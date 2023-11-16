/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.admin.cli.cluster;

import com.sun.enterprise.admin.cli.remote.RemoteCLICommand;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

//import java.net.InetAddress;
//import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;


import org.jvnet.hk2.annotations.Service;
import org.glassfish.api.Param;
import org.glassfish.api.admin.*;
import static com.sun.enterprise.admin.cli.CLIConstants.*;
import com.sun.enterprise.util.io.FileUtils;
import java.io.FileInputStream;
import org.glassfish.admin.payload.PayloadImpl;
import org.glassfish.admin.payload.PayloadFilesManager.Perm;
import org.glassfish.hk2.api.PerLookup;

/**
 * This is a local command that unbundles the bundle generated by export-sync-bundle.
 * import-sync-bundle applies the content under ${com.sun.aas.instanceRoot}/
 * directory. Synchronization cookie with DAS's timestamp should be created.
 * It also creates das.properties (if not present) under agent dir (ex.
 * installRoot/glassfish4/glassfish/nodes/<host-name>/agent/config/das.properties).
 *
 * Before running this command the instance should already have been registered in
 * DAS (server element created in DAS domain.xml) by running create-instance.
 * This command does not validate --node or instance_name.
 *
 * For upgrade - this command creates a new instance filesystem if it does not exist.
 * and completes DAS registration by setting rendezvousOccurred=true.
 *
 * For manual sync - this command creates a new instance filesystem or updates the
 * directories of an existing instance (remove existing application, generated,
 * config, docroot, lib dir first and explode the zip) and completes registration with DAS
 * by setting rendezvousOccurred=true.
 *
 * If setting of rendezvousOccurred=true with DAS fails, the command does not
 * not fail. Only a warning is printed out in the command output. We provide the
 * exact "asadmin set command" in this warning so that user can run
 * that command on DAS to change the rendezvousOccurred property for the server instance.
 *
 * Usage:
 *
 * import-sync-bundle [--node node_name] [--nodedir node_path] --file
 * xyz-sync-bundle.zip instance_name
 *
 * --node         name of the node; this is optional. The command fails if there
 * is more than one node under the default location
 * (installRoot/glassfish4/glassfish/nodes/<host-name>/)
 *
 * --nodedir      parent dir where node is created; this is optional. Default
 * location is installRoot/glassfish4/glassfish/nodes/
 *
 * --file         sync bundle created by export-sync-bundle
 *
 * instance_name  name of the server instance
 */
@Service(name = "import-sync-bundle")
@PerLookup
public class ImportSyncBundleCommand extends LocalInstanceCommand {
    @Param(name = "file_name", primary = true)
    private String syncBundle;

    @Param(name = "instance")
    private String instanceName0;

    @Param(name = "node", optional = true, alias = "nodeagent")
    protected String _node;

    String DASHost;
    int DASPort = -1;
    String DASProtocol;
    boolean dasIsSecure;

    private File dasPropsFile;
    private Properties dasProperties;

    protected boolean setDasDefaultsOnly = false;
    private File syncBundleFile = null;
    private File agentConfigDir;
    private File backupDir;

    private static final String RENDEZVOUS_PROPERTY_NAME = "rendezvousOccurred";
    private String INSTANCE_DOTTED_NAME;
    private String RENDEZVOUS_DOTTED_NAME;
    //private String RENDEZVOUS_DOTTED_NAME_VALUE;
    //private boolean isDasRunning;

    /**
     */
    @Override
    protected void validate()
            throws CommandException {

        if(ok(instanceName0))
            instanceName = instanceName0;
        else
            throw new CommandException(Strings.get("Instance.badInstanceName"));

        syncBundleFile = new File(syncBundle);
        if (!syncBundleFile.isFile())
            throw new CommandException(Strings.get("noFile", syncBundle));

        if (!isRegisteredToDAS()) {
            throw new CommandException(Strings.get("import.sync.bundle.invalidInstance", instanceName));
        }
        node = _node;

        //isDasRunning = rendezvousWithDAS() ? true : false;

        //Should we validate node and instance if das is running? No validation for now.
        //setDasDefaultsOnly = true; //Issue 12847 - Call super.validate to setDasDefaults only
        //super.validate();          //so _validate-node uses das host from das.properties. No dirs created.

        //init();

        //if (node != null && isDasRunning) {
        //    validateNode(node, getInstallRootPath(), getInstanceHostName(true));
        //}

        //setDasDefaultsOnly = false;
        super.validate(); // set ServerDirs
        init();
    }

    private void init() throws CommandException {
        agentConfigDir = new File(nodeDirChild, "agent" + File.separator + "config");
        dasPropsFile = new File(agentConfigDir, "das.properties");

        if (dasPropsFile.isFile()) {
            setDasDefaults(dasPropsFile);
        }

        DASHost = programOpts.getHost();
        DASPort = programOpts.getPort();
        dasIsSecure = programOpts.isSecure();
        DASProtocol = "http";

        INSTANCE_DOTTED_NAME = "servers.server." + instanceName;
        RENDEZVOUS_DOTTED_NAME = INSTANCE_DOTTED_NAME + ".property." + RENDEZVOUS_PROPERTY_NAME;
        //RENDEZVOUS_DOTTED_NAME_VALUE = RENDEZVOUS_DOTTED_NAME + "=true";
    }

