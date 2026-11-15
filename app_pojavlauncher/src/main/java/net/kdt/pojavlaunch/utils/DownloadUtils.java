package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLException;

import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CurseForgeException;
import org.apache.commons.io.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    private static final int TIME_OUT = 10000;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            // System.out.println("Connecting: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        } catch (IOException e) {
            if (out.length() < 1) { // Only delete it if file is 0 bytes cause this file might already be downloaded and something else went wrong.
                Log.i("DownloadUtils", "Cleaning up failed download: " + out.getAbsolutePath());
                out.delete();
                throw e;
            }
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        downloadFileMonitored(urlInput, outputFile, buffer, monitor, null, -1L);
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor,
                                             @Nullable Map<String, String> headers,
                                             long expectedSize) throws IOException {
        if (urlInput == null || urlInput.trim().isEmpty()) {
            throw new CurseForgeException(CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING,
                    "Download URL is missing or empty for " + outputFile.getName());
        }
        FileUtils.ensureParentDirectory(outputFile);
        File partFile = new File(outputFile.getParentFile(), outputFile.getName() + ".part");
        String currentUrl = urlInput;
        String originHost = extractHost(urlInput);
        int redirects = 0;

        while (true) {
            URL targetUrl;
            try {
                targetUrl = new URL(currentUrl);
            } catch (MalformedURLException e) {
                throw new CurseForgeException(CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING,
                        "Invalid download URL for " + outputFile.getName(), e);
            }

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) targetUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "STARLauncher/1.0");
                addDownloadHeaders(conn, headers, originHost, targetUrl.getHost());

                int code = conn.getResponseCode();
                if (isRedirectStatus(code) && redirects < 6) {
                    String location = conn.getHeaderField("Location");
                    if (location != null && !location.trim().isEmpty()) {
                        currentUrl = new URL(targetUrl, location.trim()).toString();
                        redirects++;
                        continue;
                    }
                }
                if (code < 200 || code >= 300) {
                    String snippet = readErrorSnippet(conn.getErrorStream(), headers);
                    Integer retryAfter = parseRetryAfter(conn.getHeaderField("Retry-After"));
                    throw mapDownloadHttpError(code, currentUrl, outputFile.getName(), retryAfter, snippet);
                }

                long contentLength = conn.getContentLengthLong();
                int progressMax = contentLength > 0 ? (int) Math.min(contentLength, Integer.MAX_VALUE)
                        : (expectedSize > 0 ? (int) Math.min(expectedSize, Integer.MAX_VALUE) : -1);

                long overall = 0L;
                try (InputStream readStr = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(partFile)) {
                    byte[] localBuf = buffer != null ? buffer : new byte[32768];
                    int current;
                    while ((current = readStr.read(localBuf)) != -1) {
                        overall += current;
                        fos.write(localBuf, 0, current);
                        if (monitor != null) {
                            monitor.updateProgress((int) Math.min(overall, Integer.MAX_VALUE), progressMax);
                        }
                    }
                    fos.flush();
                }

                if (overall <= 0 && expectedSize != 0) {
                    throw new IOException("Downloaded file is empty (0 bytes): " + outputFile.getName());
                }
                if (contentLength > 0 && overall != contentLength) {
                    throw new IOException("Incomplete download for " + outputFile.getName()
                            + ": expected " + contentLength + " bytes, got " + overall + " bytes");
                }
                if (expectedSize > 0 && overall != expectedSize) {
                    throw new IOException("File size mismatch for " + outputFile.getName()
                            + ": expected " + expectedSize + " bytes, got " + overall + " bytes");
                }

                if (outputFile.exists() && !outputFile.delete()) {
                    throw new IOException("Unable to replace existing file: " + outputFile.getAbsolutePath());
                }
                if (!partFile.renameTo(outputFile)) {
                    try (FileInputStream in = new FileInputStream(partFile);
                         FileOutputStream out = new FileOutputStream(outputFile)) {
                        IOUtils.copy(in, out);
                    }
                    partFile.delete();
                }
                return;
            } catch (UnknownHostException | ConnectException | NoRouteToHostException | SocketTimeoutException | SSLException e) {
                partFile.delete();
                throw new CurseForgeException(
                        CurseForgeException.ErrorCode.NETWORK_ERROR,
                        "[" + CurseForgeException.ErrorCode.NETWORK_ERROR.name() + "] Network error downloading "
                                + outputFile.getName() + ": " + e.getMessage(),
                        e
                );
            } catch (InterruptedIOException e) {
                partFile.delete();
                throw e;
            } catch (IOException e) {
                partFile.delete();
                throw e;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    private static void addDownloadHeaders(
            HttpURLConnection conn,
            @Nullable Map<String, String> headers,
            @Nullable String originHost,
            @Nullable String targetHost
    ) {
        if (headers == null || headers.isEmpty()) return;
        boolean sameHost = originHost != null && targetHost != null && targetHost.equalsIgnoreCase(originHost);
        boolean trustedCfHost = ApiHandler.isTrustedCurseForgeHost(targetHost);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            boolean isApiKey = key.equalsIgnoreCase("x-api-key");
            boolean isAuth = key.equalsIgnoreCase("Authorization");
            if ((!isApiKey && !isAuth) || sameHost || (isApiKey && trustedCfHost)) {
                conn.setRequestProperty(key, value);
            }
        }
    }

    @Nullable
    private static String extractHost(@Nullable String rawUrl) {
        try {
            return new URL(rawUrl).getHost();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isRedirectStatus(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    @Nullable
    private static Integer parseRetryAfter(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            int val = Integer.parseInt(raw.trim());
            return val >= 0 ? val : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static String readErrorSnippet(@Nullable InputStream errorStream, @Nullable Map<String, String> headers) {
        if (errorStream == null) return null;
        try (InputStream is = errorStream) {
            String raw = Tools.read(is);
            if (raw == null) return null;
            String cleaned = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) return null;
            String[] secrets = headers != null ? headers.values().toArray(new String[0]) : null;
            String redacted = CurseForgeException.redactSecrets(cleaned, secrets);
            return redacted.length() > 160 ? redacted.substring(0, 157) + "..." : redacted;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static CurseForgeException mapDownloadHttpError(
            int code,
            String url,
            String fileName,
            @Nullable Integer retryAfter,
            @Nullable String snippet
    ) {
        CurseForgeException.ErrorCode errorCode;
        switch (code) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                errorCode = CurseForgeException.ErrorCode.API_KEY_INVALID;
                break;
            case HttpURLConnection.HTTP_FORBIDDEN: {
                String lower = snippet != null ? snippet.toLowerCase(Locale.ROOT) : "";
                boolean looksLikeAuth = lower.contains("api key") || lower.contains("invalid key") || lower.contains("missing key");
                if (!looksLikeAuth && ApiHandler.isTrustedCurseForgeHost(extractHost(url))) {
                    errorCode = CurseForgeException.ErrorCode.DISTRIBUTION_NOT_ALLOWED;
                } else {
                    errorCode = CurseForgeException.ErrorCode.API_KEY_INVALID;
                }
                break;
            }
            case HttpURLConnection.HTTP_NOT_FOUND:
                errorCode = CurseForgeException.ErrorCode.FILE_NOT_FOUND;
                break;
            case 429:
                errorCode = CurseForgeException.ErrorCode.API_RATE_LIMITED;
                break;
            default:
                errorCode = CurseForgeException.ErrorCode.API_SERVER_ERROR;
                break;
        }
        return CurseForgeException.forFileError(
                errorCode,
                0L,
                0L,
                code,
                null,
                fileName,
                null,
                retryAfter,
                snippet,
                null
        );
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.isFile() && file.length() > 0 && Tools.compareSHA1(file, sha1);
    }

    public static boolean compareMD5(File file, @Nullable String expectedMd5) {
        if (expectedMd5 == null || expectedMd5.trim().isEmpty()) return true;
        if (!file.isFile() || file.length() <= 0) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[16384];
            try (InputStream is = new FileInputStream(file)) {
                int read;
                while ((read = is.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            return hex.toString().equalsIgnoreCase(expectedMd5.trim());
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and is non-empty and we don't know its SHA1, don't try to redownload it.
            if(outputFile.isFile() && outputFile.length() > 0) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

