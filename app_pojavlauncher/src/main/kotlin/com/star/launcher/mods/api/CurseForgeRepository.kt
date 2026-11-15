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
    private val transport: CurseForgeTransport = UrlConnectionCurseForgeTransport()
) {
    private val cachedCategories = mutableListOf<CurseForgeCategory>()
    private val cachedMcVersions = mutableListOf<CurseForgeMinecraftVersion>()
    private val cachedLoaders = ConcurrentHashMap<String, List<CurseForgeModLoaderIndex>>()

    suspend fun getCredentialStatus(): CurseForgeCredentialStatus =
        credentialProvider.getCredentialStatus()

    suspend fun searchMods(query: CurseForgeSearchQuery): Result<CurseForgeSearchResponse> = runApiCall {
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

    suspend fun getMod(modId: Long): Result<CurseForgeMod> = runApiCall {
        val json = executeJsonGet("/v1/mods/$modId")
        val data = json.optJSONObject("data")
            ?: throw CurseForgeApiException.ParseError("Missing 'data' object for mod $modId")
        parseMod(data)
    }

    suspend fun getModDescription(modId: Long): Result<String> = runApiCall {
        val json = executeJsonGet("/v1/mods/$modId/description")
        val rawHtml = json.optString("data", "")
        sanitizeHtmlDescription(rawHtml)
    }

    suspend fun getModFiles(
        modId: Long,
        gameVersion: String? = null,
        modLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
        index: Int = 0,
        pageSize: Int = 50
    ): Result<CurseForgeFilesResponse> = runApiCall {
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

    suspend fun getModFile(modId: Long, fileId: Long): Result<CurseForgeFile> = runApiCall {
        val json = executeJsonGet("/v1/mods/$modId/files/$fileId")
        val data = json.optJSONObject("data")
            ?: throw CurseForgeApiException.ParseError("Missing 'data' object for file $fileId")
        parseFile(data)
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
        return runApiCall {
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
                403 -> CurseForgeDownloadUrlResult.Restricted(
                    reason = "Third-party download is restricted by the mod author on CurseForge.",
                    websiteUrl = mod?.links?.websiteUrl
                )
                else -> {
                    mapHttpError(response, "/v1/mods/${file.modId}/files/${file.id}/download-url")
                }
            }
        }
    }

    suspend fun getCategories(forceRefresh: Boolean = false): Result<List<CurseForgeCategory>> = runApiCall {
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

    suspend fun getMinecraftVersions(forceRefresh: Boolean = false): Result<List<CurseForgeMinecraftVersion>> = runApiCall {
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

    suspend fun getModLoaders(
        mcVersion: String? = null,
        includeAll: Boolean = false,
        forceRefresh: Boolean = false
    ): Result<List<CurseForgeModLoaderIndex>> = runApiCall {
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

    private suspend fun executeJsonGet(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val auth = credentialProvider.getAuthConfig()
        val (url, headers) = buildRequest(auth, path, params)
        val response = transport.get(url, headers)
        if (response.statusCode !in 200..299) {
            mapHttpError(response, path)
        }
        return runCatching { JSONObject(response.body) }.getOrElse { err ->
            throw CurseForgeApiException.ParseError(err.message ?: "Invalid JSON")
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

    private fun mapHttpError(response: CurseForgeHttpResponse, resource: String): Nothing {
        when (response.statusCode) {
            401 -> throw CurseForgeApiException.Unauthorized()
            403 -> throw CurseForgeApiException.Forbidden(
                isDistributionRestriction = resource.endsWith("/download-url")
            )
            404 -> throw CurseForgeApiException.NotFound(resource)
            429 -> {
                val retryAfter = response.headers["retry-after"]?.trim()?.toIntOrNull()
                throw CurseForgeApiException.RateLimited(retryAfter)
            }
            in 500..599 -> throw CurseForgeApiException.ServerError(response.statusCode)
            else -> throw CurseForgeApiException.ServerError(response.statusCode)
        }
    }

    private suspend inline fun <T> runApiCall(crossinline block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
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
        internal fun parseMod(obj: JSONObject): CurseForgeMod {
            val authorsArr = obj.optJSONArray("authors") ?: JSONArray()
            val authors = buildList {
                for (i in 0 until authorsArr.length()) {
                    val a = authorsArr.optJSONObject(i) ?: continue
                    add(
                        CurseForgeAuthor(
                            id = a.optLong("id"),
                            name = a.optString("name", "Unknown"),
                            url = a.optString("url").takeIf { it.isNotBlank() && it != "null" }
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
                websiteUrl = linksObj?.optString("websiteUrl")?.takeIf { it.isNotBlank() && it != "null" },
                wikiUrl = linksObj?.optString("wikiUrl")?.takeIf { it.isNotBlank() && it != "null" },
                issuesUrl = linksObj?.optString("issuesUrl")?.takeIf { it.isNotBlank() && it != "null" },
                sourceUrl = linksObj?.optString("sourceUrl")?.takeIf { it.isNotBlank() && it != "null" }
            )

            val allowDistribution = if (obj.has("allowModDistribution") && !obj.isNull("allowModDistribution")) {
                obj.optBoolean("allowModDistribution")
            } else {
                null
            }

            return CurseForgeMod(
                id = obj.optLong("id"),
                name = obj.optString("name", "Unnamed Mod"),
                slug = obj.optString("slug").takeIf { it.isNotBlank() && it != "null" },
                summary = obj.optString("summary", ""),
                downloadCount = obj.optLong("downloadCount", 0L),
                authors = authors,
                categories = categories,
                logo = obj.optJSONObject("logo")?.let { parseAsset(it) },
                screenshots = screenshots,
                links = links,
                allowModDistribution = allowDistribution,
                dateModifiedIso = obj.optString("dateModified").takeIf { it.isNotBlank() && it != "null" },
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
            return CurseForgeFile(
                id = obj.optLong("id"),
                modId = obj.optLong("modId"),
                displayName = obj.optString("displayName").ifBlank { obj.optString("fileName", "Unknown File") },
                fileName = obj.optString("fileName", "mod.jar"),
                releaseType = CurseForgeReleaseType.fromApiId(obj.optInt("releaseType", 1)),
                isAvailable = obj.optBoolean("isAvailable", true),
                isServerPack = obj.optBoolean("isServerPack", false),
                fileDateIso = obj.optString("fileDate").takeIf { it.isNotBlank() && it != "null" },
                fileLengthBytes = obj.optLong("fileLength", 0L),
                downloadUrl = obj.optString("downloadUrl").takeIf { it.isNotBlank() && it != "null" },
                hashes = hashes,
                gameVersions = gameVersions,
                dependencies = deps
            )
        }

        private fun parseCategory(obj: JSONObject): CurseForgeCategory = CurseForgeCategory(
            id = obj.optInt("id"),
            name = obj.optString("name", "Category"),
            slug = obj.optString("slug").takeIf { it.isNotBlank() && it != "null" },
            iconUrl = obj.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" },
            parentCategoryId = if (obj.isNull("parentCategoryId")) null else obj.optInt("parentCategoryId"),
            classId = if (obj.isNull("classId")) null else obj.optInt("classId")
        )

        private fun parseAsset(obj: JSONObject): CurseForgeAsset = CurseForgeAsset(
            id = obj.optLong("id"),
            title = obj.optString("title").takeIf { it.isNotBlank() && it != "null" },
            description = obj.optString("description").takeIf { it.isNotBlank() && it != "null" },
            thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
            url = obj.optString("url").takeIf { it.isNotBlank() && it != "null" }
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
