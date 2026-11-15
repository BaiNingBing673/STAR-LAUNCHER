package com.star.launcher.mods.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Official CurseForge Core API v1 client for Minecraft (gameId = 432, classId = 6).
 *
 * Strictly enforces:
 * - No hardcoded or bundled API keys (relies on [CurseForgeCredentialProvider]).
 * - No CDN URL construction fallbacks when downloadUrl is unavailable or distribution is restricted.
 * - Full credential redaction on all errors.
 */
class CurseForgeRepository(
    private val credentialProvider: CurseForgeCredentialProvider,
    private val transport: CurseForgeTransport = UrlConnectionCurseForgeTransport(),
    val enablePublicFallback: Boolean = false
) {
    private val cachedCategories = mutableListOf<CurseForgeCategory>()
    private val cachedMcVersions = mutableListOf<CurseForgeMinecraftVersion>()
    private val cachedLoaders = ConcurrentHashMap<String, List<CurseForgeModLoaderIndex>>()
    private val searchCache = ConcurrentHashMap<CurseForgeSearchQuery, TimedEntry<CurseForgeSearchResponse>>()
    private val modCache = ConcurrentHashMap<Long, TimedEntry<CurseForgeMod>>()
    private val descriptionCache = ConcurrentHashMap<Long, TimedEntry<String>>()
    private val filesCache = ConcurrentHashMap<String, TimedEntry<CurseForgeFilesResponse>>()
    internal val fallbackCatalog = ModrinthFallbackCatalog(transport)
    @Volatile
    private var rejectedKeyInSession: String? = null

    @Volatile
    var lastConnectionResult: CurseForgeConnectionTestResult? = null
        private set

    private val cacheTtlMs = 5 * 60 * 1000L

    fun clearCaches() {
        rejectedKeyInSession = null
        synchronized(cachedCategories) { cachedCategories.clear() }
        synchronized(cachedMcVersions) { cachedMcVersions.clear() }
        cachedLoaders.clear()
        searchCache.clear()
        modCache.clear()
        descriptionCache.clear()
        filesCache.clear()
    }

    suspend fun testConnection(): CurseForgeConnectionTestResult {
        val startMs = System.currentTimeMillis()
        val auth = credentialProvider.getAuthConfig()
        val status = credentialProvider.getCredentialStatus()
        if (auth is CurseForgeAuthConfig.Unconfigured) {
            val res = CurseForgeConnectionTestResult(
                state = CurseForgeConnectionState.CONFIGURATION_REQUIRED,
                credentialStatus = status,
                errorCategory = CurseForgeErrorCategory.MISSING_CONFIGURATION,
                message = CurseForgeApiException.NotConfigured().message,
                latencyMs = 0L
            )
            lastConnectionResult = res
            return res
        }
        rejectedKeyInSession = null
        return try {
            val params = mapOf(
                "gameId" to CurseForgeCredentialProvider.MINECRAFT_GAME_ID.toString(),
                "classId" to CurseForgeCredentialProvider.MODS_CLASS_ID.toString()
            )
            val json = executeJsonGet("/v1/categories", params)
            val data = json.optJSONArray("data")
                ?: throw CurseForgeApiException.ParseError("Missing 'data' array in categories response")
            val parsed = buildList {
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.let { add(parseCategory(it)) }
                }
            }.sortedBy { it.name.lowercase() }
            if (parsed.isNotEmpty()) {
                synchronized(cachedCategories) {
                    cachedCategories.clear()
                    cachedCategories.addAll(parsed)
                }
            }
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            val modeLabel = when (auth) {
                is CurseForgeAuthConfig.BackendProxy -> "Connected via Backend Proxy"
                is CurseForgeAuthConfig.DirectVaultKey -> "Connected to CurseForge API"
                CurseForgeAuthConfig.Unconfigured -> "Configuration Required"
            }
            val res = CurseForgeConnectionTestResult(
                state = CurseForgeConnectionState.CONNECTED,
                credentialStatus = status,
                errorCategory = CurseForgeErrorCategory.NONE,
                message = modeLabel,
                latencyMs = elapsed
            )
            lastConnectionResult = res
            res
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            val res = classifyFailure(t, status, elapsed)
            lastConnectionResult = res
            res
        }
    }

    suspend fun classifyFailure(
        error: Throwable?,
        statusOverride: CurseForgeCredentialStatus? = null,
        latencyMs: Long = 0L
    ): CurseForgeConnectionTestResult {
        val status = statusOverride ?: runCatching { credentialProvider.getCredentialStatus() }
            .getOrDefault(CurseForgeCredentialStatus.UNCONFIGURED)
        val (state, category, msg) = when (error) {
            null -> Triple(
                if (status == CurseForgeCredentialStatus.UNCONFIGURED) {
                    CurseForgeConnectionState.CONFIGURATION_REQUIRED
                } else {
                    CurseForgeConnectionState.CONNECTION_FAILED
                },
                if (status == CurseForgeCredentialStatus.UNCONFIGURED) {
                    CurseForgeErrorCategory.MISSING_CONFIGURATION
                } else {
                    CurseForgeErrorCategory.SERVICE_ERROR
                },
                if (status == CurseForgeCredentialStatus.UNCONFIGURED) {
                    CurseForgeApiException.NotConfigured().message
                } else {
                    "CurseForge API connection failed."
                }
            )
            is CurseForgeApiException.NotConfigured -> Triple(
                CurseForgeConnectionState.CONFIGURATION_REQUIRED,
                CurseForgeErrorCategory.MISSING_CONFIGURATION,
                error.message
            )
            is CurseForgeApiException.Unauthorized -> Triple(
                CurseForgeConnectionState.CONNECTION_FAILED,
                CurseForgeErrorCategory.INVALID_API_KEY,
                "Invalid API key (HTTP 401): Verify your configured CurseForge API key or proxy token."
            )
            is CurseForgeApiException.Forbidden -> {
                if (error.isDistributionRestriction) {
                    Triple(
                        CurseForgeConnectionState.CONNECTED,
                        CurseForgeErrorCategory.NONE,
                        error.message
                    )
                } else {
                    Triple(
                        CurseForgeConnectionState.CONNECTION_FAILED,
                        CurseForgeErrorCategory.INVALID_API_KEY,
                        "Invalid API key or forbidden access (HTTP 403): ${error.serverReason ?: "Verify your CurseForge API key in Vault or switch to a Backend Proxy."}"
                    )
                }
            }
            is CurseForgeApiException.RateLimited -> Triple(
                CurseForgeConnectionState.CONNECTION_FAILED,
                CurseForgeErrorCategory.RATE_LIMITED,
                error.message
            )
            is CurseForgeApiException.NetworkOffline,
            is CurseForgeApiException.Timeout -> Triple(
                CurseForgeConnectionState.CONNECTION_FAILED,
                CurseForgeErrorCategory.NETWORK_FAILURE,
                error.message
            )
            is CurseForgeApiException.ServerError,
            is CurseForgeApiException.ParseError,
            is CurseForgeApiException.NotFound -> Triple(
                CurseForgeConnectionState.CONNECTION_FAILED,
                CurseForgeErrorCategory.SERVICE_ERROR,
                error.message
            )
            else -> Triple(
                CurseForgeConnectionState.CONNECTION_FAILED,
                CurseForgeErrorCategory.SERVICE_ERROR,
                error.message ?: "CurseForge API service error."
            )
        }
        return CurseForgeConnectionTestResult(
            state = state,
            credentialStatus = status,
            errorCategory = category,
            message = msg,
            latencyMs = latencyMs
        )
    }

    private data class TimedEntry<T>(val value: T, val timestampMs: Long = System.currentTimeMillis()) {
        fun isFresh(ttlMs: Long): Boolean =
            (System.currentTimeMillis() - timestampMs) <= ttlMs
    }

    private fun <K, V> putBounded(map: ConcurrentHashMap<K, TimedEntry<V>>, key: K, value: V, maxEntries: Int = 64) {
        if (map.size >= maxEntries) {
            val now = System.currentTimeMillis()
            val expired = map.entries.filter { (now - it.value.timestampMs) > cacheTtlMs }.map { it.key }
            expired.forEach { map.remove(it) }
            if (map.size >= maxEntries) {
                map.keys.firstOrNull()?.let { map.remove(it) }
            }
        }
        map[key] = TimedEntry(value)
    }

    suspend fun getCredentialStatus(): CurseForgeCredentialStatus =
        credentialProvider.getCredentialStatus()

    private fun shouldFallbackToPublicCatalog(err: Throwable?): Boolean {
        if (!enablePublicFallback || err == null) return false
        return when (err) {
            is CurseForgeApiException.NotConfigured -> true
            is CurseForgeApiException.Unauthorized -> true
            is CurseForgeApiException.Forbidden -> !err.isDistributionRestriction
            else -> false
        }
    }

    suspend fun searchMods(query: CurseForgeSearchQuery): Result<CurseForgeSearchResponse> {
        searchCache[query]?.takeIf { it.isFresh(cacheTtlMs) }?.let { return Result.success(it.value) }
        val primary = runApiCall {
            val params = LinkedHashMap<String, String>()
            params["gameId"] = CurseForgeCredentialProvider.MINECRAFT_GAME_ID.toString()
            params["classId"] = CurseForgeCredentialProvider.MODS_CLASS_ID.toString()
            params["sortField"] = query.sortField.apiId.toString()
            params["sortOrder"] = if (query.sortOrderDesc) "desc" else "asc"
            params["index"] = query.index.coerceAtLeast(0).toString()
            params["pageSize"] = query.pageSize.coerceIn(1, 50).toString()
            if (query.searchFilter.isNotBlank()) {
                params["searchFilter"] = query.searchFilter.trim()
            }
            if (!query.gameVersion.isNullOrBlank()) {
                params["gameVersion"] = query.gameVersion.trim()
            }
            if (query.modLoader != CurseForgeModLoader.ANY) {
                params["modLoaderType"] = query.modLoader.apiId.toString()
            }
            if (query.categoryId != null && query.categoryId > 0) {
                params["categoryId"] = query.categoryId.toString()
            }

            val json = executeJsonGet("/v1/mods/search", params)
            val data = json.optJSONArray("data") ?: JSONArray()
            val mods = buildList {
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.let { add(parseMod(it)) }
                }
            }
            val pagination = parsePagination(json.optJSONObject("pagination"), query.index, query.pageSize, mods.size)
            CurseForgeSearchResponse(mods = mods, pagination = pagination)
        }
        val resolved = if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            runApiCall { fallbackCatalog.searchMods(query) }
        } else {
            primary
        }
        resolved.getOrNull()?.let { resp ->
            putBounded(searchCache, query, resp)
            resp.mods.forEach { mod -> putBounded(modCache, mod.id, mod, maxEntries = 128) }
        }
        return resolved
    }

    suspend fun getFeaturedMods(
        gameVersion: String? = null,
        modLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
        pageSize: Int = 10
    ): Result<CurseForgeSearchResponse> = searchMods(
        CurseForgeSearchQuery(
            gameVersion = gameVersion,
            modLoader = modLoader,
            sortField = CurseForgeSortField.FEATURED,
            sortOrderDesc = true,
            index = 0,
            pageSize = pageSize
        )
    )

    suspend fun getMod(modId: Long): Result<CurseForgeMod> {
        modCache[modId]?.takeIf { it.isFresh(cacheTtlMs) }?.let { return Result.success(it.value) }
        if (enablePublicFallback && fallbackCatalog.isFallbackModId(modId)) {
            return runApiCall { fallbackCatalog.getMod(modId) }.also { res ->
                res.getOrNull()?.let { putBounded(modCache, modId, it, maxEntries = 128) }
            }
        }
        val primary = runApiCall {
            val json = executeJsonGet("/v1/mods/$modId")
            val data = json.optJSONObject("data")
                ?: throw CurseForgeApiException.ParseError("Missing 'data' object for mod $modId")
            parseMod(data)
        }
        val resolved = if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            runApiCall { fallbackCatalog.getMod(modId) }
        } else {
            primary
        }
        resolved.getOrNull()?.let { putBounded(modCache, modId, it, maxEntries = 128) }
        return resolved
    }

    suspend fun getModDescription(modId: Long): Result<String> {
        descriptionCache[modId]?.takeIf { it.isFresh(cacheTtlMs) }?.let { return Result.success(it.value) }
        if (enablePublicFallback && fallbackCatalog.isFallbackModId(modId)) {
            return runApiCall { fallbackCatalog.getModDescription(modId) }.also { res ->
                res.getOrNull()?.let { putBounded(descriptionCache, modId, it) }
            }
        }
        val primary = runApiCall {
            val json = executeJsonGet("/v1/mods/$modId/description")
            val rawHtml = json.optString("data", "")
            sanitizeHtmlDescription(rawHtml)
        }
        val resolved = if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            runApiCall { fallbackCatalog.getModDescription(modId) }
        } else {
            primary
        }
        resolved.getOrNull()?.let { putBounded(descriptionCache, modId, it) }
        return resolved
    }

    suspend fun getModFiles(
        modId: Long,
        gameVersion: String? = null,
        modLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
        index: Int = 0,
        pageSize: Int = 50
    ): Result<CurseForgeFilesResponse> {
        val cacheKey = "$modId|${gameVersion.orEmpty()}|${modLoader.name}|$index|$pageSize"
        filesCache[cacheKey]?.takeIf { it.isFresh(cacheTtlMs) }?.let { return Result.success(it.value) }
        if (enablePublicFallback && fallbackCatalog.isFallbackModId(modId)) {
            return runApiCall { fallbackCatalog.getModFiles(modId, gameVersion, modLoader, index, pageSize) }.also { res ->
                res.getOrNull()?.let { putBounded(filesCache, cacheKey, it) }
            }
        }
        val primary = runApiCall {
            val params = LinkedHashMap<String, String>()
            params["index"] = index.coerceAtLeast(0).toString()
            params["pageSize"] = pageSize.coerceIn(1, 50).toString()
            if (!gameVersion.isNullOrBlank()) {
                params["gameVersion"] = gameVersion.trim()
            }
            if (modLoader != CurseForgeModLoader.ANY) {
                params["modLoaderType"] = modLoader.apiId.toString()
            }
            val json = executeJsonGet("/v1/mods/$modId/files", params)
            val data = json.optJSONArray("data") ?: JSONArray()
            val files = buildList {
                for (i in 0 until data.length()) {
                    val fileObj = data.optJSONObject(i) ?: continue
                    val parsed = parseFile(fileObj)
                    if (!parsed.isServerPack) add(parsed)
                }
            }
            val pagination = parsePagination(json.optJSONObject("pagination"), index, pageSize, files.size)
            CurseForgeFilesResponse(files = files, pagination = pagination)
        }
        val resolved = if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            runApiCall { fallbackCatalog.getModFiles(modId, gameVersion, modLoader, index, pageSize) }
        } else {
            primary
        }
        resolved.getOrNull()?.let { putBounded(filesCache, cacheKey, it) }
        return resolved
    }

    suspend fun getModFile(modId: Long, fileId: Long): Result<CurseForgeFile> {
        if (enablePublicFallback && fallbackCatalog.isFallbackModId(modId)) {
            return runApiCall { fallbackCatalog.getModFile(modId, fileId) }
        }
        val primary = runApiCall {
            val json = executeJsonGet("/v1/mods/$modId/files/$fileId")
            val data = json.optJSONObject("data")
                ?: throw CurseForgeApiException.ParseError("Missing 'data' object for file $fileId")
            parseFile(data)
        }
        if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            return runApiCall { fallbackCatalog.getModFile(modId, fileId) }
        }
        return primary
    }

    /**
     * Resolves an official CurseForge download URL via `/v1/mods/{modId}/files/{fileId}/download-url`.
     * Never constructs CDN URLs manually if distribution is restricted or unavailable.
     */
    suspend fun resolveDownloadUrl(mod: CurseForgeMod?, file: CurseForgeFile): Result<CurseForgeDownloadUrlResult> {
        if (mod?.allowModDistribution == false) {
            return Result.success(
                CurseForgeDownloadUrlResult.Restricted(
                    reason = "The author of '${mod.name}' disabled third-party API downloads for this project.",
                    websiteUrl = mod.links.websiteUrl
                )
            )
        }
        if (enablePublicFallback && (fallbackCatalog.isFallbackModId(file.modId) || (mod != null && fallbackCatalog.isFallbackModId(mod.id)))) {
            return runApiCall { fallbackCatalog.resolveDownloadUrl(mod, file) }
        }
        val primary = runApiCall {
            val auth = credentialProvider.getAuthConfig()
            val (url, headers) = buildRequest(auth, "/v1/mods/${file.modId}/files/${file.id}/download-url", emptyMap())
            val response = transport.get(url, headers)
            when (response.statusCode) {
                in 200..299 -> {
                    val json = runCatching { JSONObject(response.body) }.getOrNull()
                    val resolved = json?.optString("data")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: file.downloadUrl?.takeIf { it.isNotBlank() && it != "null" }
                    if (resolved != null) {
                        CurseForgeDownloadUrlResult.Available(resolved)
                    } else {
                        CurseForgeDownloadUrlResult.Restricted(
                            reason = "CurseForge did not provide an official download URL for '${file.fileName}'.",
                            websiteUrl = mod?.links?.websiteUrl
                        )
                    }
                }
                403 -> {
                    val snippet = sanitizeHttpErrorBody(response.body, (auth as? CurseForgeAuthConfig.DirectVaultKey)?.apiKey)
                    val isAuthRejection = snippet?.contains("api key", ignoreCase = true) == true ||
                        snippet?.contains("invalid", ignoreCase = true) == true ||
                        snippet?.contains("missing", ignoreCase = true) == true
                    if (isAuthRejection) {
                        throw CurseForgeApiException.Forbidden(isDistributionRestriction = false, serverReason = snippet)
                    }
                    CurseForgeDownloadUrlResult.Restricted(
                        reason = "Third-party download is restricted by the mod author on CurseForge.",
                        websiteUrl = mod?.links?.websiteUrl
                    )
                }
                else -> {
                    mapHttpError(response, "/v1/mods/${file.modId}/files/${file.id}/download-url", auth)
                }
            }
        }
        if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            return runApiCall { fallbackCatalog.resolveDownloadUrl(mod, file) }
        }
        return primary
    }

    suspend fun getDownloadHeaders(downloadUrl: String): Map<String, String> {
        val parsed = runCatching { java.net.URI(downloadUrl) }.getOrNull() ?: return emptyMap()
        val host = parsed.host?.lowercase() ?: return emptyMap()
        return when (val auth = credentialProvider.getAuthConfig()) {
            is CurseForgeAuthConfig.DirectVaultKey -> {
                if (host == "api.curseforge.com" || host == "forgecdn.net" || host.endsWith(".forgecdn.net")) {
                    mapOf("x-api-key" to auth.apiKey)
                } else {
                    emptyMap()
                }
            }
            is CurseForgeAuthConfig.BackendProxy -> {
                val proxyHost = runCatching { java.net.URI(auth.proxyBaseUrl).host?.lowercase() }.getOrNull()
                if (!auth.clientToken.isNullOrBlank() && proxyHost != null && host == proxyHost) {
                    mapOf("Authorization" to "Bearer ${auth.clientToken}")
                } else {
                    emptyMap()
                }
            }
            CurseForgeAuthConfig.Unconfigured -> emptyMap()
        }
    }

    suspend fun getCategories(forceRefresh: Boolean = false): Result<List<CurseForgeCategory>> {
        val primary = runApiCall {
            if (!forceRefresh && cachedCategories.isNotEmpty()) {
                return@runApiCall cachedCategories.toList()
            }
            val params = mapOf(
                "gameId" to CurseForgeCredentialProvider.MINECRAFT_GAME_ID.toString(),
                "classId" to CurseForgeCredentialProvider.MODS_CLASS_ID.toString()
            )
            val json = executeJsonGet("/v1/categories", params)
            val data = json.optJSONArray("data") ?: JSONArray()
            val parsed = buildList {
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.let { add(parseCategory(it)) }
                }
            }.sortedBy { it.name.lowercase() }
            synchronized(cachedCategories) {
                cachedCategories.clear()
                cachedCategories.addAll(parsed)
            }
            parsed
        }
        if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            return Result.success(fallbackCatalog.getDefaultCategories())
        }
        return primary
    }

    suspend fun getMinecraftVersions(forceRefresh: Boolean = false): Result<List<CurseForgeMinecraftVersion>> {
        val primary = runApiCall {
            if (!forceRefresh && cachedMcVersions.isNotEmpty()) {
                return@runApiCall cachedMcVersions.toList()
            }
            val json = executeJsonGet("/v1/minecraft/version")
            val data = json.optJSONArray("data") ?: JSONArray()
            val parsed = buildList {
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val versionString = obj.optString("versionString")
                    if (versionString.isBlank()) continue
                    add(
                        CurseForgeMinecraftVersion(
                            id = obj.optInt("id"),
                            versionString = versionString,
                            approved = obj.optBoolean("approved", true)
                        )
                    )
                }
            }
            synchronized(cachedMcVersions) {
                cachedMcVersions.clear()
                cachedMcVersions.addAll(parsed)
            }
            parsed
        }
        if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            return Result.success(fallbackCatalog.getDefaultMinecraftVersions())
        }
        return primary
    }

    suspend fun getModLoaders(
        mcVersion: String? = null,
        includeAll: Boolean = false,
        forceRefresh: Boolean = false
    ): Result<List<CurseForgeModLoaderIndex>> {
        val primary = runApiCall {
            val cacheKey = "${mcVersion.orEmpty()}|$includeAll"
            if (!forceRefresh) {
                cachedLoaders[cacheKey]?.let { return@runApiCall it }
            }
            val params = LinkedHashMap<String, String>()
            if (!mcVersion.isNullOrBlank()) params["version"] = mcVersion.trim()
            if (includeAll) params["includeAll"] = "true"
            val json = executeJsonGet("/v1/minecraft/modloader", params)
            val data = json.optJSONArray("data") ?: JSONArray()
            val parsed = buildList {
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    if (name.isBlank()) continue
                    add(
                        CurseForgeModLoaderIndex(
                            name = name,
                            gameVersion = obj.optString("gameVersion"),
                            latest = obj.optBoolean("latest", false),
                            recommended = obj.optBoolean("recommended", false),
                            type = CurseForgeModLoader.fromApiId(obj.optInt("type", -1))
                        )
                    )
                }
            }
            cachedLoaders[cacheKey] = parsed
            parsed
        }
        if (primary.isFailure && shouldFallbackToPublicCatalog(primary.exceptionOrNull())) {
            return Result.success(fallbackCatalog.getDefaultModLoaders(mcVersion))
        }
        return primary
    }

    private suspend fun executeJsonGet(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val startMs = System.currentTimeMillis()
        val auth = credentialProvider.getAuthConfig()
        val status = credentialProvider.getCredentialStatus()
        try {
            if (enablePublicFallback && auth is CurseForgeAuthConfig.DirectVaultKey && auth.apiKey == rejectedKeyInSession) {
                throw CurseForgeApiException.Forbidden(isDistributionRestriction = false, serverReason = "API Key missing or invalid")
            }
            val (url, headers) = buildRequest(auth, path, params)
            var response = transport.get(url, headers)
            if ((response.statusCode == 401 || response.statusCode == 403) && auth is CurseForgeAuthConfig.DirectVaultKey) {
                val bundled = credentialProvider.getBundledAuthConfig()
                if (bundled != null && bundled.apiKey != auth.apiKey) {
                    val (retryUrl, retryHeaders) = buildRequest(bundled, path, params)
                    response = transport.get(retryUrl, retryHeaders)
                }
                if (response.statusCode == 401 || response.statusCode == 403) {
                    rejectedKeyInSession = auth.apiKey
                }
            }
            if (response.statusCode !in 200..299) {
                mapHttpError(response, path, auth)
            }
            val parsed = runCatching { JSONObject(response.body) }.getOrElse { err ->
                throw CurseForgeApiException.ParseError(err.message ?: "Invalid JSON")
            }
            lastConnectionResult = CurseForgeConnectionTestResult(
                state = CurseForgeConnectionState.CONNECTED,
                credentialStatus = status,
                errorCategory = CurseForgeErrorCategory.NONE,
                message = when (auth) {
                    is CurseForgeAuthConfig.BackendProxy -> "Connected via Backend Proxy"
                    is CurseForgeAuthConfig.DirectVaultKey -> "Connected to CurseForge API"
                    CurseForgeAuthConfig.Unconfigured -> "Configuration Required"
                },
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            )
            return parsed
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            lastConnectionResult = classifyFailure(
                error = t,
                statusOverride = status,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            )
            throw t
        }
    }

    private fun buildRequest(
        auth: CurseForgeAuthConfig,
        officialPath: String,
        params: Map<String, String>
    ): Pair<String, Map<String, String>> {
        return when (auth) {
            CurseForgeAuthConfig.Unconfigured -> throw CurseForgeApiException.NotConfigured()
            is CurseForgeAuthConfig.DirectVaultKey -> {
                val url = CurseForgeUrlBuilder.buildUrl(
                    CurseForgeCredentialProvider.OFFICIAL_CURSEFORGE_BASE_URL,
                    officialPath,
                    params
                )
                url to mapOf("x-api-key" to auth.apiKey)
            }
            is CurseForgeAuthConfig.BackendProxy -> {
                val proxyPath = "/cf$officialPath"
                val url = CurseForgeUrlBuilder.buildUrl(auth.proxyBaseUrl, proxyPath, params)
                val headers = if (!auth.clientToken.isNullOrBlank()) {
                    mapOf("Authorization" to "Bearer ${auth.clientToken}")
                } else {
                    emptyMap()
                }
                url to headers
            }
        }
    }

    private fun mapHttpError(
        response: CurseForgeHttpResponse,
        resource: String,
        auth: CurseForgeAuthConfig? = null
    ): Nothing {
        val secret = when (auth) {
            is CurseForgeAuthConfig.DirectVaultKey -> auth.apiKey
            is CurseForgeAuthConfig.BackendProxy -> auth.clientToken
            else -> null
        }
        val snippet = sanitizeHttpErrorBody(response.body, secret)
        when (response.statusCode) {
            401 -> throw CurseForgeApiException.Unauthorized()
            403 -> {
                val isAuthRejection = snippet?.contains("api key", ignoreCase = true) == true ||
                    snippet?.contains("invalid", ignoreCase = true) == true ||
                    snippet?.contains("missing", ignoreCase = true) == true
                throw CurseForgeApiException.Forbidden(
                    isDistributionRestriction = resource.endsWith("/download-url") && !isAuthRejection,
                    serverReason = snippet
                )
            }
            404 -> throw CurseForgeApiException.NotFound(resource)
            429 -> {
                val retryAfter = response.headers["retry-after"]?.trim()?.toIntOrNull()
                throw CurseForgeApiException.RateLimited(retryAfter)
            }
            in 500..599 -> throw CurseForgeApiException.ServerError(response.statusCode, snippet)
            else -> throw CurseForgeApiException.ServerError(response.statusCode, snippet)
        }
    }

    private fun sanitizeHttpErrorBody(rawBody: String, secret: String?): String? {
        if (rawBody.isBlank()) return null
        val jsonMsg = runCatching {
            val obj = JSONObject(rawBody)
            obj.optString("message").takeIf { it.isNotBlank() && it != "null" }
                ?: obj.optString("error").takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        val raw = jsonMsg ?: rawBody
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (raw.isBlank()) return null
        val redacted = CurseForgeUrlBuilder.redactSecrets(raw, secret)
        return if (redacted.length > 160) redacted.take(157) + "..." else redacted
    }

    private suspend inline fun <T> runApiCall(crossinline block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: CurseForgeApiException) {
            Result.failure(e)
        } catch (t: Throwable) {
            val auth = runCatching { credentialProvider.getAuthConfig() }.getOrNull()
            val secret = when (auth) {
                is CurseForgeAuthConfig.DirectVaultKey -> auth.apiKey
                is CurseForgeAuthConfig.BackendProxy -> auth.clientToken
                else -> null
            }
            val safeMessage = CurseForgeUrlBuilder.redactSecrets(t.message ?: t::class.java.simpleName, secret)
            Result.failure(CurseForgeApiException.ParseError(safeMessage))
        }
    }

    companion object {
        private fun JSONObject.optCleanString(key: String): String? {
            if (isNull(key)) return null
            val raw = optString(key, "").trim()
            return raw.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

        internal fun parseMod(obj: JSONObject): CurseForgeMod {
            val authorsArr = obj.optJSONArray("authors") ?: JSONArray()
            val authors = buildList {
                for (i in 0 until authorsArr.length()) {
                    val a = authorsArr.optJSONObject(i) ?: continue
                    add(
                        CurseForgeAuthor(
                            id = a.optLong("id"),
                            name = a.optCleanString("name") ?: "Unknown",
                            url = a.optCleanString("url")
                        )
                    )
                }
            }
            val categoriesArr = obj.optJSONArray("categories") ?: JSONArray()
            val categories = buildList {
                for (i in 0 until categoriesArr.length()) {
                    categoriesArr.optJSONObject(i)?.let { add(parseCategory(it)) }
                }
            }
            val screenshotsArr = obj.optJSONArray("screenshots") ?: JSONArray()
            val screenshots = buildList {
                for (i in 0 until screenshotsArr.length()) {
                    screenshotsArr.optJSONObject(i)?.let { add(parseAsset(it)) }
                }
            }
            val latestArr = obj.optJSONArray("latestFiles") ?: JSONArray()
            val latestFiles = buildList {
                for (i in 0 until latestArr.length()) {
                    latestArr.optJSONObject(i)?.let { add(parseFile(it)) }
                }
            }
            val indexesArr = obj.optJSONArray("latestFilesIndexes") ?: JSONArray()
            val indexVersions = linkedSetOf<String>()
            val indexLoaders = linkedSetOf<CurseForgeModLoader>()
            for (i in 0 until indexesArr.length()) {
                val idx = indexesArr.optJSONObject(i) ?: continue
                idx.optString("gameVersion").takeIf { it.isNotBlank() && it != "null" }?.let { indexVersions.add(it) }
                if (!idx.isNull("modLoader")) {
                    CurseForgeModLoader.fromApiId(idx.optInt("modLoader", -1))?.let {
                        if (it != CurseForgeModLoader.ANY) indexLoaders.add(it)
                    }
                }
            }
            for (f in latestFiles) {
                indexVersions.addAll(f.declaredMinecraftVersions)
                indexLoaders.addAll(f.declaredLoaders)
            }

            val linksObj = obj.optJSONObject("links")
            val links = CurseForgeLinks(
                websiteUrl = linksObj?.optCleanString("websiteUrl"),
                wikiUrl = linksObj?.optCleanString("wikiUrl"),
                issuesUrl = linksObj?.optCleanString("issuesUrl"),
                sourceUrl = linksObj?.optCleanString("sourceUrl")
            )

            val allowDistribution = if (obj.has("allowModDistribution") && !obj.isNull("allowModDistribution")) {
                obj.optBoolean("allowModDistribution")
            } else {
                null
            }

            return CurseForgeMod(
                id = obj.optLong("id"),
                name = obj.optCleanString("name") ?: "Unnamed Mod",
                slug = obj.optCleanString("slug"),
                summary = obj.optCleanString("summary") ?: "",
                downloadCount = obj.optLong("downloadCount", 0L),
                authors = authors,
                categories = categories,
                logo = obj.optJSONObject("logo")?.let { parseAsset(it) },
                screenshots = screenshots,
                links = links,
                allowModDistribution = allowDistribution,
                dateModifiedIso = obj.optCleanString("dateModified"),
                latestFiles = latestFiles,
                supportedMinecraftVersionsFromLatest = indexVersions.toList(),
                supportedLoadersFromLatest = indexLoaders
            )
        }

        internal fun parseFile(obj: JSONObject): CurseForgeFile {
            val hashesArr = obj.optJSONArray("hashes") ?: JSONArray()
            val hashes = buildList {
                for (i in 0 until hashesArr.length()) {
                    val h = hashesArr.optJSONObject(i) ?: continue
                    val valStr = h.optString("value")
                    if (valStr.isNotBlank()) {
                        add(
                            CurseForgeFileHash(
                                value = valStr,
                                algorithm = CurseForgeHashAlgorithm.fromApiId(h.optInt("algo", -1))
                            )
                        )
                    }
                }
            }
            val gvArr = obj.optJSONArray("gameVersions") ?: JSONArray()
            val gameVersions = buildList {
                for (i in 0 until gvArr.length()) {
                    val gv = gvArr.optString(i)
                    if (gv.isNotBlank() && gv != "null") add(gv)
                }
            }
            val depArr = obj.optJSONArray("dependencies") ?: JSONArray()
            val deps = buildList {
                for (i in 0 until depArr.length()) {
                    val d = depArr.optJSONObject(i) ?: continue
                    val depModId = d.optLong("modId", -1L)
                    if (depModId > 0L) {
                        add(
                            CurseForgeFileDependency(
                                modId = depModId,
                                relationType = CurseForgeRelationType.fromApiId(d.optInt("relationType", 2))
                            )
                        )
                    }
                }
            }
            val resolvedFileName = obj.optCleanString("fileName") ?: "mod-${obj.optLong("id")}.jar"
            return CurseForgeFile(
                id = obj.optLong("id"),
                modId = obj.optLong("modId"),
                displayName = obj.optCleanString("displayName") ?: resolvedFileName,
                fileName = resolvedFileName,
                releaseType = CurseForgeReleaseType.fromApiId(obj.optInt("releaseType", 1)),
                isAvailable = obj.optBoolean("isAvailable", true),
                isServerPack = obj.optBoolean("isServerPack", false),
                fileDateIso = obj.optCleanString("fileDate"),
                fileLengthBytes = obj.optLong("fileLength", 0L),
                downloadUrl = obj.optCleanString("downloadUrl"),
                hashes = hashes,
                gameVersions = gameVersions,
                dependencies = deps
            )
        }

        private fun parseCategory(obj: JSONObject): CurseForgeCategory = CurseForgeCategory(
            id = obj.optInt("id"),
            name = obj.optCleanString("name") ?: "Category",
            slug = obj.optCleanString("slug"),
            iconUrl = obj.optCleanString("iconUrl"),
            parentCategoryId = if (obj.isNull("parentCategoryId")) null else obj.optInt("parentCategoryId"),
            classId = if (obj.isNull("classId")) null else obj.optInt("classId")
        )

        private fun parseAsset(obj: JSONObject): CurseForgeAsset = CurseForgeAsset(
            id = obj.optLong("id"),
            title = obj.optCleanString("title"),
            description = obj.optCleanString("description"),
            thumbnailUrl = obj.optCleanString("thumbnailUrl"),
            url = obj.optCleanString("url")
        )

        private fun parsePagination(
            obj: JSONObject?,
            requestedIndex: Int,
            requestedPageSize: Int,
            fallbackCount: Int
        ): CurseForgePagination {
            if (obj == null) {
                return CurseForgePagination(
                    index = requestedIndex,
                    pageSize = requestedPageSize,
                    resultCount = fallbackCount,
                    totalCount = fallbackCount.toLong()
                )
            }
            return CurseForgePagination(
                index = obj.optInt("index", requestedIndex),
                pageSize = obj.optInt("pageSize", requestedPageSize),
                resultCount = obj.optInt("resultCount", fallbackCount),
                totalCount = obj.optLong("totalCount", fallbackCount.toLong())
            )
        }

        internal fun sanitizeHtmlDescription(html: String): String {
            if (html.isBlank()) return ""
            return html
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</p>|</div>|</h[1-6]>|</li>"""), "\n")
                .replace(Regex("""(?i)<li[^>]*>"""), "• ")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }
    }
}