    private boolean isRegisteredToDAS() throws CommandException {
        boolean isRegisteredOnDAS = false;
        InputStream input = null;
        XMLStreamReader reader = null;
        try {
            //find node from domain.xml
            ZipFile zip = new ZipFile(syncBundleFile);
            ZipEntry entry = zip.getEntry("config/domain.xml");
            if (entry != null) {
                input = zip.getInputStream(entry);

                reader = XMLInputFactory.newInstance().createXMLStreamReader(input);
                while (!isRegisteredOnDAS) {
                    int event = reader.next();

                    if (event == XMLStreamReader.END_DOCUMENT) {
                        break;
                    }

                    if (event == XMLStreamReader.START_ELEMENT && "server".equals(reader.getLocalName())) {
                        // get the attributes for this <server>
                        int num = reader.getAttributeCount();
                        Map<String, String> map = new HashMap<String, String>();
                        for (int i = 0; i < num; i++) {
                            map.put(reader.getAttributeName(i).getLocalPart(), reader.getAttributeValue(i));
                        }
                        String thisName = map.get("name");
                        if (instanceName.equals(thisName)) {
                            isRegisteredOnDAS = true;
                            if (_node == null) {  // if node not specified
                                _node = map.get("node"); // find it in domain.xml
                            }
                        }
                    }
                }
                if (input != null) input.close();
                if (reader != null) reader.close();
            } else {
                throw new CommandException(Strings.get("import.sync.bundle.domainXmlNotFound",
                    syncBundle));
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, Strings.get("import.sync.bundle.inboundPayloadFailed",
                    syncBundle, ex.getLocalizedMessage()), ex);
            throw new CommandException(Strings.get("import.sync.bundle.inboundPayloadFailed",
                    syncBundle, ex.getLocalizedMessage()), ex);
        } catch (XMLStreamException xe) {
            logger.log(Level.SEVERE, Strings.get("import.sync.bundle.inboundPayloadFailed",
                    syncBundle, xe.getLocalizedMessage()), xe);
            throw new CommandException(Strings.get("import.sync.bundle.inboundPayloadFailed",
                    syncBundle, xe.getLocalizedMessage()), xe);
        }

        return isRegisteredOnDAS;
    }

    /**
     */
    @Override
    protected int executeCommand()
            throws CommandException {
            int exitCode = createDirectories();
            if (exitCode == SUCCESS) {
                setRendezvousOccurred("true");
            }
            return exitCode;
    }

    private int createDirectories() throws CommandException {
        if (!agentConfigDir.isDirectory()) {
            if (!agentConfigDir.mkdirs()) {
                throw new CommandException(Strings.get("import.sync.bundle.createDirectoryFailed", agentConfigDir.getPath()));
            }
        }
        
        writeProperties();
        
        FileInputStream in = null;
        Payload.Inbound payload = null;
        try {
            in = new FileInputStream(syncBundle);
            payload = PayloadImpl.Inbound.newInstance("application/zip", in);
        } catch (IOException ex) {
            try {
                if (in != null) in.close();
            } catch (IOException ioe) {
                logger.warning(Strings.get("import.sync.bundle.closeStreamFailed",
                            syncBundle, ioe.getLocalizedMessage()));
            }
            throw new CommandException(Strings.get("import.sync.bundle.inboundPayloadFailed",
                    syncBundle, ex.getLocalizedMessage()), ex);
        }
        backupInstanceDir();
        File targetDir = this.getServerDirs().getServerDir();
        if (!targetDir.mkdirs()) {
            restoreInstanceDir();
            throw new CommandException(Strings.get("import.sync.bundle.createDirectoryFailed", targetDir.getPath()));
            
        }
        Perm perm = new Perm(targetDir, null, logger);

        try {
            perm.processParts(payload);
        } catch (Exception ex) {
            restoreInstanceDir();
            String msg = Strings.get("import.sync.bundle.extractBundleFailed",
                    syncBundle, targetDir.getAbsolutePath());
            if (ex.getLocalizedMessage() != null)
                msg = msg + "\n" + ex.getLocalizedMessage();
            throw new CommandException(msg, ex);
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ex) {
                logger.warning(Strings.get("import.sync.bundle.closeStreamFailed",
                            syncBundle, ex.getLocalizedMessage()));
            }
        }
        
