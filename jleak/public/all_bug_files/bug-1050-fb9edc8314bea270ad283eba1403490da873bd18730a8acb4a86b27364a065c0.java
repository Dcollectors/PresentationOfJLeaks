// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.logserver.handlers.archive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;


/**
 * This class holds information about all (log) files contained
 * in the logarchive directory hierarchy.  It also has functionality
 * for compressing log files and deleting older files.
 *
 * @author Arne Juul
 */
public class FilesArchived {
    private static final Logger log = Logger.getLogger(FilesArchived.class.getName());

    /**
     * File instance representing root directory of archive
     */
    private final File root;

    // known-existing files inside the archive directory
    private List<LogFile> knownFiles;

    public final static long compressAfterMillis = 2L * 3600 * 1000;
    private long maxAgeDays = 30; // GDPR rules: max 30 days
    private long sizeLimit = 30L * (1L << 30); // 30 GB

    /**
     * Creates an FilesArchive managing the given directory
     */
    public FilesArchived(File rootDir) {
        this.root = rootDir;
        maintenance();
    }

    public String toString() {
        return FilesArchived.class.getName() + ": root=" + root;
    }

    public int highestGen(String prefix) {
        int gen = 0;
        for (LogFile lf : knownFiles) {
            if (prefix.equals(lf.prefix)) {
                gen = Math.max(gen, lf.generation);
            }
        }
        return gen;
    }

    public synchronized void maintenance() {
        rescan();
        if (removeOlderThan(maxAgeDays)) rescan();
        if (compressOldFiles()) rescan();
        long days = maxAgeDays;
        while (tooMuchDiskUsage() && (--days > 1)) {
            if (removeOlderThan(days)) rescan();
        }
    }

    private void rescan() {
        knownFiles = scanDir(root);
    }

    boolean tooMuchDiskUsage() {
        long sz = sumFileSizes();
        return sz > sizeLimit;
    }

    private boolean olderThan(LogFile lf, long days, long now) {
        long mtime = lf.path.lastModified();
        long diff = now - mtime;
        return (diff > days * 86400L * 1000L);
    }

    // returns true if any files were removed
    private boolean removeOlderThan(long days) {
        boolean action = false;
        long now = System.currentTimeMillis();
        for (LogFile lf : knownFiles) {
            if (olderThan(lf, days, now)) {
                lf.path.delete();
                log.info("Deleted: "+lf.path);
                action = true;
            }
        }
        return action;
    }

    // returns true if any files were compressed
    private boolean compressOldFiles() {
        boolean action = false;
        long now = System.currentTimeMillis();
        int count = 0;
        for (LogFile lf : knownFiles) {
            // avoid compressing entire archive at once
            if (lf.canCompress(now) && (count++ < 5)) {
                compress(lf.path);
            }
        }
        return count > 0;
    }

    private void compress(File oldFile) {
        File gzippedFile = new File(oldFile.getPath() + ".gz");
        try {
            long mtime = oldFile.lastModified();
            GZIPOutputStream compressor = new GZIPOutputStream(new FileOutputStream(gzippedFile), 0x100000);
            FileInputStream inputStream = new FileInputStream(oldFile);
            byte [] buffer = new byte[0x100000];

            for (int read = inputStream.read(buffer); read > 0; read = inputStream.read(buffer)) {
                compressor.write(buffer, 0, read);
            }
            inputStream.close();
            compressor.finish();
            compressor.flush();
            compressor.close();
            oldFile.delete();
            gzippedFile.setLastModified(mtime);
            log.info("Compressed: "+gzippedFile);
        } catch (IOException e) {
            log.warning("Got '" + e + "' while compressing '" + oldFile.getPath() + "'.");
        }
    }

    public long sumFileSizes() {
        long sum = 0;
        for (LogFile lf : knownFiles) {
            sum += lf.path.length();
        }
        return sum;
    }

    private static List<LogFile> scanDir(File top) {
        List<LogFile> retval = new ArrayList<>();
        String[] names = top.list();
        if (names != null) {
            for (String name : names) {
                File sub = new File(top, name);
                if (sub.isFile()) {
                    retval.add(new LogFile(sub));
                } else if (sub.isDirectory()) {
                    for (LogFile subFile : scanDir(sub)) {
                        retval.add(subFile);
                    }
                }
            }
        }
        return retval;
    }

    static class LogFile {
        public final File path; 
        public final String prefix;
        public final int generation;
        public final boolean zsuff;

        public boolean canCompress(long now) {
            if (zsuff) return false; // already compressed
            if (! path.isFile()) return false; // not a file
            long diff = now - path.lastModified();
            if (diff < compressAfterMillis) return false; // too new
            return true;
        }

        private static int generationOf(String name) {
            int dash = name.lastIndexOf('-');
            if (dash < 0) return 0;
            String suff = name.substring(dash + 1);
            int r = 0;
            for (char ch : suff.toCharArray()) {
                if (ch >= '0' && ch <= '9') {
                    r *= 10;
                    r += (ch - '0');
                } else {
                    break;
                }
            }
            return r;
        }
        private static String prefixOf(String name) {
            int dash = name.lastIndexOf('-');
            if (dash < 0) return name;
            return name.substring(0, dash);
        }
        private static boolean zSuffix(String name) {
            if (name.endsWith(".gz")) return true;
            // add other compression suffixes here
            return false;
        }
        public LogFile(File path) {
            String name = path.toString();
            this.path = path;
            this.prefix = prefixOf(name);
            this.generation = generationOf(name);
            this.zsuff = zSuffix(name);
        }
        public String toString() {
            return "FilesArchived.LogFile{name="+path+" prefix="+prefix+" gen="+generation+" z="+zsuff+"}";
        }
    }
}

