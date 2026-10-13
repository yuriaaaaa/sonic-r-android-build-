package com.yuriaaaaa.sonicr;

import java.io.File;
import java.io.IOException;

/** Unpacks zip, 7z, rar (including RAR5) and tar archives with libarchive (libmodarchive.so). */
final class ArchiveExtractor {
    interface Listener {
        /** Bytes of the archive read so far; return false to cancel. */
        boolean onProgress(long done, long total);
    }

    private static boolean loaded;

    private static native String nativeExtract(String archive, String destination, Listener listener);

    static synchronized void load() throws IOException {
        if (loaded)
            return;
        try {
            System.loadLibrary("modarchive");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("The unpacker couldn't be loaded: " + e.getMessage());
        }
    }

    static void extract(File archive, File destination, Listener listener) throws IOException {
        load();
        if (!destination.isDirectory() && !destination.mkdirs())
            throw new IOException("Can't create " + destination);
        String error = nativeExtract(archive.getAbsolutePath(), destination.getAbsolutePath(), listener);
        if (error != null)
            throw new IOException(error);
    }
}
