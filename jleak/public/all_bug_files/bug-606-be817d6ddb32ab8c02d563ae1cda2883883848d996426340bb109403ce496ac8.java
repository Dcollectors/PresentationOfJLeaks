/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.update.task;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.apache.tools.ant.util.FileUtils;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.data.update.NvdCveInfo;
import org.owasp.dependencycheck.data.update.exception.UpdateException;
import org.owasp.dependencycheck.utils.DownloadFailedException;
import org.owasp.dependencycheck.utils.Downloader;
import org.owasp.dependencycheck.utils.Settings;

/**
 * A callable object to download two files.
 *
 * @author Jeremy Long <jeremy.long@owasp.org>
 */
public class DownloadTask implements Callable<Future<ProcessTask>> {

    /**
     * The Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(DownloadTask.class.getName());

    /**
     * Simple constructor for the callable download task.
     *
     * @param nvdCveInfo the NVD CVE info
     * @param processor the processor service to submit the downloaded files to
     * @param cveDB the CVE DB to use to store the vulnerability data
     * @param settings a reference to the global settings object; this is necessary so that when the thread is started
     * the dependencies have a correct reference to the global settings.
     * @throws UpdateException thrown if temporary files could not be created
     */
    public DownloadTask(NvdCveInfo nvdCveInfo, ExecutorService processor, CveDB cveDB, Settings settings) throws UpdateException {
        this.nvdCveInfo = nvdCveInfo;
        this.processorService = processor;
        this.cveDB = cveDB;
        this.settings = settings;

        final File file1;
        final File file2;

        try {
            file1 = File.createTempFile("cve" + nvdCveInfo.getId() + "_", ".xml", Settings.getTempDirectory());
            file2 = File.createTempFile("cve_1_2_" + nvdCveInfo.getId() + "_", ".xml", Settings.getTempDirectory());
        } catch (IOException ex) {
            throw new UpdateException("Unable to create temporary files", ex);
        }
        this.first = file1;
        this.second = file2;

    }
    /**
     * The CVE DB to use when processing the files.
     */
    private CveDB cveDB;
    /**
     * The processor service to pass the results of the download to.
     */
    private ExecutorService processorService;
    /**
     * The NVD CVE Meta Data.
     */
    private NvdCveInfo nvdCveInfo;
    /**
     * A reference to the global settings object.
     */
    private Settings settings;

    /**
     * Get the value of nvdCveInfo.
     *
     * @return the value of nvdCveInfo
     */
    public NvdCveInfo getNvdCveInfo() {
        return nvdCveInfo;
    }

    /**
     * Set the value of nvdCveInfo.
     *
     * @param nvdCveInfo new value of nvdCveInfo
     */
    public void setNvdCveInfo(NvdCveInfo nvdCveInfo) {
        this.nvdCveInfo = nvdCveInfo;
    }
    /**
     * a file.
     */
    private File first;

    /**
     * Get the value of first.
     *
     * @return the value of first
     */
    public File getFirst() {
        return first;
    }

    /**
     * Set the value of first.
     *
     * @param first new value of first
     */
    public void setFirst(File first) {
        this.first = first;
    }
    /**
     * a file.
     */
    private File second;

    /**
     * Get the value of second.
     *
     * @return the value of second
     */
    public File getSecond() {
        return second;
    }

    /**
     * Set the value of second.
     *
     * @param second new value of second
     */
    public void setSecond(File second) {
        this.second = second;
    }
    /**
     * A placeholder for an exception.
     */
    private Exception exception = null;

    /**
     * Get the value of exception.
     *
     * @return the value of exception
     */
    public Exception getException() {
        return exception;
    }

    /**
     * returns whether or not an exception occurred during download.
     *
     * @return whether or not an exception occurred during download
     */
    public boolean hasException() {
        return exception != null;
    }

    @Override
    public Future<ProcessTask> call() throws Exception {
        try {
            Settings.setInstance(settings);
            final URL url1 = new URL(nvdCveInfo.getUrl());
            final URL url2 = new URL(nvdCveInfo.getOldSchemaVersionUrl());
            String msg = String.format("Download Started for NVD CVE - %s", nvdCveInfo.getId());
            LOGGER.log(Level.INFO, msg);
            try {
                Downloader.fetchFile(url1, first);
                Downloader.fetchFile(url2, second);
            } catch (DownloadFailedException ex) {
                msg = String.format("Download Failed for NVD CVE - %s%nSome CVEs may not be reported.", nvdCveInfo.getId());
                LOGGER.log(Level.WARNING, msg);
                if (Settings.getString(Settings.KEYS.PROXY_SERVER) == null) {
                    LOGGER.log(Level.INFO,
                            "If you are behind a proxy you may need to configure dependency-check to use the proxy.");
                }
                LOGGER.log(Level.FINE, null, ex);
                return null;
            }
            if (url1.toExternalForm().endsWith(".xml.gz")) {
                extractGzip(first);
            }
            if (url2.toExternalForm().endsWith(".xml.gz")) {
                extractGzip(second);
            }

            msg = String.format("Download Complete for NVD CVE - %s", nvdCveInfo.getId());
            LOGGER.log(Level.INFO, msg);
            if (this.processorService == null) {
                return null;
            }
            final ProcessTask task = new ProcessTask(cveDB, this, settings);
            return this.processorService.submit(task);

        } catch (Throwable ex) {
            final String msg = String.format("An exception occurred downloading NVD CVE - %s%nSome CVEs may not be reported.", nvdCveInfo.getId());
            LOGGER.log(Level.WARNING, msg);
            LOGGER.log(Level.FINE, "Download Task Failed", ex);
        } finally {
            Settings.cleanup(false);
        }
        return null;
    }

    /**
     * Attempts to delete the files that were downloaded.
     */
    public void cleanup() {
        boolean deleted = false;
        try {
            if (first != null && first.exists()) {
                deleted = first.delete();
            }
        } finally {
            if (first != null && (first.exists() || !deleted)) {
                first.deleteOnExit();
            }
        }
        try {
            deleted = false;
            if (second != null && second.exists()) {
                deleted = second.delete();
            }
        } finally {
            if (second != null && (second.exists() || !deleted)) {
                second.deleteOnExit();
            }
        }
    }

    /**
     * Extracts the file contained in a gzip archive. The extracted file is placed in the exact same path as the file
     * specified.
     *
     * @param file the archive file
     * @throws FileNotFoundException thrown if the file does not exist
     * @throws IOException thrown if there is an error extracting the file.
     */
    private void extractGzip(File file) throws FileNotFoundException, IOException {
        String originalPath = file.getPath();
        File gzip = new File(originalPath + ".gz");
        if (gzip.isFile()) {
            gzip.delete();
        }
        file.renameTo(gzip);
        file = new File(originalPath);

        byte[] buffer = new byte[4096];

        GZIPInputStream cin = new GZIPInputStream(new FileInputStream(gzip));

        FileOutputStream out = new FileOutputStream(file);

        int len;
        while ((len = cin.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        cin.close();
        out.close();
        FileUtils.delete(gzip);
    }
}
