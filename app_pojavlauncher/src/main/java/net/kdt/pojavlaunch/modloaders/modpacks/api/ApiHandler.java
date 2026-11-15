package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import net.kdt.pojavlaunch.Tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.SSLException;

@SuppressWarnings("unused")
public class ApiHandler {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 6;

    public final String baseUrl;
    public final Map<String, String> additionalHeaders;

    public static final class HttpResult {
        public final int statusCode;
        @NonNull public final String body;
        @NonNull public final Map<String, String> headers;

        public HttpResult(int statusCode, @Nullable String body, @Nullable Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
            this.headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
        }

        @Nullable
        public Integer getRetryAfterSeconds() {
            String raw = headers.get("retry-after");
            if (raw == null) return null;
            try {
                int parsed = Integer.parseInt(raw.trim());
                return parsed >= 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    public ApiHandler(String url) {
        baseUrl = trimTrailingSlash(url);
        additionalHeaders = null;
    }

    public ApiHandler(String url, String apiKey) {
        baseUrl = trimTrailingSlash(url);
        additionalHeaders = new ArrayMap<>();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            additionalHeaders.put("x-api-key", apiKey);
        }
    }

    public ApiHandler(String url, @Nullable Map<String, String> headers) {
        baseUrl = trimTrailingSlash(url);
        if (headers != null && !headers.isEmpty()) {
            additionalHeaders = new LinkedHashMap<>(headers);
        } else {
            additionalHeaders = null;
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public HttpResult executeGet(String endpoint) throws IOException {
        return executeGet(endpoint, null);
    }

    public HttpResult executeGet(String endpoint, @Nullable Map<String, Object> query) throws IOException {
        String cleanEndpoint = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        String url = baseUrl + "/" + cleanEndpoint;
        if (query != null && !query.isEmpty()) {
            url += parseQueries(query);
        }
        return executeGetFullUrl(additionalHeaders, url);
    }

    public static HttpResult executeGetFullUrl(@Nullable Map<String, String> headers, String initialUrl) throws IOException {
        if (initialUrl == null || initialUrl.trim().isEmpty()) {
            throw new CurseForgeException(CurseForgeException.ErrorCode.NETWORK_ERROR, "Request URL is empty.");
        }
        String currentUrl = initialUrl;
        String originHost = extractHost(initialUrl);
        int redirects = 0;

        while (true) {
            URL targetUrl;
            try {
                targetUrl = new URL(currentUrl);
            } catch (IOException e) {
                throw new CurseForgeException(CurseForgeException.ErrorCode.NETWORK_ERROR, "Invalid request URL.", e);
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) targetUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "STARLauncher/1.0");
                addScopedHeaders(conn, headers, originHost, targetUrl.getHost());

                int code = conn.getResponseCode();
                if (isRedirectStatus(code) && redirects < MAX_REDIRECTS) {
                    String location = conn.getHeaderField("Location");
                    if (location != null && !location.trim().isEmpty()) {
                        currentUrl = new URL(targetUrl, location.trim()).toString();
                        redirects++;
                        continue;
                    }
                }

                InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = "";
                if (stream != null) {
                    try {
                        body = Tools.read(stream);
                    } finally {
                        try {
                            stream.close();
                        } catch (IOException ignored) {}
                    }
                }

                Map<String, String> responseHeaders = new LinkedHashMap<>();
                Map<String, List<String>> rawHeaders = conn.getHeaderFields();
                if (rawHeaders != null) {
                    for (Map.Entry<String, List<String>> entry : rawHeaders.entrySet()) {
                        String key = entry.getKey();
                        List<String> values = entry.getValue();
                        if (key != null && values != null && !values.isEmpty()) {
                            responseHeaders.put(key.toLowerCase(Locale.ROOT), values.get(0));
                        }
                    }
                }
                return new HttpResult(code, body, responseHeaders);
            } catch (UnknownHostException | ConnectException | NoRouteToHostException e) {
                throw new CurseForgeException(
                        CurseForgeException.ErrorCode.NETWORK_ERROR,
                        "[" + CurseForgeException.ErrorCode.NETWORK_ERROR.name() + "] Unable to reach CurseForge (" + e.getClass().getSimpleName() + "). Check your internet connection and retry.",
                        e
                );
            } catch (SocketTimeoutException e) {
                throw new CurseForgeException(
                        CurseForgeException.ErrorCode.NETWORK_ERROR,
                        "[" + CurseForgeException.ErrorCode.NETWORK_ERROR.name() + "] Request to CurseForge timed out. Please retry.",
                        e
                );
            } catch (SSLException e) {
                throw new CurseForgeException(
                        CurseForgeException.ErrorCode.NETWORK_ERROR,
                        "[" + CurseForgeException.ErrorCode.NETWORK_ERROR.name() + "] Secure TLS connection to CurseForge failed (" + e.getClass().getSimpleName() + ").",
                        e
                );
            } catch (IOException e) {
                if (e instanceof CurseForgeException) throw e;
                throw new CurseForgeException(
                        CurseForgeException.ErrorCode.NETWORK_ERROR,
                        "[" + CurseForgeException.ErrorCode.NETWORK_ERROR.name() + "] Network error communicating with CurseForge: " + e.getMessage(),
                        e
                );
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    public static boolean isTrustedCurseForgeHost(@Nullable String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("api.curseforge.com")
                || lower.equals("forgecdn.net")
                || lower.endsWith(".forgecdn.net");
    }

    static void addScopedHeaders(
            @NonNull HttpURLConnection connection,
            @Nullable Map<String, String> headers,
            @Nullable String originHost,
            @Nullable String targetHost
    ) {
        if (headers == null || headers.isEmpty()) return;
        boolean sameHost = originHost != null && targetHost != null && targetHost.equalsIgnoreCase(originHost);
        boolean trustedCfHost = isTrustedCurseForgeHost(targetHost);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            boolean isApiKey = key.equalsIgnoreCase("x-api-key");
            boolean isAuth = key.equalsIgnoreCase("Authorization");
            if ((!isApiKey && !isAuth) || sameHost || (isApiKey && trustedCfHost)) {
                connection.setRequestProperty(key, value);
            }
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
    private static String extractHost(@Nullable String rawUrl) {
        if (rawUrl == null) return null;
        try {
            return new URL(rawUrl).getHost();
        } catch (IOException ignored) {
            return null;
        }
    }

    public <T> T get(String endpoint, Class<T> tClass) {
        return getFullUrl(additionalHeaders, baseUrl + "/" + endpoint, tClass);
    }

    public <T> T get(String endpoint, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(additionalHeaders, baseUrl + "/" + endpoint, query, tClass);
    }

    public <T> T post(String endpoint, T body, Class<T> tClass) {
        return postFullUrl(additionalHeaders, baseUrl + "/" + endpoint, body, tClass);
    }

    public <T> T post(String endpoint, HashMap<String, Object> query, T body, Class<T> tClass) {
        return postFullUrl(additionalHeaders, baseUrl + "/" + endpoint, query, body, tClass);
    }

    //Make a get request and return the response as a raw string;
    public static String getRaw(String url) {
        return getRaw(null, url);
    }

    public static String getRaw(Map<String, String> headers, String url) {
        Log.d("ApiHandler", url);
        try {
            HttpResult result = executeGetFullUrl(headers, url);
            if (result.statusCode >= 200 && result.statusCode < 300) {
                return result.body;
            }
            Log.w("ApiHandler", "HTTP " + result.statusCode + " from " + url);
        } catch (IOException e) {
            Log.w("ApiHandler", "GET failed for " + url + ": " + e.getMessage());
        }
        return null;
    }

    public static String postRaw(String url, String body) {
        return postRaw(null, url, body);
    }

    public static String postRaw(Map<String, String> headers, String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "STARLauncher/1.0");
            addHeaders(conn, headers);
            conn.setDoOutput(true);

            OutputStream outputStream = conn.getOutputStream();
            byte[] input = body.getBytes(StandardCharsets.UTF_8);
            outputStream.write(input, 0, input.length);
            outputStream.close();

            InputStream inputStream = conn.getInputStream();
            String data = Tools.read(inputStream);
            inputStream.close();

            conn.disconnect();
            return data;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void addHeaders(HttpURLConnection connection, Map<String, String> headers) {
        if(headers != null) {
            for(String key : headers.keySet())
                connection.addRequestProperty(key, headers.get(key));
        }
    }

    private static String parseQueries(Map<String, Object> query) {
        StringBuilder params = new StringBuilder("?");
        for (String param : query.keySet()) {
            String value = Objects.toString(query.get(param));
            params.append(urlEncodeUTF8(param))
                    .append("=")
                    .append(urlEncodeUTF8(value))
                    .append("&");
        }
        return params.substring(0, params.length() - 1);
    }

    public static <T> T getFullUrl(String url, Class<T> tClass) {
        return getFullUrl(null, url, tClass);
    }

    public static <T> T getFullUrl(String url, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(null, url, query, tClass);
    }

    public static <T> T postFullUrl(String url, T body, Class<T> tClass) {
        return postFullUrl(null, url, body, tClass);
    }

    public static <T> T postFullUrl(String url, HashMap<String, Object> query, T body, Class<T> tClass) {
        return postFullUrl(null, url, query, body, tClass);
    }

    public static <T> T getFullUrl(Map<String, String> headers, String url, Class<T> tClass) {
        return new Gson().fromJson(getRaw(headers, url), tClass);
    }

    public static <T> T getFullUrl(Map<String, String> headers, String url, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(headers, url + parseQueries(query), tClass);
    }

    public static <T> T postFullUrl(Map<String, String> headers, String url, T body, Class<T> tClass) {
        return new Gson().fromJson(postRaw(headers, url, body.toString()), tClass);
    }

    public static <T> T postFullUrl(Map<String, String> headers, String url, HashMap<String, Object> query, T body, Class<T> tClass) {
        return new Gson().fromJson(postRaw(headers, url + parseQueries(query), body.toString()), tClass);
    }

    private static String urlEncodeUTF8(String input) {
        try {
            return URLEncoder.encode(input, "UTF-8");
        }catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 is required");
        }
    }
}
