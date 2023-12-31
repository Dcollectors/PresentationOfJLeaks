/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.annotation.PublicApi;
import alluxio.client.AbstractOutStream;
import alluxio.client.AlluxioStorageType;
import alluxio.client.UnderStorageType;
import alluxio.client.block.BufferedBlockOutStream;
import alluxio.client.file.options.CancelUfsFileOptions;
import alluxio.client.file.options.CompleteFileOptions;
import alluxio.client.file.options.CompleteUfsFileOptions;
import alluxio.client.file.options.CreateUfsFileOptions;
import alluxio.client.file.options.OutStreamOptions;
import alluxio.client.file.policy.FileWriteLocationPolicy;
import alluxio.exception.AlluxioException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.PreconditionMessage;
import alluxio.metrics.MetricsSystem;
import alluxio.security.authorization.Permission;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.CreateOptions;
import alluxio.util.IdUtils;
import alluxio.util.io.PathUtils;
import alluxio.wire.WorkerNetAddress;

import com.codahale.metrics.Counter;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Provides a streaming API to write a file. This class wraps the BlockOutStreams for each of the
 * blocks in the file and abstracts the switching between streams. The backing streams can write to
 * Alluxio space in the local machine or remote machines. If the {@link UnderStorageType} is
 * {@link UnderStorageType#SYNC_PERSIST}, another stream will write the data to the under storage
 * system.
 */
@PublicApi
@NotThreadSafe
public class FileOutStream extends AbstractOutStream {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final long mBlockSize;
  protected final AlluxioStorageType mAlluxioStorageType;
  private final UnderStorageType mUnderStorageType;
  private final FileSystemContext mContext;
  private final UnderFileSystemFileOutStream.Factory mUnderOutStreamFactory;
  private final OutputStream mUnderStorageOutputStream;
  private final long mNonce;
  /** Whether this stream should delegate operations to the ufs to a worker. */
  private final boolean mUfsDelegation;
  /** The client to a file system worker, null if mUfsDelegation is false. */
  private final FileSystemWorkerClient mFileSystemWorkerClient;
  /** The worker file id for the ufs file, null if mUfsDelegation is false. */
  private final Long mUfsFileId;

  private String mUfsPath;
  private FileWriteLocationPolicy mLocationPolicy;

  protected boolean mCanceled;
  protected boolean mClosed;
  private boolean mShouldCacheCurrentBlock;
  protected BufferedBlockOutStream mCurrentBlockOutStream;
  protected List<BufferedBlockOutStream> mPreviousBlockOutStreams;

  protected final AlluxioURI mUri;

  /**
   * Creates a new file output stream.
   *
   * @param path the file path
   * @param options the client options
   * @throws IOException if an I/O error occurs
   */
  public FileOutStream(AlluxioURI path, OutStreamOptions options) throws IOException {
    this(path, options, FileSystemContext.INSTANCE, UnderFileSystemFileOutStream.Factory.get());
  }

  /**
   * Creates a new file output stream.
   *
   * @param path the file path
   * @param options the client options
   * @param context the file system context
   * @param underOutStreamFactory a factory for creating any necessary under storage out streams
   * @throws IOException if an I/O error occurs
   */
  public FileOutStream(AlluxioURI path, OutStreamOptions options, FileSystemContext context,
      UnderFileSystemFileOutStream.Factory underOutStreamFactory) throws IOException {
    mUri = Preconditions.checkNotNull(path);
    mNonce = IdUtils.getRandomNonNegativeLong();
    mBlockSize = options.getBlockSizeBytes();
    mAlluxioStorageType = options.getAlluxioStorageType();
    mUnderStorageType = options.getUnderStorageType();
    mContext = context;
    mUnderOutStreamFactory = underOutStreamFactory;
    mPreviousBlockOutStreams = new LinkedList<>();
    mUfsDelegation = Configuration.getBoolean(PropertyKey.USER_UFS_DELEGATION_ENABLED);
    if (mUnderStorageType.isSyncPersist()) {
      // Get the ufs path from the master.
      FileSystemMasterClient client = mContext.acquireMasterClient();
      try {
        mUfsPath = client.getStatus(mUri).getUfsPath();
      } catch (AlluxioException e) {
        throw new IOException(e);
      } finally {
        mContext.releaseMasterClient(client);
      }
      if (mUfsDelegation) {
        mFileSystemWorkerClient = mContext.createWorkerClient();
        try {
          Permission perm = options.getPermission();
          mUfsFileId =
              mFileSystemWorkerClient.createUfsFile(new AlluxioURI(mUfsPath),
                  CreateUfsFileOptions.defaults().setPermission(perm));
        } catch (AlluxioException e) {
          mFileSystemWorkerClient.close();
          throw new IOException(e);
        }
        mUnderStorageOutputStream = mUnderOutStreamFactory
            .create(mFileSystemWorkerClient.getWorkerDataServerAddress(), mUfsFileId);
      } else {
        String tmpPath = PathUtils.temporaryFileName(mNonce, mUfsPath);
        UnderFileSystem ufs = UnderFileSystem.get(tmpPath);
        // TODO(jiri): Implement collection of temporary files left behind by dead clients.
        CreateOptions createOptions = new CreateOptions().setPermission(options.getPermission());
        mUnderStorageOutputStream = ufs.create(tmpPath, createOptions);

        // Set delegation related vars to null as we are not using worker delegation for ufs ops
        mFileSystemWorkerClient = null;
        mUfsFileId = null;
      }
    } else {
      mUfsPath = null;
      mUnderStorageOutputStream = null;
      mFileSystemWorkerClient = null;
      mUfsFileId = null;
    }
    mClosed = false;
    mCanceled = false;
    mShouldCacheCurrentBlock = mAlluxioStorageType.isStore();
    mBytesWritten = 0;
    mLocationPolicy = Preconditions.checkNotNull(options.getLocationPolicy(),
        PreconditionMessage.FILE_WRITE_LOCATION_POLICY_UNSPECIFIED);
  }

  @Override
  public void cancel() throws IOException {
    mCanceled = true;
    close();
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    if (mCurrentBlockOutStream != null) {
      mPreviousBlockOutStreams.add(mCurrentBlockOutStream);
    }

    CompleteFileOptions options = CompleteFileOptions.defaults();
    if (mUnderStorageType.isSyncPersist()) {
      if (mUfsDelegation) {
        mUnderStorageOutputStream.close();
        try {
          if (mCanceled) {
            mFileSystemWorkerClient.cancelUfsFile(mUfsFileId, CancelUfsFileOptions.defaults());
          } else {
            long len =
                mFileSystemWorkerClient.completeUfsFile(mUfsFileId,
                    CompleteUfsFileOptions.defaults());
            options.setUfsLength(len);
          }
        } catch (AlluxioException e) {
          throw new IOException(e);
        } finally {
          mFileSystemWorkerClient.close();
        }
      } else {
        String tmpPath = PathUtils.temporaryFileName(mNonce, mUfsPath);
        UnderFileSystem ufs = UnderFileSystem.get(tmpPath);
        if (mCanceled) {
          // TODO(yupeng): Handle this special case in under storage integrations.
          mUnderStorageOutputStream.close();
          ufs.delete(tmpPath, false);
        } else {
          mUnderStorageOutputStream.flush();
          mUnderStorageOutputStream.close();
          if (!ufs.rename(tmpPath, mUfsPath)) { // Failed to commit file
            ufs.delete(tmpPath, false);
            throw new IOException("Failed to rename " + tmpPath + " to " + mUfsPath);
          }
          options.setUfsLength(ufs.getFileSize(mUfsPath));
        }
      }
    }

    if (mAlluxioStorageType.isStore()) {
      try {
        if (mCanceled) {
          for (BufferedBlockOutStream bos : mPreviousBlockOutStreams) {
            bos.cancel();
          }
        } else {
          for (BufferedBlockOutStream bos : mPreviousBlockOutStreams) {
            bos.close();
          }
        }
      } catch (IOException e) {
        handleCacheWriteException(e);
      }
    }

    // Complete the file if it's ready to be completed.
    if (!mCanceled && (mUnderStorageType.isSyncPersist() || mAlluxioStorageType.isStore())) {
      FileSystemMasterClient masterClient = mContext.acquireMasterClient();
      try {
        masterClient.completeFile(mUri, options);
      } catch (AlluxioException e) {
        throw new IOException(e);
      } finally {
        mContext.releaseMasterClient(masterClient);
      }
    }

    if (mUnderStorageType.isAsyncPersist()) {
      scheduleAsyncPersist();
    }
    mClosed = true;
  }

  @Override
  public void flush() throws IOException {
    // TODO(yupeng): Handle flush for Alluxio storage stream as well.
    if (mUnderStorageType.isSyncPersist()) {
      mUnderStorageOutputStream.flush();
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (mShouldCacheCurrentBlock) {
      try {
        if (mCurrentBlockOutStream == null || mCurrentBlockOutStream.remaining() == 0) {
          getNextBlock();
        }
        mCurrentBlockOutStream.write(b);
      } catch (IOException e) {
        handleCacheWriteException(e);
      }
    }

    if (mUnderStorageType.isSyncPersist()) {
      mUnderStorageOutputStream.write(b);
      Metrics.BYTES_WRITTEN_UFS.inc();
    }
    mBytesWritten++;
  }

  @Override
  public void write(byte[] b) throws IOException {
    Preconditions.checkArgument(b != null, PreconditionMessage.ERR_WRITE_BUFFER_NULL);
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    Preconditions.checkArgument(b != null, PreconditionMessage.ERR_WRITE_BUFFER_NULL);
    Preconditions.checkArgument(off >= 0 && len >= 0 && len + off <= b.length,
        PreconditionMessage.ERR_BUFFER_STATE.toString(), b.length, off, len);

    if (mShouldCacheCurrentBlock) {
      try {
        int tLen = len;
        int tOff = off;
        while (tLen > 0) {
          if (mCurrentBlockOutStream == null || mCurrentBlockOutStream.remaining() == 0) {
            getNextBlock();
          }
          long currentBlockLeftBytes = mCurrentBlockOutStream.remaining();
          if (currentBlockLeftBytes >= tLen) {
            mCurrentBlockOutStream.write(b, tOff, tLen);
            tLen = 0;
          } else {
            mCurrentBlockOutStream.write(b, tOff, (int) currentBlockLeftBytes);
            tOff += currentBlockLeftBytes;
            tLen -= currentBlockLeftBytes;
          }
        }
      } catch (IOException e) {
        handleCacheWriteException(e);
      }
    }

    if (mUnderStorageType.isSyncPersist()) {
      mUnderStorageOutputStream.write(b, off, len);
      Metrics.BYTES_WRITTEN_UFS.inc(len);
    }
    mBytesWritten += len;
  }

  private void getNextBlock() throws IOException {
    if (mCurrentBlockOutStream != null) {
      Preconditions.checkState(mCurrentBlockOutStream.remaining() <= 0,
          PreconditionMessage.ERR_BLOCK_REMAINING);
      mPreviousBlockOutStreams.add(mCurrentBlockOutStream);
    }

    if (mAlluxioStorageType.isStore()) {
      try {
        WorkerNetAddress address = mLocationPolicy
            .getWorkerForNextBlock(mContext.getAlluxioBlockStore().getWorkerInfoList(), mBlockSize);
        mCurrentBlockOutStream =
            mContext.getAlluxioBlockStore().getOutStream(getNextBlockId(), mBlockSize, address);
        mShouldCacheCurrentBlock = true;
      } catch (AlluxioException e) {
        throw new IOException(e);
      }
    }
  }

  private long getNextBlockId() throws IOException {
    FileSystemMasterClient masterClient = mContext.acquireMasterClient();
    try {
      return masterClient.getNewBlockIdForFile(mUri);
    } catch (AlluxioException e) {
      throw new IOException(e);
    } finally {
      mContext.releaseMasterClient(masterClient);
    }
  }

  protected void handleCacheWriteException(IOException e) throws IOException {
    if (!mUnderStorageType.isSyncPersist()) {
      throw new IOException(ExceptionMessage.FAILED_CACHE.getMessage(e.getMessage()), e);
    }

    LOG.warn("Failed to write into AlluxioStore, canceling write attempt.", e);
    if (mCurrentBlockOutStream != null) {
      mShouldCacheCurrentBlock = false;
      mCurrentBlockOutStream.cancel();
    }
  }

  /**
   * Schedules the async persistence of the current file.
   *
   * @throws IOException an I/O error occurs
   */
  protected void scheduleAsyncPersist() throws IOException {
    FileSystemMasterClient masterClient = mContext.acquireMasterClient();
    try {
      masterClient.scheduleAsyncPersist(mUri);
    } catch (AlluxioException e) {
      throw new IOException(e);
    } finally {
      mContext.releaseMasterClient(masterClient);
    }
  }

  /**
   * Class that contains metrics about FileOutStream.
   */
  @ThreadSafe
  private static final class Metrics {
    private static final Counter BYTES_WRITTEN_UFS = MetricsSystem.clientCounter("BytesWrittenUfs");

    private Metrics() {} // prevent instantiation
  }
}
/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.annotation.PublicApi;
import alluxio.client.AbstractOutStream;
import alluxio.client.AlluxioStorageType;
import alluxio.client.UnderStorageType;
import alluxio.client.block.BufferedBlockOutStream;
import alluxio.client.file.options.CancelUfsFileOptions;
import alluxio.client.file.options.CompleteFileOptions;
import alluxio.client.file.options.CompleteUfsFileOptions;
import alluxio.client.file.options.CreateUfsFileOptions;
import alluxio.client.file.options.OutStreamOptions;
import alluxio.client.file.policy.FileWriteLocationPolicy;
import alluxio.exception.AlluxioException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.PreconditionMessage;
import alluxio.metrics.MetricsSystem;
import alluxio.security.authorization.Permission;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.CreateOptions;
import alluxio.util.IdUtils;
import alluxio.util.io.PathUtils;
import alluxio.wire.WorkerNetAddress;

import com.codahale.metrics.Counter;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Provides a streaming API to write a file. This class wraps the BlockOutStreams for each of the
 * blocks in the file and abstracts the switching between streams. The backing streams can write to
 * Alluxio space in the local machine or remote machines. If the {@link UnderStorageType} is
 * {@link UnderStorageType#SYNC_PERSIST}, another stream will write the data to the under storage
 * system.
 */
@PublicApi
@NotThreadSafe
public class FileOutStream extends AbstractOutStream {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final long mBlockSize;
  protected final AlluxioStorageType mAlluxioStorageType;
  private final UnderStorageType mUnderStorageType;
  private final FileSystemContext mContext;
  private final UnderFileSystemFileOutStream.Factory mUnderOutStreamFactory;
  private final OutputStream mUnderStorageOutputStream;
  private final long mNonce;
  /** Whether this stream should delegate operations to the ufs to a worker. */
  private final boolean mUfsDelegation;
  /** The client to a file system worker, null if mUfsDelegation is false. */
  private final FileSystemWorkerClient mFileSystemWorkerClient;
  /** The worker file id for the ufs file, null if mUfsDelegation is false. */
  private final Long mUfsFileId;

  private String mUfsPath;
  private FileWriteLocationPolicy mLocationPolicy;

  protected boolean mCanceled;
  protected boolean mClosed;
  private boolean mShouldCacheCurrentBlock;
  protected BufferedBlockOutStream mCurrentBlockOutStream;
  protected List<BufferedBlockOutStream> mPreviousBlockOutStreams;

  protected final AlluxioURI mUri;

  /**
   * Creates a new file output stream.
   *
   * @param path the file path
   * @param options the client options
   * @throws IOException if an I/O error occurs
   */
  public FileOutStream(AlluxioURI path, OutStreamOptions options) throws IOException {
    this(path, options, FileSystemContext.INSTANCE, UnderFileSystemFileOutStream.Factory.get());
  }

  /**
   * Creates a new file output stream.
   *
   * @param path the file path
   * @param options the client options
   * @param context the file system context
   * @param underOutStreamFactory a factory for creating any necessary under storage out streams
   * @throws IOException if an I/O error occurs
   */
  public FileOutStream(AlluxioURI path, OutStreamOptions options, FileSystemContext context,
      UnderFileSystemFileOutStream.Factory underOutStreamFactory) throws IOException {
    mUri = Preconditions.checkNotNull(path);
    mNonce = IdUtils.getRandomNonNegativeLong();
    mBlockSize = options.getBlockSizeBytes();
    mAlluxioStorageType = options.getAlluxioStorageType();
    mUnderStorageType = options.getUnderStorageType();
    mContext = context;
    mUnderOutStreamFactory = underOutStreamFactory;
    mPreviousBlockOutStreams = new LinkedList<>();
    mUfsDelegation = Configuration.getBoolean(PropertyKey.USER_UFS_DELEGATION_ENABLED);
    if (mUnderStorageType.isSyncPersist()) {
      // Get the ufs path from the master.
      FileSystemMasterClient client = mContext.acquireMasterClient();
      try {
        mUfsPath = client.getStatus(mUri).getUfsPath();
      } catch (AlluxioException e) {
        throw new IOException(e);
      } finally {
        mContext.releaseMasterClient(client);
      }
      if (mUfsDelegation) {
        mFileSystemWorkerClient = mContext.createWorkerClient();
        try {
          Permission perm = options.getPermission();
          mUfsFileId =
              mFileSystemWorkerClient.createUfsFile(new AlluxioURI(mUfsPath),
                  CreateUfsFileOptions.defaults().setPermission(perm));
        } catch (AlluxioException e) {
          mFileSystemWorkerClient.close();
          throw new IOException(e);
        }
        mUnderStorageOutputStream = mUnderOutStreamFactory
            .create(mFileSystemWorkerClient.getWorkerDataServerAddress(), mUfsFileId);
      } else {
        String tmpPath = PathUtils.temporaryFileName(mNonce, mUfsPath);
        UnderFileSystem ufs = UnderFileSystem.get(tmpPath);
        // TODO(jiri): Implement collection of temporary files left behind by dead clients.
        CreateOptions createOptions = new CreateOptions().setPermission(options.getPermission());
        mUnderStorageOutputStream = ufs.create(tmpPath, createOptions);

        // Set delegation related vars to null as we are not using worker delegation for ufs ops
        mFileSystemWorkerClient = null;
        mUfsFileId = null;
      }
    } else {
      mUfsPath = null;
      mUnderStorageOutputStream = null;
      mFileSystemWorkerClient = null;
      mUfsFileId = null;
    }
    mClosed = false;
    mCanceled = false;
    mShouldCacheCurrentBlock = mAlluxioStorageType.isStore();
    mBytesWritten = 0;
    mLocationPolicy = Preconditions.checkNotNull(options.getLocationPolicy(),
        PreconditionMessage.FILE_WRITE_LOCATION_POLICY_UNSPECIFIED);
  }

  @Override
  public void cancel() throws IOException {
    mCanceled = true;
    close();
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    if (mCurrentBlockOutStream != null) {
      mPreviousBlockOutStreams.add(mCurrentBlockOutStream);
    }

    CompleteFileOptions options = CompleteFileOptions.defaults();
    if (mUnderStorageType.isSyncPersist()) {
      if (mUfsDelegation) {
        mUnderStorageOutputStream.close();
        try {
          if (mCanceled) {
            mFileSystemWorkerClient.cancelUfsFile(mUfsFileId, CancelUfsFileOptions.defaults());
          } else {
            long len =
                mFileSystemWorkerClient.completeUfsFile(mUfsFileId,
                    CompleteUfsFileOptions.defaults());
            options.setUfsLength(len);
          }
        } catch (AlluxioException e) {
          throw new IOException(e);
        } finally {
          mFileSystemWorkerClient.close();
        }
      } else {
        String tmpPath = PathUtils.temporaryFileName(mNonce, mUfsPath);
        UnderFileSystem ufs = UnderFileSystem.get(tmpPath);
        if (mCanceled) {
          // TODO(yupeng): Handle this special case in under storage integrations.
          mUnderStorageOutputStream.close();
          ufs.delete(tmpPath, false);
        } else {
          mUnderStorageOutputStream.flush();
          mUnderStorageOutputStream.close();
          if (!ufs.rename(tmpPath, mUfsPath)) { // Failed to commit file
            ufs.delete(tmpPath, false);
            throw new IOException("Failed to rename " + tmpPath + " to " + mUfsPath);
          }
          options.setUfsLength(ufs.getFileSize(mUfsPath));
        }
      }
    }

    if (mAlluxioStorageType.isStore()) {
      try {
        if (mCanceled) {
          for (BufferedBlockOutStream bos : mPreviousBlockOutStreams) {
            bos.cancel();
          }
        } else {
          for (BufferedBlockOutStream bos : mPreviousBlockOutStreams) {
            bos.close();
          }
        }
      } catch (IOException e) {
        handleCacheWriteException(e);
      }
    }

    // Complete the file if it's ready to be completed.
    if (!mCanceled && (mUnderStorageType.isSyncPersist() || mAlluxioStorageType.isStore())) {
      FileSystemMasterClient masterClient = mContext.acquireMasterClient();
      try {
        masterClient.completeFile(mUri, options);
      } catch (AlluxioException e) {
        throw new IOException(e);
      } finally {
        mContext.releaseMasterClient(masterClient);
      }
    }

    if (mUnderStorageType.isAsyncPersist()) {
      scheduleAsyncPersist();
    }
    mClosed = true;
  }

  @Override
  public void flush() throws IOException {
    // TODO(yupeng): Handle flush for Alluxio storage stream as well.
    if (mUnderStorageType.isSyncPersist()) {
      mUnderStorageOutputStream.flush();
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (mShouldCacheCurrentBlock) {
      try {
        if (mCurrentBlockOutStream == null || mCurrentBlockOutStream.remaining() == 0) {
          getNextBlock();
        }
        mCurrentBlockOutStream.write(b);
      } catch (IOException e) {
        handleCacheWriteException(e);
      }
    }

    if (mUnderStorageType.isSyncPersist()) {
      mUnderStorageOutputStream.write(b);
      Metrics.BYTES_WRITTEN_UFS.inc();
    }
    mBytesWritten++;
  }

  @Override
  public void write(byte[] b) throws IOException {
    Preconditions.checkArgument(b != null, PreconditionMessage.ERR_WRITE_BUFFER_NULL);
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    Preconditions.checkArgument(b != null, PreconditionMessage.ERR_WRITE_BUFFER_NULL);
    Preconditions.checkArgument(off >= 0 && len >= 0 && len + off <= b.length,
        PreconditionMessage.ERR_BUFFER_STATE.toString(), b.length, off, len);

    if (mShouldCacheCurrentBlock) {
      try {
        int tLen = len;
        int tOff = off;
        while (tLen > 0) {
          if (mCurrentBlockOutStream == null || mCurrentBlockOutStream.remaining() == 0) {
            getNextBlock();
          }
          long currentBlockLeftBytes = mCurrentBlockOutStream.remaining();
          if (currentBlockLeftBytes >= tLen) {
            mCurrentBlockOutStream.write(b, tOff, tLen);
            tLen = 0;
          } else {
            mCurrentBlockOutStream.write(b, tOff, (int) currentBlockLeftBytes);
            tOff += currentBlockLeftBytes;
            tLen -= currentBlockLeftBytes;
          }
        }
      } catch (IOException e) {
        handleCacheWriteException(e);
      }
    }

    if (mUnderStorageType.isSyncPersist()) {
      mUnderStorageOutputStream.write(b, off, len);
      Metrics.BYTES_WRITTEN_UFS.inc(len);
    }
    mBytesWritten += len;
  }

  private void getNextBlock() throws IOException {
    if (mCurrentBlockOutStream != null) {
      Preconditions.checkState(mCurrentBlockOutStream.remaining() <= 0,
          PreconditionMessage.ERR_BLOCK_REMAINING);
      mPreviousBlockOutStreams.add(mCurrentBlockOutStream);
    }

    if (mAlluxioStorageType.isStore()) {
      try {
        WorkerNetAddress address = mLocationPolicy
            .getWorkerForNextBlock(mContext.getAlluxioBlockStore().getWorkerInfoList(), mBlockSize);
        mCurrentBlockOutStream =
            mContext.getAlluxioBlockStore().getOutStream(getNextBlockId(), mBlockSize, address);
        mShouldCacheCurrentBlock = true;
      } catch (AlluxioException e) {
        throw new IOException(e);
      }
    }
  }

  private long getNextBlockId() throws IOException {
    FileSystemMasterClient masterClient = mContext.acquireMasterClient();
    try {
      return masterClient.getNewBlockIdForFile(mUri);
    } catch (AlluxioException e) {
      throw new IOException(e);
    } finally {
      mContext.releaseMasterClient(masterClient);
    }
  }

  protected void handleCacheWriteException(IOException e) throws IOException {
    if (!mUnderStorageType.isSyncPersist()) {
      throw new IOException(ExceptionMessage.FAILED_CACHE.getMessage(e.getMessage()), e);
    }

    LOG.warn("Failed to write into AlluxioStore, canceling write attempt.", e);
    if (mCurrentBlockOutStream != null) {
      mShouldCacheCurrentBlock = false;
      mCurrentBlockOutStream.cancel();
    }
  }

  /**
   * Schedules the async persistence of the current file.
   *
   * @throws IOException an I/O error occurs
   */
  protected void scheduleAsyncPersist() throws IOException {
    FileSystemMasterClient masterClient = mContext.acquireMasterClient();
    try {
      masterClient.scheduleAsyncPersist(mUri);
    } catch (AlluxioException e) {
      throw new IOException(e);
    } finally {
      mContext.releaseMasterClient(masterClient);
    }
  }

  /**
   * Class that contains metrics about FileOutStream.
   */
  @ThreadSafe
  private static final class Metrics {
    private static final Counter BYTES_WRITTEN_UFS = MetricsSystem.clientCounter("BytesWrittenUfs");

    private Metrics() {} // prevent instantiation
  }
}
