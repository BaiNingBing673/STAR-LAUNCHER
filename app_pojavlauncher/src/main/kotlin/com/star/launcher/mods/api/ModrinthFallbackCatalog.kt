package com.star.launcher.mods.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Keyless public mod catalog fallback backed by Modrinth API v2 (`https://api.modrinth.com/v2`).
 *
 * Ensures release builds and end-user installations work out-of-the-box without requiring
 * users to obtain a personal CurseForge Developer API key, and transparently recovers when
 * a stored CurseForge key is missing or rejected (HTTP 401 / 403).
 */
class ModrinthFallbackCatalog(
    private val transport: CurseForgeTransport
) {
    private val modIdToProjectId = ConcurrentHashMap<Long, String>()
    private val fileIdToVersionId = ConcurrentHashMap<Long, String>()
    private val categoryIdToSlug = ConcurrentHashMap<Int, String>()

    init {
        DEFAULT_CATEGORIES.forEach { (id, slug, _) ->
            categoryIdToSlug[id] = slug
        }
    }

    fun isFallbackModId(modId: Long): Boolean =
        modId >= FALLBACK_ID_BASE || modIdToProjectId.containsKey(modId)

    fun registerProject(projectIdOrSlug: String): Long {
        val clean = projectIdOrSlug.trim()
        val syntheticId = syntheticIdFor(clean)
        modIdToProjectId.putIfAbsent(syntheticId, clean)
        return syntheticId
    }

    fun registerVersion(versionId: String): Long {
        val clean = versionId.trim()
        val syntheticId = syntheticIdFor(clean)
        fileIdToVersionId.putIfAbsent(syntheticId, clean)
        return syntheticId
    }

    suspend fun searchMods(query: CurseForgeSearchQuery): CurseForgeSearchResponse {
        val primary = executeSearch(
            query = query,
            includeGameVersion = !query.gameVersion.isNullOrBlank(),
            includeModLoader = query.modLoader != CurseForgeModLoader.ANY
        )
        if (primary.mods.isNotEmpty() || query.index > 0) {
            return primary
        }
        if (!query.gameVersion.isNullOrBlank()) {
            val withoutVersion = executeSearch(
                query = query,
                includeGameVersion = false,
                includeModLoader = query.modLoader != CurseForgeModLoader.ANY
            )
            if (withoutVersion.mods.isNotEmpty()) {
                return withoutVersion
            }
        }
        if (query.modLoader != CurseForgeModLoader.ANY) {
            return executeSearch(
                query = query,
                includeGameVersion = false,
                includeModLoader = false
            )
        }
        return primary
    }

    private suspend fun executeSearch(
        query: CurseForgeSearchQuery,
        includeGameVersion: Boolean,
        includeModLoader: Boolean
    ): CurseForgeSearchResponse {
        val facets = mutableListOf("""["project_type:mod"]""")
        if (includeGameVersion && !query.gameVersion.isNullOrBlank()) {
            facets.add("""["versions:${escapeJson(query.gameVersion.trim())}"]""")
        }
        val loaderSlug = if (includeModLoader) loaderToModrinthSlug(query.modLoader) else null
        if (loaderSlug != null) {
            facets.add("""["categories:$loaderSlug"]""")
        }
        if (query.categoryId != null && query.categoryId > 0) {
            val catSlug = categoryIdToSlug[query.categoryId]
            if (!catSlug.isNullOrBlank()) {
                facets.add("""["categories:${escapeJson(catSlug)}"]""")
            }
        }

        val indexSort = when {
            query.searchFilter.isNotBlank() && query.sortField == CurseForgeSortField.FEATURED -> "relevance"
            query.sortField == CurseForgeSortField.FEATURED -> "follows"
            query.sortField == CurseForgeSortField.POPULARITY -> "downloads"
            query.sortField == CurseForgeSortField.TOTAL_DOWNLOADS -> "downloads"
            query.sortField == CurseForgeSortField.LAST_UPDATED -> "updated"
            query.sortField == CurseForgeSortField.NAME -> "relevance"
            else -> "downloads"
        }

        val offset = query.index.coerceAtLeast(0)
        val limit = query.pageSize.coerceIn(1, 50)
        val params = LinkedHashMap<String, String>()
        if (query.searchFilter.isNotBlank()) {
            params["query"] = query.searchFilter.trim()
        }
        params["facets"] = "[${facets.joinToString(",")}]"
        params["index"] = indexSort
        params["offset"] = offset.toString()
        params["limit"] = limit.toString()

        val url = CurseForgeUrlBuilder.buildUrl(MODRINTH_BASE_URL, "/v2/search", params)
        val response = transport.get(url, emptyMap())
        if (response.statusCode !in 200..299) {
            throw mapModrinthError(response, "/v2/search")
        }

        val root = runCatching { JSONObject(response.body) }.getOrElse { err ->
            throw CurseForgeApiException.ParseError(err.message ?: "Invalid Modrinth search JSON")
        }
        val hits = root.optJSONArray("hits") ?: JSONArray()
        val mods = buildList {
            for (i in 0 until hits.length()) {
                val hit = hits.optJSONObject(i) ?: continue
                add(parseSearchHit(hit))
            }
        }
        val totalHits = root.optLong("total_hits", mods.size.toLong())
        return CurseForgeSearchResponse(
            mods = mods,
            pagination = CurseForgePagination(
                index = root.optInt("offset", offset),
                pageSize = root.optInt("limit", limit),
                resultCount = mods.size,
                totalCount = totalHits
            )
        )
    }

    suspend fun getMod(modId: Long): CurseForgeMod {
        val projectKey = modIdToProjectId[modId]
            ?: throw CurseForgeApiException.NotFound("/v2/project/$modId")
        val url = CurseForgeUrlBuilder.buildUrl(MODRINTH_BASE_URL, "/v2/project/$projectKey", emptyMap())
        val response = transport.get(url, emptyMap())
        if (response.statusCode !in 200..299) {
            throw mapModrinthError(response, "/v2/project/$projectKey")
        }
        val obj = runCatching { JSONObject(response.body) }.getOrElse { err ->
            throw CurseForgeApiException.ParseError(err.message ?: "Invalid Modrinth project JSON")
        }
        val filesResp = runCatching {
            getModFiles(modId = modId, pageSize = 20)
        }.getOrNull()
        return parseProjectDetail(obj, modId, filesResp?.files.orEmpty())
    }

    suspend fun getModDescription(modId: Long): String {
        val projectKey = modIdToProjectId[modId]
            ?: throw CurseForgeApiException.NotFound("/v2/project/$modId")
        val url = CurseForgeUrlBuilder.buildUrl(MODRINTH_BASE_URL, "/v2/project/$projectKey", emptyMap())
        val response = transport.get(url, emptyMap())
        if (response.statusCode !in 200..299) {
            throw mapModrinthError(response, "/v2/project/$projectKey")
        }
        val obj = runCatching { JSONObject(response.body) }.getOrElse { err ->
            throw CurseForgeApiException.ParseError(err.message ?: "Invalid Modrinth project JSON")
        }
        val rawBody = obj.optCleanString("body") ?: obj.optCleanString("description") ?: ""
        return CurseForgeRepository.sanitizeHtmlDescription(rawBody)
    }

    suspend fun getModFiles(
        modId: Long,
        gameVersion: String? = null,
        modLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
        index: Int = 0,
        pageSize: Int = 50
    ): CurseForgeFilesResponse {
        val projectKey = modIdToProjectId[modId]
            ?: throw CurseForgeApiException.NotFound("/v2/project/$modId/version")
        val params = LinkedHashMap<String, String>()
        if (!gameVersion.isNullOrBlank()) {
            params["game_versions"] = """["${escapeJson(gameVersion.trim())}"]"""
        }
        val loaderSlug = loaderToModrinthSlug(modLoader)
        if (loaderSlug != null) {
            params["loaders"] = """["$loaderSlug"]"""
        }

        val url = CurseForgeUrlBuilder.buildUrl(MODRINTH_BASE_URL, "/v2/project/$projectKey/version", params)
        val response = transport.get(url, emptyMap())
        if (response.statusCode !in 200..299) {
            throw mapModrinthError(response, "/v2/project/$projectKey/version")
        }
        val arr = runCatching { JSONArray(response.body) }.getOrElse { err ->
            throw CurseForgeApiException.ParseError(err.message ?: "Invalid Modrinth versions JSON")
        }
        val allFiles = buildList {
            for (i in 0 until arr.length()) {
                val verObj = arr.optJSONObject(i) ?: continue
                parseVersionToFile(verObj, modId)?.let { add(it) }
            }
        }
        val safeIndex = index.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, 50)
        val paged = allFiles.drop(safeIndex).take(safePageSize)
        return CurseForgeFilesResponse(
            files = paged,
            pagination = CurseForgePagination(
                index = safeIndex,
                pageSize = safePageSize,
                resultCount = paged.size,
                totalCount = allFiles.size.toLong()
            )
        )
    }

    suspend fun getModFile(modId: Long, fileId: Long): CurseForgeFile {
        val versionId = fileIdToVersionId[fileId]
        if (versionId != null) {
            val url = CurseForgeUrlBuilder.buildUrl(MODRINTH_BASE_URL, "/v2/version/$versionId", emptyMap())
            val response = transport.get(url, emptyMap())
            if (response.statusCode in 200..299) {
                val obj = runCatching { JSONObject(response.body) }.getOrNull()
                if (obj != null) {
                    parseVersionToFile(obj, modId)?.let { return it }
                }
            }
        }
        val files = getModFiles(modId = modId, pageSize = 50).files
        return files.firstOrNull { it.id == fileId } ?: files.firstOrNull()
            ?: throw CurseForgeApiException.NotFound("/v2/version/$fileId")
    }

    suspend fun resolveDownloadUrl(mod: CurseForgeMod?, file: CurseForgeFile): CurseForgeDownloadUrlResult {
        val direct = file.downloadUrl?.takeIf { it.isNotBlank() && it != "null" }
        if (direct != null) {
            return CurseForgeDownloadUrlResult.Available(direct)
        }
        val refreshed = runCatching { getModFile(file.modId, file.id) }.getOrNull()
        val refreshedUrl = refreshed?.downloadUrl?.takeIf { it.isNotBlank() && it != "null" }
        return if (refreshedUrl != null) {
            CurseForgeDownloadUrlResult.Available(refreshedUrl)
        } else {
            CurseForgeDownloadUrlResult.Restricted(
                reason = "No direct download URL was found for '${file.fileName}'.",
                websiteUrl = mod?.links?.websiteUrl
            )
        }
    }

    fun getDefaultCategories(): List<CurseForgeCategory> =
        DEFAULT_CATEGORIES.map { (id, slug, name) ->
            CurseForgeCategory(
                id = id,
                name = name,
                slug = slug,
                iconUrl = null,
                parentCategoryId = null,
                classId = CurseForgeCredentialProvider.MODS_CLASS_ID
            )
        }

    fun getDefaultMinecraftVersions(): List<CurseForgeMinecraftVersion> =
        DEFAULT_MC_VERSIONS.mapIndexed { idx, ver ->
            CurseForgeMinecraftVersion(id = idx + 1, versionString = ver, approved = true)
        }

    fun getDefaultModLoaders(mcVersion: String?): List<CurseForgeModLoaderIndex> {
        val ver = mcVersion?.takeIf { it.isNotBlank() } ?: "1.21.1"
        return listOf(
            CurseForgeModLoaderIndex("fabric-$ver", ver, latest = true, recommended = true, type = CurseForgeModLoader.FABRIC),
            CurseForgeModLoaderIndex("forge-$ver", ver, latest = true, recommended = true, type = CurseForgeModLoader.FORGE),
            CurseForgeModLoaderIndex("neoforge-$ver", ver, latest = true, recommended = true, type = CurseForgeModLoader.NEOFORGE),
            CurseForgeModLoaderIndex("quilt-$ver", ver, latest = true, recommended = false, type = CurseForgeModLoader.QUILT)
        )
    }

    private fun parseSearchHit(hit: JSONObject): CurseForgeMod {
        val rawProjectId = hit.optCleanString("project_id")
            ?: hit.optCleanString("slug")
            ?: hit.optCleanString("title")
            ?: "unknown"
        val slug = hit.optCleanString("slug") ?: rawProjectId
        val modId = registerProject(rawProjectId)
        slug.let { modIdToProjectId.putIfAbsent(syntheticIdFor(it), rawProjectId) }

        val title = hit.optCleanString("title") ?: slug
        val description = hit.optCleanString("description") ?: ""
        val authorName = hit.optCleanString("author") ?: "Modrinth Creator"
        val iconUrl = hit.optCleanString("icon_url")

        val versionsArr = hit.optJSONArray("versions") ?: JSONArray()
        val mcVersions = buildList {
            for (i in 0 until versionsArr.length()) {
                val v = versionsArr.optString(i).trim()
                if (v.isNotEmpty() && v != "null") add(v)
            }
        }

        val categoriesArr = hit.optJSONArray("categories") ?: JSONArray()
        val displayCatsArr = hit.optJSONArray("display_categories") ?: categoriesArr
        val loaders = linkedSetOf<CurseForgeModLoader>()
        val categories = mutableListOf<CurseForgeCategory>()

        for (i in 0 until categoriesArr.length()) {
            val tag = categoriesArr.optString(i).trim()
            CurseForgeModLoader.fromGameVersionTag(tag)?.let { loaders.add(it) }
        }
        for (i in 0 until displayCatsArr.length()) {
            val tag = displayCatsArr.optString(i).trim()
            if (tag.isEmpty() || CurseForgeModLoader.fromGameVersionTag(tag) != null) continue
            categories.add(mapTagToCategory(tag))
        }

        val galleryArr = hit.optJSONArray("gallery") ?: JSONArray()
        val screenshots = buildList {
            for (i in 0 until galleryArr.length()) {
                val url = galleryArr.optString(i).trim()
                if (url.isNotEmpty() && url != "null") {
                    add(CurseForgeAsset(id = i.toLong() + 1L, title = null, description = null, thumbnailUrl = url, url = url))
                }
            }
        }

        return CurseForgeMod(
            id = modId,
            name = title,
            slug = slug,
            summary = description,
            downloadCount = hit.optLong("downloads", 0L),
            authors = listOf(
                CurseForgeAuthor(
                    id = modId,
                    name = authorName,
                    url = "https://modrinth.com/mod/$slug"
                )
            ),
            categories = categories,
            logo = iconUrl?.let {
                CurseForgeAsset(id = modId, title = title, description = null, thumbnailUrl = it, url = it)
            },
            screenshots = screenshots,
            links = CurseForgeLinks(
                websiteUrl = "https://modrinth.com/mod/$slug",
                wikiUrl = null,
                issuesUrl = null,
                sourceUrl = null
            ),
            allowModDistribution = true,
            dateModifiedIso = hit.optCleanString("date_modified"),
            latestFiles = emptyList(),
            supportedMinecraftVersionsFromLatest = mcVersions,
            supportedLoadersFromLatest = loaders
        )
    }

    private fun parseProjectDetail(
        obj: JSONObject,
        modId: Long,
        latestFiles: List<CurseForgeFile>
    ): CurseForgeMod {
        val rawId = obj.optCleanString("id") ?: modIdToProjectId[modId] ?: modId.toString()
        val slug = obj.optCleanString("slug") ?: rawId
        modIdToProjectId[modId] = rawId
        val title = obj.optCleanString("title") ?: slug
        val description = obj.optCleanString("description") ?: ""
        val iconUrl = obj.optCleanString("icon_url")

        val gvArr = obj.optJSONArray("game_versions") ?: JSONArray()
        val mcVersions = buildList {
            for (i in 0 until gvArr.length()) {
                val v = gvArr.optString(i).trim()
                if (v.isNotEmpty() && v != "null") add(v)
            }
        }

        val loadersArr = obj.optJSONArray("loaders") ?: JSONArray()
        val loaders = linkedSetOf<CurseForgeModLoader>()
        for (i in 0 until loadersArr.length()) {
            val l = loadersArr.optString(i).trim()
            CurseForgeModLoader.fromGameVersionTag(l)?.let { loaders.add(it) }
        }
        latestFiles.forEach { file ->
            loaders.addAll(file.declaredLoaders)
        }

        val catsArr = obj.optJSONArray("categories") ?: JSONArray()
        val categories = buildList {
            for (i in 0 until catsArr.length()) {
                val tag = catsArr.optString(i).trim()
                if (tag.isNotEmpty() && CurseForgeModLoader.fromGameVersionTag(tag) == null) {
                    add(mapTagToCategory(tag))
                }
            }
        }

        val galleryArr = obj.optJSONArray("gallery") ?: JSONArray()
        val screenshots = buildList {
            for (i in 0 until galleryArr.length()) {
                val g = galleryArr.optJSONObject(i)
                val url = g?.optCleanString("url") ?: galleryArr.optString(i).trim().takeIf { it.startsWith("http") }
                if (!url.isNullOrBlank()) {
                    add(
                        CurseForgeAsset(
                            id = i.toLong() + 1L,
                            title = g?.optCleanString("title"),
                            description = g?.optCleanString("description"),
                            thumbnailUrl = g?.optCleanString("raw_url") ?: url,
                            url = url
                        )
                    )
                }
            }
        }

        return CurseForgeMod(
            id = modId,
            name = title,
            slug = slug,
            summary = description,
            downloadCount = obj.optLong("downloads", 0L),
            authors = listOf(
                CurseForgeAuthor(
                    id = modId,
                    name = "Modrinth Verified",
                    url = "https://modrinth.com/mod/$slug"
                )
            ),
            categories = categories,
            logo = iconUrl?.let {
                CurseForgeAsset(id = modId, title = title, description = null, thumbnailUrl = it, url = it)
            },
            screenshots = screenshots,
            links = CurseForgeLinks(
                websiteUrl = "https://modrinth.com/mod/$slug",
                wikiUrl = obj.optCleanString("wiki_url"),
                issuesUrl = obj.optCleanString("issues_url"),
                sourceUrl = obj.optCleanString("source_url")
            ),
            allowModDistribution = true,
            dateModifiedIso = obj.optCleanString("updated"),
            latestFiles = latestFiles,
            supportedMinecraftVersionsFromLatest = if (mcVersions.isNotEmpty()) mcVersions else latestFiles.flatMap { it.declaredMinecraftVersions }.distinct(),
            supportedLoadersFromLatest = loaders
        )
    }

    private fun parseVersionToFile(verObj: JSONObject, modId: Long): CurseForgeFile? {
        val rawVersionId = verObj.optCleanString("id") ?: return null
        val fileId = registerVersion(rawVersionId)
        val projectId = verObj.optCleanString("project_id")
        val resolvedModId = if (projectId != null) registerProject(projectId) else modId

        val filesArr = verObj.optJSONArray("files") ?: JSONArray()
        if (filesArr.length() == 0) return null

        var primaryFile = filesArr.optJSONObject(0)
        for (i in 0 until filesArr.length()) {
            val candidate = filesArr.optJSONObject(i) ?: continue
            if (candidate.optBoolean("primary", false)) {
                primaryFile = candidate
                break
            }
        }
        val fileObj = primaryFile ?: return null
        val fileName = fileObj.optCleanString("filename") ?: "mod-$fileId.jar"
        val displayName = verObj.optCleanString("name") ?: fileName
        val downloadUrl = fileObj.optCleanString("url")
        val fileSize = fileObj.optLong("size", 0L)

        val hashesObj = fileObj.optJSONObject("hashes")
        val hashes = buildList {
            hashesObj?.optCleanString("sha1")?.let {
                add(CurseForgeFileHash(value = it, algorithm = CurseForgeHashAlgorithm.SHA1))
            }
        }

        val gvTags = mutableListOf<String>()
        val gvArr = verObj.optJSONArray("game_versions") ?: JSONArray()
        for (i in 0 until gvArr.length()) {
            val gv = gvArr.optString(i).trim()
            if (gv.isNotEmpty() && gv != "null") gvTags.add(gv)
        }
        val loadersArr = verObj.optJSONArray("loaders") ?: JSONArray()
        for (i in 0 until loadersArr.length()) {
            val loader = loadersArr.optString(i).trim()
            val mapped = CurseForgeModLoader.fromGameVersionTag(loader)
            if (mapped != null && mapped != CurseForgeModLoader.ANY) {
                gvTags.add(mapped.displayName)
            }
        }

        val releaseType = when (verObj.optCleanString("version_type")?.lowercase()) {
            "alpha" -> CurseForgeReleaseType.ALPHA
            "beta" -> CurseForgeReleaseType.BETA
            else -> CurseForgeReleaseType.RELEASE
        }

        val depsArr = verObj.optJSONArray("dependencies") ?: JSONArray()
        val dependencies = buildList {
            for (i in 0 until depsArr.length()) {
                val d = depsArr.optJSONObject(i) ?: continue
                val depProjectId = d.optCleanString("project_id") ?: continue
                val depModId = registerProject(depProjectId)
                val rel = when (d.optCleanString("dependency_type")?.lowercase()) {
                    "required" -> CurseForgeRelationType.REQUIRED_DEPENDENCY
                    "optional" -> CurseForgeRelationType.OPTIONAL_DEPENDENCY
                    "incompatible" -> CurseForgeRelationType.INCOMPATIBLE
                    "embedded" -> CurseForgeRelationType.EMBEDDED_LIBRARY
                    else -> CurseForgeRelationType.OPTIONAL_DEPENDENCY
                }
                add(CurseForgeFileDependency(modId = depModId, relationType = rel))
            }
        }

        return CurseForgeFile(
            id = fileId,
            modId = resolvedModId,
            displayName = displayName,
            fileName = fileName,
            releaseType = releaseType,
            isAvailable = true,
            isServerPack = false,
            fileDateIso = verObj.optCleanString("date_published"),
            fileLengthBytes = fileSize,
            downloadUrl = downloadUrl,
            hashes = hashes,
            gameVersions = gvTags,
            dependencies = dependencies
        )
    }

    private fun mapTagToCategory(slug: String): CurseForgeCategory {
        val clean = slug.trim().lowercase()
        val existing = DEFAULT_CATEGORIES.firstOrNull { it.second == clean }
        val id = existing?.first ?: (clean.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
        val name = existing?.third ?: clean.replace('-', ' ').replaceFirstChar { it.uppercase() }
        categoryIdToSlug.putIfAbsent(id, clean)
        return CurseForgeCategory(
            id = id,
            name = name,
            slug = clean,
            iconUrl = null,
            parentCategoryId = null,
            classId = CurseForgeCredentialProvider.MODS_CLASS_ID
        )
    }

    private fun loaderToModrinthSlug(loader: CurseForgeModLoader): String? = when (loader) {
        CurseForgeModLoader.FORGE -> "forge"
        CurseForgeModLoader.FABRIC -> "fabric"
        CurseForgeModLoader.QUILT -> "quilt"
        CurseForgeModLoader.NEOFORGE -> "neoforge"
        CurseForgeModLoader.LITELOADER -> "liteloader"
        CurseForgeModLoader.ANY, CurseForgeModLoader.CAULDRON -> null
    }

    private fun mapModrinthError(response: CurseForgeHttpResponse, path: String): CurseForgeApiException {
        return when (response.statusCode) {
            404 -> CurseForgeApiException.NotFound(path)
            429 -> CurseForgeApiException.RateLimited(response.headers["retry-after"]?.trim()?.toIntOrNull())
            else -> CurseForgeApiException.ServerError(response.statusCode, "Public mod catalog returned HTTP ${response.statusCode}")
        }
    }

    companion object {
        const val MODRINTH_BASE_URL = "https://api.modrinth.com"
        const val FALLBACK_ID_BASE = 2_000_000_000L

        private val DEFAULT_CATEGORIES = listOf(
            Triple(406, "worldgen", "World Gen"),
            Triple(407, "technology", "Technology"),
            Triple(412, "magic", "Magic"),
            Triple(419, "adventure", "Adventure"),
            Triple(420, "storage", "Storage"),
            Triple(421, "library", "Library & API"),
            Triple(423, "utility", "Utility & QoL"),
            Triple(434, "optimization", "Optimization")
        )

        private val DEFAULT_MC_VERSIONS = listOf(
            "1.21.4",
            "1.21.3",
            "1.21.1",
            "1.21",
            "1.20.6",
            "1.20.4",
            "1.20.2",
            "1.20.1",
            "1.19.4",
            "1.19.2",
            "1.18.2",
            "1.16.5",
            "1.12.2",
            "1.8.9",
            "1.7.10"
        )

        fun syntheticIdFor(raw: String): Long {
            var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
            for (ch in raw) {
                hash = hash xor ch.code.toLong()
                hash *= 1099511628211L
            }
            val positive = (hash and 0x1FFFFFFFFFFFFFFFL)
            return FALLBACK_ID_BASE + positive
        }

        private fun escapeJson(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun JSONObject.optCleanString(key: String): String? {
            if (isNull(key)) return null
            val raw = optString(key, "").trim()
            return raw.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }
    }
}
