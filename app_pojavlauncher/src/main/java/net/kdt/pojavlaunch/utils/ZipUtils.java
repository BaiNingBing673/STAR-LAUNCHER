package net.kdt.pojavlaunch.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipUtils {
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    public interface ZipProgressCallback {
        void onProgress(long extractedBytes, long totalBytes, String currentEntry);
    }

    public interface CancellationCheck {
        boolean isCancelled();
    }

    /**
     * Gets an InputStream for a given ZIP entry, throwing an IOException if the ZIP entry does not
     * exist.
     * @param zipFile The ZipFile to get the entry from
     * @param entryPath The full path inside of the ZipFile
     * @return The InputStream provided by the ZipFile
     * @throws IOException if the entry was not found
     */
    public static InputStream getEntryStream(ZipFile zipFile, String entryPath) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryPath);
        if (entry == null) throw new IOException("No entry in ZIP file: " + entryPath);
        return zipFile.getInputStream(entry);
    }

    /**
     * Validates that a ZIP entry name does not attempt path traversal (ZIP Slip).
     */
    public static boolean isSafeEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\0') >= 0) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            return false;
        }
        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            return false;
        }
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if ("..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the total declared uncompressed bytes for entries under {@code dirName}.
     */
    public static long calculateUncompressedBytes(ZipFile zipFile, String dirName) throws IOException {
        long total = 0L;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = entry.getName();
            if (!isSafeEntryName(entryName)) {
                throw new IOException("Blocked unsafe ZIP entry path: " + entryName);
            }
            if (!entryName.startsWith(dirName) || entry.isDirectory()) continue;
            long size = entry.getSize();
            if (size > 0L) {
                total += size;
            }
        }
        return total;
    }

    /**
     * Extracts all files in a ZipFile inside of a given directory to a given destination directory
     * using bounded streaming buffers and ZIP Slip protection.
     */
    public static void zipExtract(ZipFile zipFile, String dirName, File destination) throws IOException {
        zipExtract(zipFile, dirName, destination, null, null);
    }

    /**
     * Streaming, cancellable, progress-reporting ZIP extraction with ZIP Slip validation.
     */
    public static void zipExtract(
            ZipFile zipFile,
            String dirName,
            File destination,
            ZipProgressCallback progressCallback,
            CancellationCheck cancellationCheck
    ) throws IOException {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Failed to create destination directory: " + destination.getAbsolutePath());
        }
        String canonicalDest = destination.getCanonicalPath();
        String prefix = canonicalDest.endsWith(File.separator) ? canonicalDest : canonicalDest + File.separator;

        long totalBytes = progressCallback != null ? calculateUncompressedBytes(zipFile, dirName) : 0L;
        long extractedBytes = 0L;
        long lastReportedBytes = -1L;

        int dirNameLen = dirName.length();
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();

        while (zipEntries.hasMoreElements()) {
            if (Thread.currentThread().isInterrupted() || (cancellationCheck != null && cancellationCheck.isCancelled())) {
                throw new InterruptedIOException("Archive extraction cancelled");
            }
            ZipEntry zipEntry = zipEntries.nextElement();
            String entryName = zipEntry.getName();
            if (!isSafeEntryName(entryName)) {
                throw new IOException("Blocked unsafe ZIP entry path: " + entryName);
            }
            if (!entryName.startsWith(dirName) || zipEntry.isDirectory()) continue;

            String relativePath = entryName.substring(dirNameLen);
            if (relativePath.isEmpty()) continue;

            File zipDestination = new File(destination, relativePath);
            String canonicalEntry = zipDestination.getCanonicalPath();
            if (!canonicalEntry.startsWith(prefix)) {
                throw new IOException("Blocked unsafe ZIP entry path: " + entryName);
            }
            FileUtils.ensureParentDirectory(zipDestination);

            try (InputStream inputStream = zipFile.getInputStream(zipEntry);
                 OutputStream outputStream = new FileOutputStream(zipDestination)) {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || (cancellationCheck != null && cancellationCheck.isCancelled())) {
                        throw new InterruptedIOException("Archive extraction cancelled");
                    }
                    outputStream.write(buffer, 0, read);
                    extractedBytes += read;
                    if (progressCallback != null && (extractedBytes - lastReportedBytes >= 262_144L || lastReportedBytes < 0L)) {
                        lastReportedBytes = extractedBytes;
                        progressCallback.onProgress(extractedBytes, totalBytes, relativePath);
                    }
                }
            }
        }
        if (progressCallback != null) {
            progressCallback.onProgress(extractedBytes, Math.max(totalBytes, extractedBytes), "");
        }
    }
}
