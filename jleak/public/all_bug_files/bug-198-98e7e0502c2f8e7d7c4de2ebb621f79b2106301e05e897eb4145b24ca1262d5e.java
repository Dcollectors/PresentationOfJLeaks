/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linkedin.pinot.core.segment.index.converter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.core.indexsegment.generator.SegmentVersion;
import com.linkedin.pinot.core.segment.creator.impl.V1Constants;
import com.linkedin.pinot.core.segment.index.SegmentMetadataImpl;
import com.linkedin.pinot.core.segment.memory.PinotDataBuffer;
import com.linkedin.pinot.core.segment.store.ColumnIndexType;
import com.linkedin.pinot.core.segment.store.SegmentDirectory;
import com.linkedin.pinot.core.segment.store.SegmentDirectoryPaths;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SegmentV1V2ToV3FormatConverter implements SegmentFormatConverter {
  private static Logger LOGGER = LoggerFactory.getLogger(SegmentV1V2ToV3FormatConverter.class);
  private static final String V3_TEMP_DIR_SUFFIX = ".v3.tmp";

  // NOTE: this can convert segments in v1 and v2 format to v3.
  // we use variable names with v2 prefix for readability
  @Override
  public void convert(File v2SegmentDirectory)
      throws Exception {
    Preconditions.checkNotNull(v2SegmentDirectory, "Segment directory should not be null");

    Preconditions.checkState(v2SegmentDirectory.exists() && v2SegmentDirectory.isDirectory(),
        "Segment directory: " + v2SegmentDirectory.toString() + " must exist and should be a directory");

    LOGGER.info("Converting segment: {} to v3 format", v2SegmentDirectory);

    // check existing segment version
    SegmentMetadataImpl v2Metadata = new SegmentMetadataImpl(v2SegmentDirectory);
    SegmentVersion oldVersion = SegmentVersion.valueOf(v2Metadata.getVersion());
    Preconditions.checkState(oldVersion != SegmentVersion.v3, "Segment {} is already in v3 format but at wrong path",
        v2Metadata.getName());

    Preconditions.checkArgument(oldVersion == SegmentVersion.v1 || oldVersion == SegmentVersion.v2,
        "Can not convert segment version: {} at path: {} ", oldVersion, v2SegmentDirectory);

    deleteStaleConversionDirectories(v2SegmentDirectory);

    
    File v3TempDirectory = v3ConversionTempDirectory(v2SegmentDirectory);
    setDirectoryPermissions(v3TempDirectory);

    createMetadataFile(v2SegmentDirectory, v3TempDirectory);
    copyCreationMetadata(v2SegmentDirectory, v3TempDirectory);
    copyIndexData(v2SegmentDirectory, v2Metadata, v3TempDirectory);

    File newLocation = SegmentDirectoryPaths.segmentDirectoryFor(v2SegmentDirectory, SegmentVersion.v3);
    LOGGER.info("v3 segment location for segment: {} is {}", v2Metadata.getName(), newLocation);
    v3TempDirectory.renameTo(newLocation);
  }

  @VisibleForTesting
  public File v3ConversionTempDirectory(File v2SegmentDirectory)
      throws IOException {
    File v3TempDirectory = Files.createTempDirectory(v2SegmentDirectory.toPath(),
        v2SegmentDirectory.getName() + V3_TEMP_DIR_SUFFIX).toFile();
    return v3TempDirectory;

  }
  private void setDirectoryPermissions(File v3Directory)
      throws IOException {
    EnumSet<PosixFilePermission> permissions = EnumSet
        .of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);

    Files.setPosixFilePermissions(v3Directory.toPath(), permissions);
  }

  private void copyIndexData(File v2Directory, SegmentMetadataImpl v2Metadata, File v3Directory)
      throws IOException, ConfigurationException {
    SegmentMetadataImpl v3Metadata = new SegmentMetadataImpl(v3Directory);
    SegmentDirectory v2Segment = SegmentDirectory.createFromLocalFS(v2Directory, v2Metadata, ReadMode.mmap);
    SegmentDirectory v3Segment = SegmentDirectory.createFromLocalFS(v3Directory, v3Metadata, ReadMode.mmap);

    // for each dictionary and each fwdIndex, copy that to newDirectory buffer
    Set<String> allColumns = v2Metadata.getAllColumns();
    SegmentDirectory.Reader v2DataReader = v2Segment.createReader();
    SegmentDirectory.Writer v3DataWriter = v3Segment.createWriter();

    for (String column : allColumns) {
      LOGGER.debug("Converting segment: {} , column: {}", v2Directory, column);
      copyDictionary(v2DataReader, v3DataWriter, column);
      copyForwardIndex(v2DataReader, v3DataWriter, column);
    }

    // inverted indexes are intentionally stored at the end of the single file
    for (String column : allColumns) {
      copyExistingInvertedIndex(v2DataReader, v3DataWriter, column);
    }
    v2DataReader.close();
    v3DataWriter.saveAndClose();
  }

  private void copyDictionary(SegmentDirectory.Reader reader,
      SegmentDirectory.Writer writer,
      String column)
      throws IOException {
    readCopyBuffers(reader, writer, column, ColumnIndexType.DICTIONARY);
  }

  private void copyForwardIndex(SegmentDirectory.Reader reader,
      SegmentDirectory.Writer writer,
      String column)
      throws IOException {
    readCopyBuffers(reader, writer, column, ColumnIndexType.FORWARD_INDEX);
  }

  private void copyExistingInvertedIndex(SegmentDirectory.Reader reader,
      SegmentDirectory.Writer writer,
      String column)
      throws IOException {
    if (reader.hasIndexFor(column, ColumnIndexType.INVERTED_INDEX)) {
      readCopyBuffers(reader, writer, column, ColumnIndexType.INVERTED_INDEX);
    }
  }

  private void readCopyBuffers(SegmentDirectory.Reader reader, SegmentDirectory.Writer writer,
      String column, ColumnIndexType indexType)
      throws IOException {

    PinotDataBuffer oldBuffer = reader.getIndexFor(column, indexType);
    Preconditions.checkState(oldBuffer.size() > 0 && oldBuffer.size() < Integer.MAX_VALUE,
        "Buffer sizes of greater than 2GB is not supported. segment: " + reader.toString() +
            ", column: " + column);
    PinotDataBuffer newDictBuffer =
        writer.newIndexFor(column, indexType, (int) oldBuffer.size());
    // this shouldn't copy data
    ByteBuffer bb = oldBuffer.toDirectByteBuffer(0, (int) oldBuffer.size());
    newDictBuffer.readFrom(bb, 0, 0, bb.limit());
  }

  private void createMetadataFile(File currentDir, File v3Dir)
      throws ConfigurationException {
    File v2MetadataFile = new File(currentDir, V1Constants.MetadataKeys.METADATA_FILE_NAME);
    File v3MetadataFile = new File(v3Dir, V1Constants.MetadataKeys.METADATA_FILE_NAME);

    final PropertiesConfiguration properties = new PropertiesConfiguration(v2MetadataFile);
    // update the segment version
    properties.setProperty(V1Constants.MetadataKeys.Segment.SEGMENT_VERSION, SegmentVersion.v3.toString());
    properties.save(v3MetadataFile);
  }

  private void copyCreationMetadata(File currentDir, File v3Dir)
      throws IOException {
    File v2CreationFile = new File (currentDir, V1Constants.SEGMENT_CREATION_META);
    File v3CreationFile = new File (v3Dir, V1Constants.SEGMENT_CREATION_META);
    Files.copy(v2CreationFile.toPath(), v3CreationFile.toPath());
  }

  private void deleteStaleConversionDirectories(File segmentDirectory) {
    final String prefix = segmentDirectory.getName() + V3_TEMP_DIR_SUFFIX;
    File[] files = segmentDirectory.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.startsWith(prefix);
      }
    });

    for (File file : files) {
      LOGGER.info("Deleting stale v3 directory: {}", file);
      FileUtils.deleteQuietly(file);
    }
  }

  public static void main(String[] args)
      throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: $0 <table directory with segments>");
      System.exit(1);
    }
    File tableDirectory = new File(args[0]);
    Preconditions.checkState(tableDirectory.exists(), "Directory: {} does not exist", tableDirectory);
    Preconditions.checkState(tableDirectory.isDirectory(), "Path: {} is not a directory", tableDirectory);
    File[] files = tableDirectory.listFiles();
    SegmentFormatConverter converter = new SegmentV1V2ToV3FormatConverter();

    for (File file : files) {
      if (! file.isDirectory()) {
        System.out.println("Path: " + file + " is not a directory. Skipping...");
        continue;
      }
      long startTimeNano = System.nanoTime();
      converter.convert(file);
      long endTimeNano = System.nanoTime();
      long latency =  (endTimeNano - startTimeNano) / (1000 * 1000);
      System.out.println("Converting segment: " + file + " took " + latency + " milliseconds");
    }
  }
}
