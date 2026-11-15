package net.kdt.pojavlaunch.modloaders.modpacks.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34})
public class CurseforgeApiModpackTest {

    private static final String TEST_API_KEY = "test-curseforge-key-987654321";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MiniHttpServer server;
    private String baseUrl;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private final List<String> cdnAuthHeaders = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> pathHitCounts = new ConcurrentHashMap<>();

    @Before
    public void setUp() throws Exception {
        server = new MiniHttpServer();
        int port = server.getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void publiclyDistributableMod_resolvesOfficialUrl_followsRedirect_andVerifiesSha1() throws Exception {
        final byte[] modBytes = "PK-fake-jar-content-for-public-mod".getBytes(StandardCharsets.UTF_8);
        final String sha1 = sha1Hex(modBytes);

        server.createContext("/v1/mods/100101/files/200101", exchange -> {
            record(exchange);
            if (!TEST_API_KEY.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
                sendJson(exchange, 403, "{\"error\":\"Invalid API key\"}");
                return;
            }
            if (exchange.getRequestURI().getPath().endsWith("/download-url")) {
                sendJson(exchange, 200, "{\"data\":\"" + baseUrl + "/cdn/redirect/example-mod-1.0.jar\"}");
                return;
            }
            sendJson(exchange, 200, "{"
                    + "\"data\":{"
                    + "\"id\":200101,"
                    + "\"modId\":100101,"
                    + "\"displayName\":\"Example Mod 1.0\","
                    + "\"fileName\":\"example-mod-1.0.jar\","
                    + "\"isAvailable\":true,"
                    + "\"isServerPack\":false,"
                    + "\"fileLength\":" + modBytes.length + ","
                    + "\"downloadUrl\":null,"
                    + "\"hashes\":[{\"value\":\"" + sha1 + "\",\"algo\":1}],"
                    + "\"dependencies\":[]"
                    + "}"
                    + "}");
        });

        server.createContext("/cdn/redirect/example-mod-1.0.jar", exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Location", baseUrl + "/cdn/files/1001/200101/example-mod-1.0.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.createContext("/cdn/files/1001/200101/example-mod-1.0.jar", exchange -> {
            record(exchange);
            String key = exchange.getRequestHeaders().getFirst("x-api-key");
            if (key != null) cdnAuthHeaders.add(key);
            if (!TEST_API_KEY.equals(key)) {
                sendJson(exchange, 401, "Unauthorized");
                return;
            }
            sendBytes(exchange, 200, modBytes);
        });

        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);
        CurseforgeApi.ResolvedFileDownload resolved = api.resolveFileDownload(100101L, 200101L);

        assertEquals(baseUrl + "/cdn/redirect/example-mod-1.0.jar", resolved.url);
        assertEquals("example-mod-1.0.jar", resolved.fileName);
        assertEquals(sha1, resolved.sha1);
        assertEquals(modBytes.length, resolved.fileLength);

        File dest = new File(tempFolder.getRoot(), resolved.fileName);
        net.kdt.pojavlaunch.utils.DownloadUtils.downloadFileMonitored(
                resolved.url,
                dest,
                new byte[4096],
                null,
                resolved.downloadHeaders,
                resolved.fileLength
        );

        assertTrue(dest.isFile());
        assertArrayEquals(modBytes, java.nio.file.Files.readAllBytes(dest.toPath()));
        assertTrue(cdnAuthHeaders.contains(TEST_API_KEY));
    }

    @Test
    public void distributionDisabledFile_throwsDistributionNotAllowed_andNeverConstructsCdnUrl() throws Exception {
        server.createContext("/v1/mods/100202", exchange -> {
            record(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/download-url")) {
                sendJson(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            if (path.endsWith("/files/200202")) {
                sendJson(exchange, 200, "{"
                        + "\"data\":{"
                        + "\"id\":200202,"
                        + "\"modId\":100202,"
                        + "\"displayName\":\"Restricted Mod\","
                        + "\"fileName\":\"restricted-mod-1.0.jar\","
                        + "\"isAvailable\":true,"
                        + "\"isServerPack\":false,"
                        + "\"fileLength\":4096,"
                        + "\"downloadUrl\":null,"
                        + "\"hashes\":[]"
                        + "}"
                        + "}");
                return;
            }
            if (path.endsWith("/files")) {
                sendJson(exchange, 200, "{"
                        + "\"data\":[{"
                        + "\"id\":200202,"
                        + "\"modId\":100202,"
                        + "\"displayName\":\"Restricted Mod\","
                        + "\"fileName\":\"restricted-mod-1.0.jar\","
                        + "\"isAvailable\":true,"
                        + "\"isServerPack\":false,"
                        + "\"downloadUrl\":null,"
                        + "\"gameVersions\":[\"1.20.1\"],"
                        + "\"hashes\":[],"
                        + "\"dependencies\":[]"
                        + "}],"
                        + "\"pagination\":{\"index\":0,\"pageSize\":50,\"resultCount\":1,\"totalCount\":1}"
                        + "}");
                return;
            }
            sendJson(exchange, 200, "{"
                    + "\"data\":{"
                    + "\"id\":100202,"
                    + "\"name\":\"Restricted Mod\","
                    + "\"summary\":\"Author blocked API distribution\","
                    + "\"allowModDistribution\":false,"
                    + "\"links\":{\"websiteUrl\":\"https://www.curseforge.com/minecraft/mc-mods/restricted-mod\"}"
                    + "}"
                    + "}");
        });

        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);

        // Verify getModDetails does NOT fabricate an edge.forgecdn.net URL
        ModItem item = new ModItem(Constants.SOURCE_CURSEFORGE, false, "100202", "Restricted Mod", "desc", null);
        ModDetail detail = api.getModDetails(item);
        assertNotNull(detail);
        assertEquals(1, detail.versionUrls.length);
        assertNull(detail.versionUrls[0]);

        try {
            api.resolveFileDownload(100202L, 200202L);
            fail("Expected CurseForgeException for DISTRIBUTION_NOT_ALLOWED");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.DISTRIBUTION_NOT_ALLOWED, e.getErrorCode());
            assertFalse(e.isRetryable());
            assertEquals("Restricted Mod", e.getModName());
            assertEquals("https://www.curseforge.com/minecraft/mc-mods/restricted-mod", e.getWebsiteUrl());
            assertTrue(e.getMessage().contains("disabled third-party distribution"));
            assertTrue(e.getMessage().contains("https://www.curseforge.com/minecraft/mc-mods/restricted-mod"));
        }

        for (String path : requestedPaths) {
            assertFalse("Must never construct guessed forgecdn URLs", path.contains("forgecdn.net"));
        }
    }

    @Test
    public void invalidApiKey_rejectedBothClientSideAndFromServer401() throws Exception {
        // Client-side rejection of placeholder "DUMMY" without hitting network
        CurseforgeApi dummyApi = new CurseforgeApi(baseUrl + "/v1", "DUMMY");
        try {
            dummyApi.resolveFileDownload(100303L, 200303L);
            fail("Expected API_KEY_INVALID for DUMMY key");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.API_KEY_INVALID, e.getErrorCode());
            assertFalse(e.isRetryable());
            assertTrue(requestedPaths.isEmpty());
        }

        // Server 401 rejection with key redaction
        final String badKey = "bad-secret-api-key-123456";
        server.createContext("/v1/mods/100303/files/200303", exchange -> {
            record(exchange);
            sendJson(exchange, 401, "{\"error\":\"Invalid key " + badKey + "\"}");
        });

        CurseforgeApi badApi = new CurseforgeApi(baseUrl + "/v1", badKey);
        try {
            badApi.resolveFileDownload(100303L, 200303L);
            fail("Expected API_KEY_INVALID on HTTP 401");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.API_KEY_INVALID, e.getErrorCode());
            assertEquals(401, e.getHttpStatus());
            assertFalse("API key secret must be redacted", e.getMessage().contains(badKey));
        }
    }

    @Test
    public void missingFileAndUnavailableFile_returnDistinctErrors() throws Exception {
        server.createContext("/v1/mods/100404/files/200404", exchange -> {
            record(exchange);
            sendJson(exchange, 404, "{\"error\":\"Not Found\"}");
        });
        server.createContext("/v1/mods/100404/files/200405", exchange -> {
            record(exchange);
            sendJson(exchange, 200, "{"
                    + "\"data\":{"
                    + "\"id\":200405,"
                    + "\"modId\":100404,"
                    + "\"fileName\":\"archived-mod.jar\","
                    + "\"isAvailable\":false,"
                    + "\"downloadUrl\":null"
                    + "}"
                    + "}");
        });

        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);
        try {
            api.resolveFileDownload(100404L, 200404L);
            fail("Expected FILE_NOT_FOUND");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.FILE_NOT_FOUND, e.getErrorCode());
            assertFalse(e.isRetryable());
        }

        try {
            api.resolveFileDownload(100404L, 200405L);
            fail("Expected FILE_UNAVAILABLE");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.FILE_UNAVAILABLE, e.getErrorCode());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void rateLimitedAndServerAndNetworkErrors_areClassifiedCorrectly() throws Exception {
        server.createContext("/v1/mods/100505/files/200505", exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Retry-After", "12");
            sendJson(exchange, 429, "{\"message\":\"Too many requests\"}");
        });
        server.createContext("/v1/mods/100505/files/200506", exchange -> {
            record(exchange);
            sendJson(exchange, 503, "Service Unavailable");
        });
        server.createContext("/v1/mods/100505/files/200507", exchange -> {
            record(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/download-url")) {
                sendJson(exchange, 200, "{\"data\":null}");
                return;
            }
            sendJson(exchange, 200, "{"
                    + "\"data\":{"
                    + "\"id\":200507,"
                    + "\"modId\":100505,"
                    + "\"fileName\":\"no-url-mod.jar\","
                    + "\"isAvailable\":true,"
                    + "\"downloadUrl\":null"
                    + "}"
                    + "}");
        });
        server.createContext("/v1/mods/100505", exchange -> {
            record(exchange);
            sendJson(exchange, 200, "{\"data\":{\"id\":100505,\"name\":\"NoUrlMod\",\"allowModDistribution\":true}}");
        });

        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);
        try {
            api.resolveFileDownload(100505L, 200505L);
            fail("Expected API_RATE_LIMITED");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.API_RATE_LIMITED, e.getErrorCode());
            assertTrue(e.isRetryable());
            assertEquals(Integer.valueOf(12), e.getRetryAfterSeconds());
        }

        try {
            api.resolveFileDownload(100505L, 200506L);
            fail("Expected API_SERVER_ERROR");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.API_SERVER_ERROR, e.getErrorCode());
            assertTrue(e.isRetryable());
        }

        try {
            api.resolveFileDownload(100505L, 200507L);
            fail("Expected DOWNLOAD_URL_MISSING");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING, e.getErrorCode());
            assertFalse(e.isRetryable());
        }

        // Network error on closed local port
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }
        CurseforgeApi offlineApi = new CurseforgeApi("http://127.0.0.1:" + closedPort + "/v1", TEST_API_KEY);
        try {
            offlineApi.resolveFileDownload(100505L, 200505L);
            fail("Expected NETWORK_ERROR");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.NETWORK_ERROR, e.getErrorCode());
            assertTrue(e.isRetryable());
        }
    }

    @Test
    public void normalModpackInstallation_parsesManifest_downloadsMods_extractsOverrides_andHandlesOptionalFailures() throws Exception {
        byte[] mod1Bytes = "mod-one-jar-payload".getBytes(StandardCharsets.UTF_8);
        byte[] mod2Bytes = "mod-two-jar-payload".getBytes(StandardCharsets.UTF_8);
        String mod1Sha1 = sha1Hex(mod1Bytes);
        String mod2Sha1 = sha1Hex(mod2Bytes);

        registerMockFile(100601L, 200601L, "core-lib.jar", mod1Bytes, mod1Sha1);
        registerMockFile(100602L, 200602L, "feature-mod.jar", mod2Bytes, mod2Sha1);
        // Optional mod 100603/200603 returns 404 -> must not fail modpack installation
        server.createContext("/v1/mods/100603/files/200603", exchange -> {
            record(exchange);
            sendJson(exchange, 404, "{\"error\":\"Missing optional file\"}");
        });

        String manifestJson = "{"
                + "\"manifestType\":\"minecraftModpack\","
                + "\"manifestVersion\":1,"
                + "\"name\":\"StarTestPack\","
                + "\"version\":\"1.0.0\","
                + "\"overrides\":\"overrides\","
                + "\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"forge-47.2.0\",\"primary\":true}]},"
                + "\"files\":["
                + "{\"projectID\":100601,\"fileID\":200601,\"required\":true},"
                + "{\"projectID\":100602,\"fileID\":200602,\"required\":true},"
                + "{\"projectID\":100603,\"fileID\":200603,\"required\":false}"
                + "]"
                + "}";

        File zipFile = createModpackZip("normal-pack.zip", manifestJson, "overrides/config/pack.toml", "difficulty=normal");
        File instanceDir = new File(tempFolder.getRoot(), "instance-normal");

        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);
        ModLoader loader = api.installCurseforgeZip(zipFile, instanceDir);

        assertNotNull(loader);
        assertEquals(ModLoader.MOD_LOADER_FORGE, loader.modLoaderType);
        assertEquals("47.2.0", loader.modLoaderVersion);
        assertEquals("1.20.1", loader.minecraftVersion);

        assertArrayEquals(mod1Bytes, java.nio.file.Files.readAllBytes(new File(instanceDir, "mods/core-lib.jar").toPath()));
        assertArrayEquals(mod2Bytes, java.nio.file.Files.readAllBytes(new File(instanceDir, "mods/feature-mod.jar").toPath()));
        File overrideConfig = new File(instanceDir, "config/pack.toml");
        assertTrue(overrideConfig.isFile());
        assertEquals("difficulty=normal", new String(java.nio.file.Files.readAllBytes(overrideConfig.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void partialModpackFailure_preservesSucceededFilesAndOverrides_andSafeRetrySkipsCompletedMods() throws Exception {
        byte[] mod1Bytes = "mod-alpha-jar".getBytes(StandardCharsets.UTF_8);
        byte[] mod2Bytes = "mod-beta-jar".getBytes(StandardCharsets.UTF_8);
        String mod1Sha1 = sha1Hex(mod1Bytes);
        String mod2Sha1 = sha1Hex(mod2Bytes);

        registerMockFile(100701L, 200701L, "alpha.jar", mod1Bytes, mod1Sha1);

        // Mod 100702/200702 fails on initial pass (403 distribution restricted), then succeeds after toggle
        final AtomicInteger betaMode = new AtomicInteger(0); // 0 = restricted, 1 = available
        server.createContext("/v1/mods/100702", exchange -> {
            record(exchange);
            String path = exchange.getRequestURI().getPath();
            if (betaMode.get() == 0) {
                if (path.endsWith("/download-url")) {
                    sendJson(exchange, 403, "{}");
                    return;
                }
                if (path.endsWith("/files/200702")) {
                    sendJson(exchange, 200, "{"
                            + "\"data\":{"
                            + "\"id\":200702,"
                            + "\"modId\":100702,"
                            + "\"fileName\":\"beta.jar\","
                            + "\"isAvailable\":true,"
                            + "\"fileLength\":" + mod2Bytes.length + ","
                            + "\"downloadUrl\":null,"
                            + "\"hashes\":[{\"value\":\"" + mod2Sha1 + "\",\"algo\":1}]"
                            + "}"
                            + "}");
                    return;
                }
                sendJson(exchange, 200, "{"
                        + "\"data\":{"
                        + "\"id\":100702,"
                        + "\"name\":\"BetaMod\","
                        + "\"allowModDistribution\":false,"
                        + "\"links\":{\"websiteUrl\":\"https://www.curseforge.com/minecraft/mc-mods/beta-mod\"}"
                        + "}"
                        + "}");
            } else {
                if (path.endsWith("/files/200702")) {
                    sendJson(exchange, 200, "{"
                            + "\"data\":{"
                            + "\"id\":200702,"
                            + "\"modId\":100702,"
                            + "\"fileName\":\"beta.jar\","
                            + "\"isAvailable\":true,"
                            + "\"fileLength\":" + mod2Bytes.length + ","
                            + "\"downloadUrl\":\"" + baseUrl + "/cdn/mods/beta.jar\","
                            + "\"hashes\":[{\"value\":\"" + mod2Sha1 + "\",\"algo\":1}]"
                            + "}"
                            + "}");
                    return;
                }
                sendJson(exchange, 200, "{\"data\":{\"id\":100702,\"name\":\"BetaMod\",\"allowModDistribution\":true}}");
            }
        });
        server.createContext("/cdn/mods/beta.jar", exchange -> {
            record(exchange);
            sendBytes(exchange, 200, mod2Bytes);
        });

        String manifestJson = "{"
                + "\"manifestType\":\"minecraftModpack\","
                + "\"manifestVersion\":1,"
                + "\"name\":\"PartialPack\","
                + "\"version\":\"1.0.0\","
                + "\"overrides\":\"overrides\","
                + "\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"fabric-0.15.7\",\"primary\":true}]},"
                + "\"files\":["
                + "{\"projectID\":100701,\"fileID\":200701,\"required\":true},"
                + "{\"projectID\":100702,\"fileID\":200702,\"required\":true}"
                + "]"
                + "}";

        File zipFile = createModpackZip("partial-pack.zip", manifestJson, "overrides/options.txt", "fov:80");
        File instanceDir = new File(tempFolder.getRoot(), "instance-partial");

        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);
        try {
            api.installCurseforgeZip(zipFile, instanceDir);
            fail("Expected partial CurseForgeException");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.DISTRIBUTION_NOT_ALLOWED, e.getErrorCode());
            assertEquals(1, e.getFailedFiles().size());
            assertEquals(100702L, e.getFailedFiles().get(0).projectId);
        }

        // Alpha mod AND overrides must be preserved despite Beta failing
        assertTrue(new File(instanceDir, "mods/alpha.jar").isFile());
        assertTrue(new File(instanceDir, "options.txt").isFile());
        int alphaCdnHitsAfterFirstPass = hitCount("/cdn/mods/alpha.jar");
        assertEquals(1, alphaCdnHitsAfterFirstPass);

        // Retry installation when Beta becomes available; Alpha must NOT be re-downloaded!
        betaMode.set(1);
        ModLoader loader = api.installCurseforgeZip(zipFile, instanceDir);
        assertNotNull(loader);
        assertEquals(ModLoader.MOD_LOADER_FABRIC, loader.modLoaderType);
        assertArrayEquals(mod1Bytes, java.nio.file.Files.readAllBytes(new File(instanceDir, "mods/alpha.jar").toPath()));
        assertArrayEquals(mod2Bytes, java.nio.file.Files.readAllBytes(new File(instanceDir, "mods/beta.jar").toPath()));
        assertEquals("Already-verified alpha.jar must not be re-downloaded on retry",
                alphaCdnHitsAfterFirstPass, hitCount("/cdn/mods/alpha.jar"));
        assertEquals(1, hitCount("/cdn/mods/beta.jar"));
    }

    @Test
    public void transientRateLimitInModpack_retriesWithoutRestartingCompletedMods() throws Exception {
        byte[] okBytes = "ok-mod-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] rlBytes = "rate-limited-then-ok-bytes".getBytes(StandardCharsets.UTF_8);
        registerMockFile(100901L, 200901L, "ok-mod.jar", okBytes, sha1Hex(okBytes));

        final AtomicInteger rlAttempts = new AtomicInteger(0);
        final String rlSha1 = sha1Hex(rlBytes);
        server.createContext("/v1/mods/100902/files/200902", exchange -> {
            record(exchange);
            if (rlAttempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                sendJson(exchange, 429, "{\"message\":\"Rate limited\"}");
                return;
            }
            sendJson(exchange, 200, "{"
                    + "\"data\":{"
                    + "\"id\":200902,"
                    + "\"modId\":100902,"
                    + "\"fileName\":\"rl-mod.jar\","
                    + "\"isAvailable\":true,"
                    + "\"fileLength\":" + rlBytes.length + ","
                    + "\"downloadUrl\":\"" + baseUrl + "/cdn/mods/rl-mod.jar\","
                    + "\"hashes\":[{\"value\":\"" + rlSha1 + "\",\"algo\":1}]"
                    + "}"
                    + "}");
        });
        server.createContext("/cdn/mods/rl-mod.jar", exchange -> {
            record(exchange);
            sendBytes(exchange, 200, rlBytes);
        });

        String manifestJson = "{"
                + "\"manifestType\":\"minecraftModpack\","
                + "\"manifestVersion\":1,"
                + "\"name\":\"RetryPack\","
                + "\"version\":\"1.0.0\","
                + "\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"neoforge-20.4.190\",\"primary\":true}]},"
                + "\"files\":["
                + "{\"projectID\":100901,\"fileID\":200901,\"required\":true},"
                + "{\"projectID\":100902,\"fileID\":200902,\"required\":true}"
                + "]"
                + "}";

        File zipFile = createModpackZip("retry-pack.zip", manifestJson, "overrides/readme.txt", "hi");
        File instanceDir = new File(tempFolder.getRoot(), "instance-retry");
        CurseforgeApi api = new CurseforgeApi(baseUrl + "/v1", TEST_API_KEY);
        ModLoader loader = api.installCurseforgeZip(zipFile, instanceDir);

        assertNotNull(loader);
        assertEquals(ModLoader.MOD_LOADER_NEOFORGE, loader.modLoaderType);
        assertEquals(1, hitCount("/cdn/mods/ok-mod.jar"));
        assertTrue(rlAttempts.get() >= 2);
        assertArrayEquals(rlBytes, java.nio.file.Files.readAllBytes(new File(instanceDir, "mods/rl-mod.jar").toPath()));
    }

    @Test
    public void contextConstructor_rejectsDefaultDummyAndUsesConfiguredVaultCredentials() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CurseforgeApi api = new CurseforgeApi(context);
        try {
            api.resolveFileDownload(100801L, 200801L);
            fail("Expected API_KEY_INVALID when no key is configured in Vault");
        } catch (CurseForgeException e) {
            assertEquals(CurseForgeException.ErrorCode.API_KEY_INVALID, e.getErrorCode());
        }
    }

    private void registerMockFile(long projectId, long fileId, String fileName, byte[] content, String sha1) {
        String cdnPath = "/cdn/mods/" + fileName;
        server.createContext("/v1/mods/" + projectId + "/files/" + fileId, exchange -> {
            record(exchange);
            if (!TEST_API_KEY.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
                sendJson(exchange, 403, "{\"error\":\"Invalid API key\"}");
                return;
            }
            sendJson(exchange, 200, "{"
                    + "\"data\":{"
                    + "\"id\":" + fileId + ","
                    + "\"modId\":" + projectId + ","
                    + "\"fileName\":\"" + fileName + "\","
                    + "\"isAvailable\":true,"
                    + "\"fileLength\":" + content.length + ","
                    + "\"downloadUrl\":\"" + baseUrl + cdnPath + "\","
                    + "\"hashes\":[{\"value\":\"" + sha1 + "\",\"algo\":1}]"
                    + "}"
                    + "}");
        });
        server.createContext(cdnPath, exchange -> {
            record(exchange);
            if (!TEST_API_KEY.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
                sendJson(exchange, 401, "Missing CDN x-api-key");
                return;
            }
            sendBytes(exchange, 200, content);
        });
    }

    private File createModpackZip(String zipName, String manifestJson, String overridePath, String overrideContent) throws IOException {
        File zipFile = new File(tempFolder.getRoot(), zipName);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(overridePath));
            zos.write(overrideContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zipFile;
    }

    private void record(MiniHttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        pathHitCounts.merge(path, 1, Integer::sum);
    }

    private int hitCount(String path) {
        Integer c = pathHitCounts.get(path);
        return c != null ? c : 0;
    }

    private static void sendJson(MiniHttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendBytes(MiniHttpExchange exchange, int code, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String sha1Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface MiniHttpHandler {
        void handle(MiniHttpExchange exchange) throws IOException;
    }

    private static final class HeaderMap {
        private final Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        void add(String name, String value) {
            map.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(value);
        }

        void set(String name, String value) {
            List<String> list = new CopyOnWriteArrayList<>();
            list.add(value);
            map.put(name, list);
        }

        String getFirst(String name) {
            List<String> list = map.get(name);
            return (list == null || list.isEmpty()) ? null : list.get(0);
        }

        Map<String, List<String>> entries() {
            return map;
        }
    }

    private static final class MiniHttpExchange implements AutoCloseable {
        private final Socket socket;
        private final URI requestUri;
        private final HeaderMap requestHeaders;
        private final HeaderMap responseHeaders = new HeaderMap();
        private final OutputStream socketOut;
        private boolean headersSent = false;

        MiniHttpExchange(Socket socket, URI requestUri, HeaderMap requestHeaders, OutputStream socketOut) {
            this.socket = socket;
            this.requestUri = requestUri;
            this.requestHeaders = requestHeaders;
            this.socketOut = socketOut;
        }

        URI getRequestURI() {
            return requestUri;
        }

        HeaderMap getRequestHeaders() {
            return requestHeaders;
        }

        HeaderMap getResponseHeaders() {
            return responseHeaders;
        }

        void sendResponseHeaders(int code, long responseLength) throws IOException {
            if (headersSent) return;
            headersSent = true;
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            if (responseHeaders.getFirst("Connection") == null) {
                responseHeaders.set("Connection", "close");
            }
            if (responseLength >= 0 && responseHeaders.getFirst("Content-Length") == null) {
                responseHeaders.set("Content-Length", Long.toString(responseLength));
            } else if (responseLength < 0 && responseHeaders.getFirst("Content-Length") == null) {
                responseHeaders.set("Content-Length", "0");
            }
            for (Map.Entry<String, List<String>> entry : responseHeaders.entries().entrySet()) {
                for (String value : entry.getValue()) {
                    sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
                }
            }
            sb.append("\r\n");
            socketOut.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            socketOut.flush();
        }

        OutputStream getResponseBody() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    socketOut.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    socketOut.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    socketOut.flush();
                }

                @Override
                public void close() throws IOException {
                    socketOut.flush();
                    socket.close();
                }
            };
        }

        @Override
        public void close() throws IOException {
            socketOut.flush();
            socket.close();
        }
    }

    private static final class MiniHttpServer {
        private final ServerSocket serverSocket;
        private final Map<String, MiniHttpHandler> contexts = new ConcurrentHashMap<>();
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private volatile boolean running = true;
        private Thread acceptThread;

        MiniHttpServer() throws IOException {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void createContext(String path, MiniHttpHandler handler) {
            contexts.put(path, handler);
        }

        void start() {
            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        final Socket client = serverSocket.accept();
                        executor.submit(() -> handleClient(client));
                    } catch (IOException ignored) {
                        if (!running) break;
                    }
                }
            }, "MiniHttpServer-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        void stop() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            executor.shutdownNow();
        }

        private void handleClient(Socket client) {
            try (Socket s = client) {
                InputStream in = new BufferedInputStream(s.getInputStream());
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;
                String[] parts = requestLine.split(" ");
                String target = parts.length >= 2 ? parts[1] : "/";

                HeaderMap headers = new HeaderMap();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }

                URI uri = URI.create(target);
                String reqPath = uri.getPath() != null ? uri.getPath() : "/";
                MiniHttpHandler handler = findHandler(reqPath);
                MiniHttpExchange exchange = new MiniHttpExchange(s, uri, headers, s.getOutputStream());
                if (handler == null) {
                    sendJson(exchange, 404, "{\"error\":\"No handler for " + reqPath + "\"}");
                } else {
                    handler.handle(exchange);
                }
            } catch (Exception ignored) {
            }
        }

        private MiniHttpHandler findHandler(String reqPath) {
            String bestKey = null;
            for (String prefix : contexts.keySet()) {
                boolean matches = reqPath.equals(prefix)
                        || "/".equals(prefix)
                        || reqPath.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
                if (matches && (bestKey == null || prefix.length() > bestKey.length())) {
                    bestKey = prefix;
                }
            }
            return bestKey != null ? contexts.get(bestKey) : null;
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int prev = -1;
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    byte[] bytes = baos.toByteArray();
                    int len = bytes.length;
                    if (len > 0 && bytes[len - 1] == '\r') len--;
                    return new String(bytes, 0, len, StandardCharsets.US_ASCII);
                }
                baos.write(b);
                prev = b;
            }
            return baos.size() == 0 && prev == -1 ? null : baos.toString("US-ASCII");
        }
    }
}
