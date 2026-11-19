package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.kdt.mcgui.ProgressLayout;
import com.star.launcher.core.AetherContainer;
import com.star.launcher.mods.api.CurseForgeAuthConfig;
import com.star.launcher.mods.api.CurseForgeCredentialProvider;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.CurseManifest;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class CurseforgeApi implements ModpackApi {
    private static final String TAG = "CurseforgeApi";
    private static final Pattern sMcVersionPattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private static final String OFFICIAL_V1_BASE_URL = "https://api.curseforge.com/v1";
    private static final int ALGO_SHA_1 = 1;
    private static final int ALGO_MD5 = 2;
    private static final int CURSEFORGE_MINECRAFT_GAME_ID = 432;
    private static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    private static final int CURSEFORGE_MOD_CLASS_ID = 6;
    private static final int CURSEFORGE_SORT_RELEVANCY = 1;
    private static final int CURSEFORGE_PAGINATION_SIZE = 50;
    private static final int CURSEFORGE_PAGINATION_END_REACHED = -1;
    private static final int CURSEFORGE_PAGINATION_ERROR = -2;

    public interface AuthProvider {
        @NonNull
        ResolvedAuth resolveAuth() throws CurseForgeException;
    }

    public static final class ResolvedAuth {
        @NonNull public final String apiBaseUrl;
        @NonNull public final Map<String, String> apiHeaders;
        @Nullable public final String apiKey;
        @Nullable public final String proxyToken;
        public final boolean isProxy;

        public ResolvedAuth(
                @NonNull String apiBaseUrl,
                @NonNull Map<String, String> apiHeaders,
                @Nullable String apiKey,
                @Nullable String proxyToken,
                boolean isProxy
        ) {
            this.apiBaseUrl = trimTrailingSlashes(apiBaseUrl);
            this.apiHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(apiHeaders));
            this.apiKey = apiKey;
            this.proxyToken = proxyToken;
            this.isProxy = isProxy;
        }

        @NonNull
        public Map<String, String> getDownloadHeadersForUrl(@Nullable String downloadUrl) {
            if (downloadUrl == null || downloadUrl.trim().isEmpty()) return Collections.emptyMap();
            String downloadHost = extractHost(downloadUrl);
            String apiHost = extractHost(apiBaseUrl);
            if (isProxy) {
                if (proxyToken != null && !proxyToken.isEmpty()
                        && downloadHost != null && apiHost != null
                        && downloadHost.equalsIgnoreCase(apiHost)) {
                    return Collections.singletonMap("Authorization", "Bearer " + proxyToken);
                }
                return Collections.emptyMap();
            }
            if (apiKey != null && !apiKey.isEmpty() && downloadHost != null) {
                if (ApiHandler.isTrustedCurseForgeHost(downloadHost)
                        || (apiHost != null && downloadHost.equalsIgnoreCase(apiHost))) {
                    return Collections.singletonMap("x-api-key", apiKey);
                }
            }
            return Collections.emptyMap();
        }
    }

    public static final class ResolvedFileDownload {
        @NonNull public final String url;
        @NonNull public final String fileName;
        @Nullable public final String sha1;
        @Nullable public final String md5;
        public final long fileLength;
        @Nullable public final String modName;
        @Nullable public final String websiteUrl;
        @NonNull public final Map<String, String> downloadHeaders;

        public ResolvedFileDownload(
                @NonNull String url,
                @NonNull String fileName,
                @Nullable String sha1,
                @Nullable String md5,
                long fileLength,
                @Nullable String modName,
                @Nullable String websiteUrl,
                @NonNull Map<String, String> downloadHeaders
        ) {
            this.url = url;
            this.fileName = fileName;
            this.sha1 = sha1;
            this.md5 = md5;
            this.fileLength = fileLength;
            this.modName = modName;
            this.websiteUrl = websiteUrl;
            this.downloadHeaders = downloadHeaders;
        }
    }

    private static final class ProjectInfo {
        @Nullable final String name;
        @Nullable final Boolean allowModDistribution;
        @Nullable final String websiteUrl;

        ProjectInfo(@Nullable String name, @Nullable Boolean allowModDistribution, @Nullable String websiteUrl) {
            this.name = name;
            this.allowModDistribution = allowModDistribution;
            this.websiteUrl = websiteUrl;
        }
    }

    private final AuthProvider mAuthProvider;
    private final ConcurrentHashMap<Long, ProjectInfo> mProjectInfoCache = new ConcurrentHashMap<>();

    public CurseforgeApi(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        mAuthProvider = () -> fromCredentialProvider(appContext);
    }

    public CurseforgeApi(@Nullable String apiKey) {
        this(OFFICIAL_V1_BASE_URL, apiKey);
    }

    public CurseforgeApi(@NonNull String apiBaseUrl, @Nullable String apiKey) {
        mAuthProvider = () -> fromRawKey(apiBaseUrl, apiKey);
    }

    public CurseforgeApi(@NonNull AuthProvider authProvider) {
        mAuthProvider = authProvider;
    }

    private static ResolvedAuth fromCredentialProvider(@NonNull Context appContext) throws CurseForgeException {
        CurseForgeCredentialProvider provider = AetherContainer.Companion.get(appContext).getCurseForgeCredentialProvider();
        CurseForgeAuthConfig config = provider.getAuthConfigBlocking();
        if (config instanceof CurseForgeAuthConfig.DirectVaultKey) {
            String key = ((CurseForgeAuthConfig.DirectVaultKey) config).getApiKey();
            return fromRawKey(OFFICIAL_V1_BASE_URL, key);
        }
        if (config instanceof CurseForgeAuthConfig.BackendProxy) {
            CurseForgeAuthConfig.BackendProxy proxy = (CurseForgeAuthConfig.BackendProxy) config;
            Map<String, String> headers = new LinkedHashMap<>();
            String token = proxy.getClientToken();
            if (token != null && !token.trim().isEmpty()) {
                headers.put("Authorization", "Bearer " + token.trim());
            }
            return new ResolvedAuth(
                    trimTrailingSlashes(proxy.getProxyBaseUrl()) + "/cf/v1",
                    headers,
                    null,
                    token,
                    true
            );
        }
        throw CurseForgeException.forFileError(
                CurseForgeException.ErrorCode.API_KEY_INVALID,
                0L, 0L, 0, null, null, null, null,
                "No CurseForge API key or Backend Proxy is configured.",
                null
        );
    }

    private static ResolvedAuth fromRawKey(@NonNull String apiBaseUrl, @Nullable String rawApiKey) throws CurseForgeException {
        String normalized = rawApiKey == null
                ? ""
                : CurseForgeCredentialProvider.Companion.normalizeDeveloperKey(rawApiKey);
        if (!CurseForgeCredentialProvider.Companion.isPlausibleDeveloperKey(normalized)) {
            throw CurseForgeException.forFileError(
                    CurseForgeException.ErrorCode.API_KEY_INVALID,
                    0L, 0L, 0, null, null, null, null,
                    "Configured CurseForge API key is missing or placeholder.",
                    null
            );
        }
        return new ResolvedAuth(
                apiBaseUrl,
                Collections.singletonMap("x-api-key", normalized),
                normalized,
                null,
                false
        );
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;

        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MINECRAFT_GAME_ID);
        params.put("classId", searchFilters.isModpack ? CURSEFORGE_MODPACK_CLASS_ID : CURSEFORGE_MOD_CLASS_ID);
        params.put("searchFilter", searchFilters.name);
        params.put("sortField", CURSEFORGE_SORT_RELEVANCY);
        params.put("sortOrder", "desc");
        if (searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty()) {
            params.put("gameVersion", searchFilters.mcVersion);
        }
        if (previousPageResult != null) {
            params.put("index", curseforgeSearchResult.previousOffset);
        }

        JsonObject response;
        try {
            response = executeApiGetJson("mods/search", params, 0L, 0L, null);
        } catch (IOException e) {
            Log.w(TAG, "searchMod failed: " + e.getMessage());
            return null;
        }
        if (response == null) return null;
        JsonArray dataArray = GsonJsonUtils.getJsonArraySafe(response, "data");
        if (dataArray == null) return null;
        JsonObject paginationInfo = GsonJsonUtils.getJsonObjectSafe(response, "pagination");
        ArrayList<ModItem> modItemList = new ArrayList<>(dataArray.size());
        for (int i = 0; i < dataArray.size(); i++) {
            JsonElement rawElement = dataArray.get(i);
            if (rawElement == null || !rawElement.isJsonObject()) continue;
            JsonObject dataElement = rawElement.getAsJsonObject();
            JsonElement allowModDistribution = dataElement.get("allowModDistribution");
            String modName = GsonJsonUtils.getStringSafe(dataElement, "name");
            // Gson automatically casts null to false, which leads to issues
            // So, only check the distribution flag if it is non-null
            if (allowModDistribution != null && !allowModDistribution.isJsonNull() && !allowModDistribution.getAsBoolean()) {
                Log.i(TAG, "Skipping modpack " + modName + " because third-party distribution is disabled");
                continue;
            }
            JsonObject logoObj = GsonJsonUtils.getJsonObjectSafe(dataElement, "logo");
            String thumbnailUrl = logoObj != null ? GsonJsonUtils.getStringSafe(logoObj, "thumbnailUrl") : null;
            String modId = GsonJsonUtils.getStringSafe(dataElement, "id");
            String summary = GsonJsonUtils.getStringSafe(dataElement, "summary");
            if (modId == null) continue;
            ModItem modItem = new ModItem(Constants.SOURCE_CURSEFORGE,
                    searchFilters.isModpack,
                    modId,
                    modName != null ? modName : "Unnamed",
                    summary != null ? summary : "",
                    thumbnailUrl);
            modItemList.add(modItem);
        }
        if (curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = modItemList.toArray(new ModItem[0]);
        curseforgeSearchResult.totalResultCount = GsonJsonUtils.getIntSafe(paginationInfo, "totalCount", modItemList.size());
        curseforgeSearchResult.previousOffset += dataArray.size();
        return curseforgeSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        fillInMissingModItemData(item);
        ArrayList<JsonObject> allModDetails = new ArrayList<>();
        int index = 0;
        while (index != CURSEFORGE_PAGINATION_END_REACHED &&
                index != CURSEFORGE_PAGINATION_ERROR) {
            index = getPaginatedDetails(allModDetails, index, item.id);
        }
        if (index == CURSEFORGE_PAGINATION_ERROR) return null;
        int length = allModDetails.size();
        String[] versionNames = new String[length];
        String[] versionIds = new String[length];
        String[] mcVersionNames = new String[length];
        String[] versionUrls = new String[length];
        String[] hashes = new String[length];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[length][];
        for (int i = 0; i < allModDetails.size(); i++) {
            JsonObject modDetail = allModDetails.get(i);
            String displayName = GsonJsonUtils.getStringSafe(modDetail, "displayName");
            String fileIdStr = GsonJsonUtils.getStringSafe(modDetail, "id");
            String dlUrl = GsonJsonUtils.getStringSafe(modDetail, "downloadUrl");
            if (dlUrl != null && (dlUrl.trim().isEmpty() || "null".equalsIgnoreCase(dlUrl.trim()))) {
                dlUrl = null;
            }
            // Never construct guessed edge.forgecdn.net URLs when downloadUrl is null/missing.
            versionNames[i] = displayName != null ? displayName : fileIdStr;
            versionIds[i] = fileIdStr;
            versionUrls[i] = dlUrl;

            JsonArray gameVersions = GsonJsonUtils.getJsonArraySafe(modDetail, "gameVersions");
            JsonArray dependenciesJsonArray = GsonJsonUtils.getJsonArraySafe(modDetail, "dependencies");
            if (dependenciesJsonArray != null) {
                dependencies[i] = new ModDetail.Dependencies[dependenciesJsonArray.size()];
                for (int i1 = 0; i1 < dependenciesJsonArray.size(); ++i1) {
                    JsonObject obj = GsonJsonUtils.getJsonObjectSafe(dependenciesJsonArray.get(i1));
                    dependencies[i][i1] = obj == null ? null : new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "modId"),
                            null,
                            null, // These two are only present on modrinth
                            GsonJsonUtils.getStringSafe(obj, "relationType")
                    );
                }
            } else {
                dependencies[i] = new ModDetail.Dependencies[0];
            }
            if (gameVersions != null) {
                for (JsonElement jsonElement : gameVersions) {
                    if (jsonElement == null || jsonElement.isJsonNull()) continue;
                    String gameVersion = jsonElement.getAsString();
                    if (!sMcVersionPattern.matcher(gameVersion).matches()) {
                        continue;
                    }
                    mcVersionNames[i] = gameVersion;
                    break;
                }
            }

            hashes[i] = getSha1FromModData(modDetail);
        }
        return new ModDetail(item, versionNames, versionIds, mcVersionNames, versionUrls, hashes, dependencies);
    }

    private void fillInMissingModItemData(ModItem item) {
        if (!(item.title == null || item.description == null || item.imageUrl == null)) return;
        JsonObject response;
        try {
            response = executeApiGetJson(String.format("mods/%s", item.id), null, parseLongOrZero(item.id), 0L, null);
        } catch (IOException e) {
            Log.w(TAG, "fillInMissingModItemData failed: " + e.getMessage());
            return;
        }
        JsonObject data = GsonJsonUtils.getJsonObjectSafe(response, "data");
        if (data == null) return;
        if (item.title == null) {
            item.title = GsonJsonUtils.getStringSafe(data, "name");
            if (item.title == null) item.title = "";
        }
        if (item.description == null) {
            item.description = GsonJsonUtils.getStringSafe(data, "summary");
            if (item.description == null) item.description = "";
        }
        if (item.imageUrl == null) {
            JsonObject logoObj = GsonJsonUtils.getJsonObjectSafe(data, "logo");
            item.imageUrl = logoObj != null ? GsonJsonUtils.getStringSafe(logoObj, "thumbnailUrl") : null;
        }
    }

    @Override
    public ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException {
        if (modDetail.versionUrls == null || selectedVersion < 0 || selectedVersion >= modDetail.versionUrls.length) {
            throw CurseForgeException.forFileError(
                    CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING,
                    parseLongOrZero(modDetail.id),
                    0L,
                    0,
                    modDetail.title,
                    null,
                    null,
                    null,
                    "Selected modpack version index is invalid.",
                    null
            );
        }
        ResolvedAuth auth = mAuthProvider.resolveAuth();
        String versionUrl = modDetail.versionUrls[selectedVersion];
        if (versionUrl == null || versionUrl.trim().isEmpty()) {
            long projectId = parseLongOrZero(modDetail.id);
            long fileId = (modDetail.versionIds != null && selectedVersion < modDetail.versionIds.length)
                    ? parseLongOrZero(modDetail.versionIds[selectedVersion])
                    : 0L;
            ResolvedFileDownload resolved = resolveFileDownload(projectId, fileId);
            modDetail.versionUrls[selectedVersion] = resolved.url;
            versionUrl = resolved.url;
        }
        Map<String, String> downloadHeaders = auth.getDownloadHeadersForUrl(versionUrl);
        return ModpackInstaller.installModpack(modDetail, selectedVersion, downloadHeaders, this::installCurseforgeZip);
    }

    @Override
    public ModLoader importModpack(File modpackFile) throws IOException, NoSuchAlgorithmException {
        return ModpackInstaller.importModpack(modpackFile, Constants.SOURCE_CURSEFORGE, this::installCurseforgeZip);
    }

    private int getPaginatedDetails(ArrayList<JsonObject> objectList, int index, String modId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("pageSize", CURSEFORGE_PAGINATION_SIZE);

        JsonObject response;
        try {
            response = executeApiGetJson("mods/" + modId + "/files", params, parseLongOrZero(modId), 0L, null);
        } catch (IOException e) {
            Log.w(TAG, "getPaginatedDetails failed: " + e.getMessage());
            return CURSEFORGE_PAGINATION_ERROR;
        }
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        if (data == null) return CURSEFORGE_PAGINATION_ERROR;

        for (int i = 0; i < data.size(); i++) {
            JsonObject fileInfo = GsonJsonUtils.getJsonObjectSafe(data.get(i));
            if (fileInfo == null) continue;
            if (getBooleanSafe(fileInfo, "isServerPack", false)) continue;
            objectList.add(fileInfo);
        }
        if (data.size() < CURSEFORGE_PAGINATION_SIZE) {
            return CURSEFORGE_PAGINATION_END_REACHED;
        }
        return index + data.size();
    }

    public ModLoader installCurseforgeZip(File zipFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(zipFile)) {
            CurseManifest curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "manifest.json")),
                    CurseManifest.class);
            if (!verifyManifest(curseManifest)) {
                Log.i(TAG, "manifest verification failed");
                throw new IOException("Invalid or incomplete CurseForge manifest.json.");
            }
            CurseManifest.CurseFile[] files = curseManifest.files != null
                    ? curseManifest.files
                    : new CurseManifest.CurseFile[0];

            List<CurseForgeException.FileFailure> optionalFailures = Collections.emptyList();
            List<CurseForgeException.FileFailure> requiredFailures = Collections.emptyList();
            int succeededCount = 0;

            if (files.length > 0) {
                // Only require CurseForge API credentials when the pack actually references remote files to download
                mAuthProvider.resolveAuth();

                File modsDir = new File(instanceDestination, "mods");
                ModDownloader modDownloader = new ModDownloader(modsDir, true, true);

                for (final CurseManifest.CurseFile curseFile : files) {
                    modDownloader.submitDownload(curseFile.projectID, curseFile.fileID, curseFile.required, () -> {
                        ResolvedFileDownload resolved = resolveFileDownload(curseFile.projectID, curseFile.fileID);
                        return new ModDownloader.FileInfo(
                                resolved.url,
                                resolved.fileName,
                                resolved.sha1,
                                resolved.md5,
                                resolved.fileLength,
                                resolved.downloadHeaders,
                                curseFile.projectID,
                                curseFile.fileID,
                                resolved.modName,
                                resolved.websiteUrl
                        );
                    });
                }

                Tools.DownloaderFeedback progressCallback = (c, m) ->
                        ProgressKeeper.submitProgress(
                                ProgressLayout.INSTALL_MODPACK,
                                (int) Math.max((float) c / Math.max(m, 1) * 100, 0),
                                R.string.modpack_download_downloading_mods_fc,
                                c,
                                m
                        );

                modDownloader.awaitFinish(progressCallback);

                if (modDownloader.hasRetryableFailures()) {
                    modDownloader.retryFailed(progressCallback);
                }

                optionalFailures = modDownloader.getOptionalFailures();
                requiredFailures = modDownloader.getFailedDownloads();
                succeededCount = modDownloader.getSucceededCount();
            }

            String overridesDir = "overrides";
            if (curseManifest.overrides != null && !curseManifest.overrides.trim().isEmpty()) {
                overridesDir = curseManifest.overrides.trim();
            }
            if (!overridesDir.isEmpty() && !overridesDir.endsWith("/")) {
                overridesDir = overridesDir + "/";
            }
            ZipUtils.zipExtract(modpackZipFile, overridesDir, instanceDestination);

            if (!optionalFailures.isEmpty()) {
                Log.w(TAG, optionalFailures.size() + " optional modpack file(s) could not be downloaded and were skipped.");
            }

            if (!requiredFailures.isEmpty()) {
                throw CurseForgeException.partialFailure(
                        files.length,
                        succeededCount,
                        requiredFailures,
                        optionalFailures
                );
            }

            return createInfo(curseManifest.minecraft);
        }
    }

    private ModLoader createInfo(CurseManifest.CurseMinecraft minecraft) {
        if (minecraft == null || minecraft.modLoaders == null || minecraft.modLoaders.length == 0) {
            return null;
        }
        CurseManifest.CurseModLoader primaryModLoader = null;
        for (CurseManifest.CurseModLoader modLoader : minecraft.modLoaders) {
            if (modLoader.primary) {
                primaryModLoader = modLoader;
                break;
            }
        }
        if (primaryModLoader == null) primaryModLoader = minecraft.modLoaders[0];
        String modLoaderId = primaryModLoader.id;
        int dashIndex = modLoaderId != null ? modLoaderId.indexOf('-') : -1;
        if (dashIndex <= 0 || dashIndex >= modLoaderId.length() - 1) return null;
        String modLoaderName = modLoaderId.substring(0, dashIndex);
        String modLoaderVersion = modLoaderId.substring(dashIndex + 1);
        Log.i(TAG, modLoaderId + " " + modLoaderName + " " + modLoaderVersion);
        int modLoaderTypeInt;
        switch (modLoaderName) {
            case "forge":
                modLoaderTypeInt = ModLoader.MOD_LOADER_FORGE;
                break;
            case "fabric":
                modLoaderTypeInt = ModLoader.MOD_LOADER_FABRIC;
                break;
            case "quilt":
                modLoaderTypeInt = ModLoader.MOD_LOADER_QUILT;
                break;
            case "neoforge":
                modLoaderTypeInt = ModLoader.MOD_LOADER_NEOFORGE;
                break;
            default:
                return null;
        }
        return new ModLoader(modLoaderTypeInt, modLoaderVersion, minecraft.version);
    }

    /**
     * Resolves an official download URL and file integrity metadata for a CurseForge file.
     * Never constructs guessed CDN URLs when distribution is disabled or downloadUrl is missing.
     */
    @NonNull
    public ResolvedFileDownload resolveFileDownload(long projectID, long fileID) throws IOException {
        ResolvedAuth auth = mAuthProvider.resolveAuth();

        // 1. Fetch authoritative file metadata: GET /v1/mods/{modId}/files/{fileId}
        JsonObject fileResponse = executeApiGetJson(
                auth,
                "mods/" + projectID + "/files/" + fileID,
                null,
                projectID,
                fileID,
                null
        );
        JsonObject fileData = GsonJsonUtils.getJsonObjectSafe(fileResponse, "data");
        if (fileData == null) {
            throw CurseForgeException.forFileError(
                    CurseForgeException.ErrorCode.API_SERVER_ERROR,
                    projectID, fileID, 200, null, null, null, null,
                    "CurseForge API response is missing the 'data' object for file " + fileID + ".",
                    null
            );
        }

        String rawFileName = GsonJsonUtils.getStringSafe(fileData, "fileName");
        String displayName = GsonJsonUtils.getStringSafe(fileData, "displayName");
        boolean isAvailable = getBooleanSafe(fileData, "isAvailable", true);
        if (!isAvailable) {
            throw CurseForgeException.forFileError(
                    CurseForgeException.ErrorCode.FILE_UNAVAILABLE,
                    projectID, fileID, 200, null,
                    rawFileName != null ? rawFileName : displayName,
                    null, null, null, null
            );
        }

        long fileLength = getLongSafe(fileData, "fileLength", -1L);
        String sha1 = getSha1FromModData(fileData);
        String md5 = getHashFromModData(fileData, ALGO_MD5);
        String downloadUrl = cleanNullableUrl(GsonJsonUtils.getStringSafe(fileData, "downloadUrl"));

        ProjectInfo projectInfo = null;
        if (downloadUrl == null) {
            // 2. Try official download-url endpoint: GET /v1/mods/{modId}/files/{fileId}/download-url
            String dlEndpoint = "mods/" + projectID + "/files/" + fileID + "/download-url";
            ApiHandler handler = new ApiHandler(auth.apiBaseUrl, auth.apiHeaders);
            ApiHandler.HttpResult dlResult = handler.executeGet(dlEndpoint);

            if (dlResult.statusCode >= 200 && dlResult.statusCode < 300) {
                JsonObject dlJson = parseJsonObjectSafe(dlResult.body);
                if (dlJson != null) {
                    downloadUrl = cleanNullableUrl(GsonJsonUtils.getStringSafe(dlJson, "data"));
                }
                if (downloadUrl == null) {
                    projectInfo = fetchProjectInfoSilently(auth, projectID);
                    CurseForgeException.ErrorCode code = (projectInfo != null && Boolean.FALSE.equals(projectInfo.allowModDistribution))
                            ? CurseForgeException.ErrorCode.DISTRIBUTION_NOT_ALLOWED
                            : CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING;
                    throw CurseForgeException.forFileError(
                            code,
                            projectID,
                            fileID,
                            dlResult.statusCode,
                            projectInfo != null ? projectInfo.name : null,
                            rawFileName != null ? rawFileName : displayName,
                            projectInfo != null ? projectInfo.websiteUrl : null,
                            null,
                            null,
                            null
                    );
                }
            } else if (dlResult.statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
                String snippet = sanitizeBodySnippet(dlResult.body, auth);
                if (looksLikeAuthRejection(snippet)) {
                    throw CurseForgeException.forFileError(
                            CurseForgeException.ErrorCode.API_KEY_INVALID,
                            projectID, fileID, dlResult.statusCode, null,
                            rawFileName != null ? rawFileName : displayName,
                            null, null, snippet, null
                    );
                }
                projectInfo = fetchProjectInfoSilently(auth, projectID);
                throw CurseForgeException.forFileError(
                        CurseForgeException.ErrorCode.DISTRIBUTION_NOT_ALLOWED,
                        projectID,
                        fileID,
                        dlResult.statusCode,
                        projectInfo != null ? projectInfo.name : null,
                        rawFileName != null ? rawFileName : displayName,
                        projectInfo != null ? projectInfo.websiteUrl : null,
                        null,
                        snippet,
                        null
                );
            } else {
                throw mapApiHttpError(
                        dlResult,
                        auth,
                        projectID,
                        fileID,
                        rawFileName != null ? rawFileName : displayName,
                        false
                );
            }
        }

        String safeFileName = sanitizeDownloadFileName(rawFileName, downloadUrl, projectID, fileID);
        Map<String, String> downloadHeaders = auth.getDownloadHeadersForUrl(downloadUrl);
        return new ResolvedFileDownload(
                downloadUrl,
                safeFileName,
                sha1,
                md5,
                fileLength,
                projectInfo != null ? projectInfo.name : null,
                projectInfo != null ? projectInfo.websiteUrl : null,
                downloadHeaders
        );
    }

    @NonNull
    public String getDownloadUrl(long projectID, long fileID) throws IOException {
        return resolveFileDownload(projectID, fileID).url;
    }

    @Nullable
    private ProjectInfo fetchProjectInfoSilently(@NonNull ResolvedAuth auth, long projectID) {
        ProjectInfo cached = mProjectInfoCache.get(projectID);
        if (cached != null) return cached;
        try {
            JsonObject modResponse = executeApiGetJson(auth, "mods/" + projectID, null, projectID, 0L, null);
            JsonObject data = GsonJsonUtils.getJsonObjectSafe(modResponse, "data");
            if (data == null) return null;
            String name = GsonJsonUtils.getStringSafe(data, "name");
            JsonElement allowDistEl = GsonJsonUtils.getElementSafe(data, "allowModDistribution");
            Boolean allowDist = (allowDistEl != null && allowDistEl.isJsonPrimitive())
                    ? allowDistEl.getAsBoolean()
                    : null;
            JsonObject links = GsonJsonUtils.getJsonObjectSafe(data, "links");
            String websiteUrl = links != null ? GsonJsonUtils.getStringSafe(links, "websiteUrl") : null;
            ProjectInfo info = new ProjectInfo(name, allowDist, websiteUrl);
            mProjectInfoCache.put(projectID, info);
            return info;
        } catch (IOException e) {
            Log.w(TAG, "Unable to fetch project metadata for " + projectID + ": " + e.getMessage());
            return null;
        }
    }

    private JsonObject executeApiGetJson(
            String endpoint,
            @Nullable Map<String, Object> params,
            long projectId,
            long fileId,
            @Nullable String fileName
    ) throws IOException {
        ResolvedAuth auth = mAuthProvider.resolveAuth();
        return executeApiGetJson(auth, endpoint, params, projectId, fileId, fileName);
    }

    private JsonObject executeApiGetJson(
            @NonNull ResolvedAuth auth,
            String endpoint,
            @Nullable Map<String, Object> params,
            long projectId,
            long fileId,
            @Nullable String fileName
    ) throws IOException {
        ApiHandler handler = new ApiHandler(auth.apiBaseUrl, auth.apiHeaders);
        ApiHandler.HttpResult result = handler.executeGet(endpoint, params);
        if (result.statusCode < 200 || result.statusCode >= 300) {
            throw mapApiHttpError(result, auth, projectId, fileId, fileName, true);
        }
        JsonObject parsed = parseJsonObjectSafe(result.body);
        if (parsed == null) {
            throw CurseForgeException.forFileError(
                    CurseForgeException.ErrorCode.API_SERVER_ERROR,
                    projectId, fileId, result.statusCode, null, fileName, null, null,
                    "CurseForge returned invalid JSON.",
                    null
            );
        }
        return parsed;
    }

    private CurseForgeException mapApiHttpError(
            @NonNull ApiHandler.HttpResult result,
            @NonNull ResolvedAuth auth,
            long projectId,
            long fileId,
            @Nullable String fileName,
            boolean treat403AsAuth
    ) {
        String snippet = sanitizeBodySnippet(result.body, auth);
        Integer retryAfter = result.getRetryAfterSeconds();
        CurseForgeException.ErrorCode code;
        switch (result.statusCode) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                code = CurseForgeException.ErrorCode.API_KEY_INVALID;
                break;
            case HttpURLConnection.HTTP_FORBIDDEN:
                if (treat403AsAuth || looksLikeAuthRejection(snippet)) {
                    code = CurseForgeException.ErrorCode.API_KEY_INVALID;
                } else {
                    code = CurseForgeException.ErrorCode.DISTRIBUTION_NOT_ALLOWED;
                }
                break;
            case HttpURLConnection.HTTP_NOT_FOUND:
                code = CurseForgeException.ErrorCode.FILE_NOT_FOUND;
                break;
            case 429:
                code = CurseForgeException.ErrorCode.API_RATE_LIMITED;
                break;
            default:
                code = CurseForgeException.ErrorCode.API_SERVER_ERROR;
                break;
        }
        return CurseForgeException.forFileError(
                code,
                projectId,
                fileId,
                result.statusCode,
                null,
                fileName,
                null,
                retryAfter,
                snippet,
                null
        );
    }

    private static boolean looksLikeAuthRejection(@Nullable String snippet) {
        if (snippet == null) return false;
        String lower = snippet.toLowerCase(Locale.ROOT);
        return lower.contains("api key")
                || lower.contains("invalid key")
                || lower.contains("missing key")
                || lower.contains("unauthorized")
                || lower.contains("x-api-key");
    }

    @Nullable
    private static String sanitizeBodySnippet(@Nullable String rawBody, @NonNull ResolvedAuth auth) {
        if (rawBody == null || rawBody.trim().isEmpty()) return null;
        String msg = null;
        JsonObject obj = parseJsonObjectSafe(rawBody);
        if (obj != null) {
            msg = GsonJsonUtils.getStringSafe(obj, "message");
            if (msg == null || msg.trim().isEmpty()) {
                msg = GsonJsonUtils.getStringSafe(obj, "error");
            }
        }
        if (msg == null || msg.trim().isEmpty()) {
            msg = rawBody.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
        if (msg.isEmpty()) return null;
        String redacted = CurseForgeException.redactSecrets(msg, auth.apiKey, auth.proxyToken);
        return redacted.length() > 160 ? redacted.substring(0, 157) + "..." : redacted;
    }

    @Nullable
    private static JsonObject parseJsonObjectSafe(@Nullable String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) return null;
        try {
            JsonElement el = JsonParser.parseString(rawBody);
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static String cleanNullableUrl(@Nullable String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return null;
        return trimmed;
    }

    @NonNull
    static String sanitizeDownloadFileName(
            @Nullable String apiFileName,
            @NonNull String downloadUrl,
            long projectId,
            long fileId
    ) {
        String candidate = apiFileName;
        if (candidate == null || candidate.trim().isEmpty()) {
            candidate = extractFileNameFromUrl(downloadUrl);
        }
        if (candidate != null) {
            candidate = candidate.trim();
            int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
            if (slash >= 0) {
                candidate = candidate.substring(slash + 1);
            }
            candidate = candidate.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        }
        if (candidate == null || candidate.isEmpty() || ".".equals(candidate) || "..".equals(candidate)) {
            return projectId + "-" + fileId + ".jar";
        }
        return candidate;
    }

    @Nullable
    private static String extractFileNameFromUrl(@NonNull String url) {
        String path = null;
        try {
            path = new URI(url).getPath();
        } catch (Exception ignored) {}
        if (path == null || path.isEmpty()) {
            int queryIdx = url.indexOf('?');
            String stripped = queryIdx >= 0 ? url.substring(0, queryIdx) : url;
            int fragIdx = stripped.indexOf('#');
            if (fragIdx >= 0) stripped = stripped.substring(0, fragIdx);
            path = stripped;
        }
        int lastSlash = path.lastIndexOf('/');
        String segment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (segment.isEmpty()) return null;
        try {
            return URLDecoder.decode(segment, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return segment;
        }
    }

    @Nullable
    private static String extractHost(@Nullable String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return null;
        try {
            return new URI(rawUrl).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String trimTrailingSlashes(@NonNull String url) {
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static long parseLongOrZero(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long getLongSafe(@Nullable JsonObject obj, String member, long fallback) {
        JsonElement el = GsonJsonUtils.getElementSafe(obj, member);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean getBooleanSafe(@Nullable JsonObject obj, String member, boolean fallback) {
        JsonElement el = GsonJsonUtils.getElementSafe(obj, member);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String getSha1FromModData(@NonNull JsonObject object) {
        return getHashFromModData(object, ALGO_SHA_1);
    }

    @Nullable
    private String getHashFromModData(@NonNull JsonObject object, int algoId) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(object, "hashes");
        if (hashes == null) return null;
        for (JsonElement jsonElement : hashes) {
            // sha1 = 1; md5 = 2
            JsonObject jsonObject = GsonJsonUtils.getJsonObjectSafe(jsonElement);
            if (GsonJsonUtils.getIntSafe(jsonObject, "algo", -1) == algoId) {
                return GsonJsonUtils.getStringSafe(jsonObject, "value");
            }
        }
        return null;
    }

    private boolean verifyManifest(CurseManifest manifest) {
        if (manifest == null) return false;
        if (!"minecraftModpack".equals(manifest.manifestType)) return false;
        if (manifest.manifestVersion != 1) return false;
        if (manifest.minecraft == null) return false;
        if (manifest.minecraft.version == null) return false;
        if (manifest.minecraft.modLoaders == null) return false;
        return manifest.minecraft.modLoaders.length >= 1;
    }

    static class CurseforgeSearchResult extends SearchResult {
        int previousOffset;
    }
}
