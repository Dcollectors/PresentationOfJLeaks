/*
 * Copyright (c) 2008-2012, Hazel Bilisim Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.nio;

import com.hazelcast.cluster.Bind;
import com.hazelcast.config.SSLConfig;
import com.hazelcast.config.SocketInterceptorConfig;
import com.hazelcast.impl.ClusterOperation;
import com.hazelcast.impl.ThreadContext;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.ssl.BasicSSLContextFactory;
import com.hazelcast.nio.ssl.SSLContextFactory;
import com.hazelcast.nio.ssl.SSLSocketChannelWrapper;
import com.hazelcast.util.Clock;
import com.hazelcast.util.ConcurrentHashSet;

import java.io.IOException;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.hazelcast.impl.Constants.IO.KILO_BYTE;

public class ConnectionManager {

    protected final ILogger logger;

    final int SOCKET_RECEIVE_BUFFER_SIZE;

    final int SOCKET_SEND_BUFFER_SIZE;

    final int SOCKET_LINGER_SECONDS;

    final boolean SOCKET_KEEP_ALIVE;

    final boolean SOCKET_NO_DELAY;

    private final ConcurrentMap<Address, Connection> mapConnections = new ConcurrentHashMap<Address, Connection>(100);

    private final ConcurrentMap<Address, ConnectionMonitor> mapMonitors = new ConcurrentHashMap<Address, ConnectionMonitor>(100);

    private final Set<Address> setConnectionInProgress = new ConcurrentHashSet<Address>();

    private final Set<ConnectionListener> setConnectionListeners = new CopyOnWriteArraySet<ConnectionListener>();

    private final Set<Connection> setActiveConnections = new ConcurrentHashSet<Connection>();

    private final AtomicInteger allTextConnections = new AtomicInteger();

    private final AtomicInteger connectionIdGen = new AtomicInteger();

    private volatile boolean live = false;

    final IOService ioService;

    private final ServerSocketChannel serverSocketChannel;

    private final InOutSelector[] selectors;

    private final AtomicInteger nextSelectorIndex = new AtomicInteger();

    private final MemberSocketInterceptor memberSocketInterceptor;

    private final ExecutorService es = Executors.newCachedThreadPool();

    private final SocketChannelWrapperFactory socketChannelWrapperFactory;

    private Thread socketAcceptorThread; // accessed only in synchronized block

    public ConnectionManager(IOService ioService, ServerSocketChannel serverSocketChannel) {
        this.ioService = ioService;
        this.serverSocketChannel = serverSocketChannel;
        this.logger = ioService.getLogger(ConnectionManager.class.getName());
        this.SOCKET_RECEIVE_BUFFER_SIZE = ioService.getSocketReceiveBufferSize() * KILO_BYTE;
        this.SOCKET_SEND_BUFFER_SIZE = ioService.getSocketSendBufferSize() * KILO_BYTE;
        this.SOCKET_LINGER_SECONDS = ioService.getSocketLingerSeconds();
        this.SOCKET_KEEP_ALIVE = ioService.getSocketKeepAlive();
        this.SOCKET_NO_DELAY = ioService.getSocketNoDelay();
        int selectorCount = ioService.getSelectorThreadCount();
        selectors = new InOutSelector[selectorCount];
        SSLConfig sslConfig = ioService.getSSLConfig();
        if (sslConfig != null && sslConfig.isEnabled()) {
            socketChannelWrapperFactory = new SSLSocketChannelWrapperFactory(sslConfig);
            logger.log(Level.INFO, "SSL is enabled");
        } else {
            socketChannelWrapperFactory = new DefaultSocketChannelWrapperFactory();
        }
        SocketInterceptorConfig sic = ioService.getSocketInterceptorConfig();
        if (sic != null && sic.isEnabled()) {
            SocketInterceptor implementation = (SocketInterceptor) sic.getImplementation();
            if (implementation == null && sic.getClassName() != null) {
                try {
                    implementation = (SocketInterceptor) Class.forName(sic.getClassName()).newInstance();
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "SocketInterceptor class cannot be instantiated!" + sic.getClassName(), e);
                }
            }
            if (implementation != null) {
                if (!(implementation instanceof MemberSocketInterceptor)) {
                    logger.log(Level.SEVERE, "SocketInterceptor must be instance of " + MemberSocketInterceptor.class.getName());
                    implementation = null;
                } else {
                    logger.log(Level.INFO, "SocketInterceptor is enabled");
                }
            }
            if (implementation != null) {
                memberSocketInterceptor = (MemberSocketInterceptor) implementation;
                memberSocketInterceptor.init(sic);
            } else {
                memberSocketInterceptor = null;
            }
        } else {
            memberSocketInterceptor = null;
        }
    }

    interface SocketChannelWrapperFactory {
        SocketChannelWrapper wrapSocketChannel(SocketChannel socketChannel, boolean client) throws Exception;
    }

    class DefaultSocketChannelWrapperFactory implements SocketChannelWrapperFactory {
        public SocketChannelWrapper wrapSocketChannel(SocketChannel socketChannel, boolean client) throws Exception {
            return new DefaultSocketChannelWrapper(socketChannel);
        }
    }

    class SSLSocketChannelWrapperFactory implements SocketChannelWrapperFactory {
        final SSLContextFactory sslContextFactory;

        SSLSocketChannelWrapperFactory(SSLConfig sslConfig) {
            if (CipherHelper.isSymmetricEncryptionEnabled(ioService)) {
                throw new RuntimeException("SSL and SymmetricEncryption cannot be both enabled!");
            }
            if (CipherHelper.isAsymmetricEncryptionEnabled(ioService)) {
                throw new RuntimeException("SSL and AsymmetricEncryption cannot be both enabled!");
            }
            SSLContextFactory sslContextFactoryObject = (SSLContextFactory) sslConfig.getFactoryImplementation();
            try {
                String factoryClassName = sslConfig.getFactoryClassName();
                if (sslContextFactoryObject == null && factoryClassName != null) {
                    sslContextFactoryObject = (SSLContextFactory) Class.forName(factoryClassName).newInstance();
                }
                if (sslContextFactoryObject == null) {
                    sslContextFactoryObject = new BasicSSLContextFactory();
                }
                sslContextFactoryObject.init(sslConfig.getProperties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            sslContextFactory = sslContextFactoryObject;
        }

        public SocketChannelWrapper wrapSocketChannel(SocketChannel socketChannel, boolean client) throws Exception {
            return new SSLSocketChannelWrapper(sslContextFactory.getSSLContext(), socketChannel, client);
        }
    }

    public IOService getIOHandler() {
        return ioService;
    }

    void executeAsync(Runnable runnable) {
        es.execute(runnable);
    }

    public MemberSocketInterceptor getMemberSocketInterceptor() {
        return memberSocketInterceptor;
    }

    private InOutSelector nextSelector() {
        if (nextSelectorIndex.get() > 1000000) {
            nextSelectorIndex.set(0);
        }
        return selectors[Math.abs(nextSelectorIndex.incrementAndGet()) % selectors.length];
    }

    public void addConnectionListener(ConnectionListener listener) {
        setConnectionListeners.add(listener);
    }

    public boolean bind(Address endPoint, Connection connection,
                        boolean accept) {
        log(Level.FINEST, "Binding " + connection + " to " + endPoint + ", accept: " + accept);
        connection.setEndPoint(endPoint);
        if (mapConnections.containsKey(endPoint)) {
            return false;
        }
        if (!endPoint.equals(ioService.getThisAddress())) {
            if (!connection.isClient()) {
                connection.setMonitor(getConnectionMonitor(endPoint, true));
            }
            if (!accept) {
                //make sure bind packet is the first packet sent to the end point.
                Packet bindPacket = createBindPacket(new Bind(ioService.getThisAddress()));
                connection.getWriteHandler().enqueueSocketWritable(bindPacket);
                //now you can send anything...
            }
            mapConnections.put(endPoint, connection);
            setConnectionInProgress.remove(endPoint);
            for (ConnectionListener listener : setConnectionListeners) {
                listener.connectionAdded(connection);
            }
        } else {
            return false;
        }
        return true;
    }

    private Packet createBindPacket(Bind rp) {
        Data value = ThreadContext.get().toData(rp);
        Packet packet = new Packet();
        packet.set("remotelyProcess", ClusterOperation.REMOTELY_PROCESS, null, value);
        packet.client = ioService.isClient();
        return packet;
    }

    Connection assignSocketChannel(SocketChannelWrapper channel) {
        InOutSelector selectorAssigned = nextSelector();
        final Connection connection = new Connection(this, selectorAssigned, connectionIdGen.incrementAndGet(), channel);
        setActiveConnections.add(connection);
        selectorAssigned.addTask(connection.getReadHandler());
        selectorAssigned.selector.wakeup();
        log(Level.INFO, channel.socket().getLocalPort()
                + " accepted socket connection from "
                + channel.socket().getRemoteSocketAddress());
        return connection;
    }

    SocketChannelWrapper wrapSocketChannel(SocketChannel socketChannel, boolean client) throws Exception {
        return socketChannelWrapperFactory.wrapSocketChannel(socketChannel, client);
    }

    void failedConnection(Address address, Throwable t, boolean silent) {
        setConnectionInProgress.remove(address);
        ioService.onFailedConnection(address);
        if (!silent) {
            getConnectionMonitor(address, false).onError(t);
        }
    }

    public Connection getConnection(Address address) {
        return mapConnections.get(address);
    }

    public Connection getOrConnect(Address address) {
        return getOrConnect(address, false);
    }

    public Connection getOrConnect(Address address, boolean silent) {
        Connection connection = mapConnections.get(address);
        if (connection == null) {
            if (setConnectionInProgress.add(address)) {
                ioService.shouldConnectTo(address);
                executeAsync(new SocketConnector(this, address, silent));
            }
        }
        return connection;
    }

    private ConnectionMonitor getConnectionMonitor(Address endpoint, boolean reset) {
        ConnectionMonitor monitor = mapMonitors.get(endpoint);
        if (monitor == null) {
            monitor = new ConnectionMonitor(this, endpoint);
            final ConnectionMonitor monitorOld = mapMonitors.putIfAbsent(endpoint, monitor);
            if (monitorOld != null) {
                monitor = monitorOld;
            }
        }
        if (reset) {
            monitor.reset();
        }
        return monitor;
    }

    // for testing purposes only
    public Connection detachAndGetConnection(Address address) {
        return mapConnections.remove(address);
    }

    // for testing purposes only
    public void attachConnection(Address address, Connection conn) {
        mapConnections.put(address, conn);
    }

    public void destroyConnection(Connection connection) {
        if (connection == null)
            return;
        log(Level.FINEST, "Destroying " + connection);
        setActiveConnections.remove(connection);
        final Address endPoint = connection.getEndPoint();
        if (endPoint != null) {
            setConnectionInProgress.remove(endPoint);
            final Connection existingConn = mapConnections.get(endPoint);
            if (existingConn == connection) {
                mapConnections.remove(endPoint);
                for (ConnectionListener listener : setConnectionListeners) {
                    listener.connectionRemoved(connection);
                }
            }
        }
        if (connection.live()) {
            connection.close();
        }
    }

    public int getTotalWriteQueueSize() {
        int count = 0;
        for (Connection conn : mapConnections.values()) {
            if (conn.live()) {
                count += conn.getWriteHandler().size();
            }
        }
        return count;
    }

    protected void initSocket(Socket socket) throws Exception {
        if (SOCKET_LINGER_SECONDS > 0) {
            socket.setSoLinger(true, SOCKET_LINGER_SECONDS);
        }
        socket.setKeepAlive(SOCKET_KEEP_ALIVE);
        socket.setTcpNoDelay(SOCKET_NO_DELAY);
        socket.setReceiveBufferSize(SOCKET_RECEIVE_BUFFER_SIZE);
        socket.setSendBufferSize(SOCKET_SEND_BUFFER_SIZE);
    }

    public synchronized void start() {
        if (live) return;
        live = true;
        log(Level.FINEST, "Starting ConnectionManager and IO selectors.");
        for (int i = 0; i < selectors.length; i++) {
            InOutSelector s = new InOutSelector(this);
            selectors[i] = s;
            new Thread(ioService.getThreadGroup(), s, ioService.getThreadPrefix() + i).start();
        }
        if (serverSocketChannel != null) {
            if (socketAcceptorThread != null) {
                logger.log(Level.WARNING, "SocketAcceptor thread is already live! Shutting down old acceptor...");
                shutdownSocketAcceptor();
            }
            Runnable acceptRunnable = new SocketAcceptor(serverSocketChannel, this);
            socketAcceptorThread = new Thread(ioService.getThreadGroup(), acceptRunnable,
                    ioService.getThreadPrefix() + "Acceptor");
            socketAcceptorThread.start();
        }
    }

    public synchronized void onRestart() {
        stop();
        start();
    }

    public synchronized void shutdown() {
        if (!live) return;
        live = false;
        stop();
        if (serverSocketChannel != null) {
            try {
                log(Level.FINEST, "Closing server socket channel: " + serverSocketChannel);
                serverSocketChannel.close();
            } catch (IOException ignore) {
                logger.log(Level.FINEST, ignore.getMessage(), ignore);
            }
        }
        es.shutdownNow();
    }

    private void stop() {
        live = false;
        log(Level.FINEST, "Stopping ConnectionManager");
        shutdownSocketAcceptor(); // interrupt acceptor thread after live=false
        ioService.onShutdown();
        for (Connection conn : mapConnections.values()) {
            try {
                destroyConnection(conn);
            } catch (final Throwable ignore) {
                logger.log(Level.FINEST, ignore.getMessage(), ignore);
            }
        }
        for (Connection conn : setActiveConnections) {
            try {
                destroyConnection(conn);
            } catch (final Throwable ignore) {
                logger.log(Level.FINEST, ignore.getMessage(), ignore);
            }
        }
        shutdownIOSelectors();
        setConnectionInProgress.clear();
        mapConnections.clear();
        mapMonitors.clear();
        setActiveConnections.clear();
    }

    private synchronized void shutdownIOSelectors() {
        log(Level.FINEST, "Shutting down IO selectors, total: " + selectors.length);
        for (int i = 0; i < selectors.length; i++) {
            InOutSelector ioSelector = selectors[i];
            if (ioSelector != null) {
                ioSelector.shutdown();
            }
            selectors[i] = null;
        }
    }

    private synchronized void shutdownSocketAcceptor() {
        log(Level.FINEST, "Shutting down SocketAcceptor thread.");
        socketAcceptorThread.interrupt();
        socketAcceptorThread = null;
    }

    public int getCurrentClientConnections() {
        int count = 0;
        for (Connection conn : setActiveConnections) {
            if (conn.live()) {
                if (conn.isClient()) {
                    count++;
                }
            }
        }
        return count;
    }

    public int getAllTextConnections() {
        return allTextConnections.get();
    }

    public void incrementTextConnections() {
        allTextConnections.incrementAndGet();
    }

    public boolean isLive() {
        return live;
    }

    public Map<Address, Connection> getReadonlyConnectionMap() {
        return Collections.unmodifiableMap(mapConnections);
    }

    private void log(Level level, String message) {
        logger.log(level, message);
        ioService.getSystemLogService().logConnection(message);
    }

    public void appendState(StringBuffer sbState) {
        long now = Clock.currentTimeMillis();
        sbState.append("\nConnectionManager {");
        for (Connection conn : mapConnections.values()) {
            long wr = (now - conn.getWriteHandler().lastRegistration) / 1000;
            long wh = (now - conn.getWriteHandler().lastHandle) / 1000;
            long rr = (now - conn.getReadHandler().lastRegistration) / 1000;
            long rh = (now - conn.getReadHandler().lastHandle) / 1000;
            sbState.append("\n\tEndPoint: ").append(conn.getEndPoint());
            sbState.append("  ").append(conn.live());
            sbState.append("  ").append(conn.getWriteHandler().size());
            sbState.append("  w:").append(wr).append("/").append(wh);
            sbState.append("  r:").append(rr).append("/").append(rh);
        }
        sbState.append("\n}");
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("Connections {");
        for (Connection conn : mapConnections.values()) {
            sb.append("\n");
            sb.append(conn);
        }
        sb.append("\nlive=");
        sb.append(live);
        sb.append("\n}");
        return sb.toString();
    }
}