        deleteBackupDir();
        return SUCCESS;
    }

    private void writeProperties() throws CommandException {
        try {
            if (!dasPropsFile.isFile()) {
                writeDasProperties();
            }
        } catch (IOException ex) {
            throw new CommandException(Strings.get("Instance.cantWriteProperties", "das.properties", ex.getLocalizedMessage()), ex);
        }
    }

    private void writeDasProperties() throws IOException {
        if (dasPropsFile.createNewFile()) {
            dasProperties = new Properties();
            dasProperties.setProperty(K_DAS_HOST, DASHost);
            dasProperties.setProperty(K_DAS_PORT, String.valueOf(DASPort));
            dasProperties.setProperty(K_DAS_IS_SECURE, String.valueOf(dasIsSecure));
            dasProperties.setProperty(K_DAS_PROTOCOL, DASProtocol);
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(dasPropsFile);
                dasProperties.store(fos, Strings.get("Instance.dasPropertyComment"));
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        }
    }

    private void backupInstanceDir() throws CommandException {
        File f = getServerDirs().getServerDir();
        if (f != null && f.isDirectory()) {
            Random r = new Random();
            setBackupDir(r.nextInt());
            File backup = getBackupDir();
            if (!f.renameTo(backup)) {
                logger.warning(Strings.get("import.sync.bundle.backupInstanceDirFailed", f.getAbsolutePath(), backup.getAbsolutePath()));
                if (FileUtils.whack(f)) { //Ask user first before deleting?
                    logger.warning(Strings.get("import.sync.bundle.deletedInstanceDir", f.getAbsolutePath()));
                }
            }

        }
    }

    private void setBackupDir(int i) {
        File f = getServerDirs().getServerDir();
        backupDir = new File(getServerDirs().getServerParentDir(), f.getName() + "_backup" + i);
    }

    private File getBackupDir() {
        return backupDir;
    }

    private void restoreInstanceDir() {
        File backup = getBackupDir();
        if (backup != null && backup.isDirectory()) {
            getServerDirs().getServerDir().delete();
            if (!backup.renameTo(getServerDirs().getServerDir())) {
                logger.warning(Strings.get("import.sync.bundle.restoreInstanceDirFailed", backup.getAbsolutePath(), getServerDirs().getServerDir().getAbsolutePath()));
            }
        }
    }

    private void deleteBackupDir() {
        File backup = getBackupDir();
        if (backup != null && backup.isDirectory()) {
            FileUtils.whack(backup);
        }
    }

    private void setRendezvousOccurred(String rendezVal) {
        String dottedName = RENDEZVOUS_DOTTED_NAME + "=" + rendezVal;
        try {
            RemoteCLICommand rc = new RemoteCLICommand("set", this.programOpts, this.env);
            rc.executeAndReturnOutput("set", dottedName);
        } catch (CommandException ex) {
            logger.warning(Strings.get("import.sync.bundle.completeRegistrationFailed", dottedName));
        }
    }

    /*private boolean rendezvousWithDAS() {
        try {
            getUptime();
            return true;
        } catch (CommandException ex) {
            return false;
        }
    }

    private String getInstanceHostName(boolean isCanonical) throws CommandException {
        String instanceHostName = null;
        InetAddress localHost = null;
        try {
            localHost = InetAddress.getLocalHost();
        } catch (UnknownHostException ex) {
            throw new CommandException(Strings.get("cantGetHostName", ex));
        }
        if (localHost != null) {
            if (isCanonical) {
                instanceHostName = localHost.getCanonicalHostName();
            } else {
                instanceHostName = localHost.getHostName();
            }
        }
        return instanceHostName;
    }

    private int validateNode(String name, String installdir, String nodeHost) throws CommandException {
        ArrayList<String> argsList = new ArrayList<String>();
        argsList.add(0, "_validate-node");

        if (nodeDir != null) {
            argsList.add("--nodedir");
            argsList.add(nodeDir);
        }
        if (nodeHost != null) {
            argsList.add("--nodehost");
            argsList.add(nodeHost);
        }
        if (installdir != null) {
            argsList.add("--installdir");
            argsList.add(installdir);
        }

        argsList.add(name);

        String[] argsArray = new String[argsList.size()];
        argsArray = argsList.toArray(argsArray);

        RemoteCLICommand rc = new RemoteCLICommand("_validate-node", this.programOpts, this.env);
        return rc.execute(argsArray);
    }

    private boolean isRegisteredToDAS() {
        boolean isRegistered = true;
        try {
            RemoteCLICommand rc = new RemoteCLICommand("get", this.programOpts, this.env);
            rc.executeAndReturnOutput("get", INSTANCE_DOTTED_NAME);
        } catch (CommandException ex) {
            isRegistered = false;
        }
        return isRegistered;
    }

    @Override
    protected boolean mkdirs(File f) {
        if (setDasDefaultsOnly) {
            return true;
        } else {
            return f.mkdirs();
        }
    }

    @Override
    protected boolean isDirectory(File f) {
        if (setDasDefaultsOnly) {
            return true;
        } else {
            return f.isDirectory();
        }
    }

    @Override
    protected boolean setServerDirs() {
        if (setDasDefaultsOnly) {
            return false;
        } else {
            return true;
        }
    }*/

}
