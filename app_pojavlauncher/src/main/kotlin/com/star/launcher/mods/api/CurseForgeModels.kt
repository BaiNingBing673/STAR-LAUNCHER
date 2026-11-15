package com.star.launcher.mods.api

import com.star.launcher.data.db.model.ModLoaderType

enum class CurseForgeModLoader(val apiId: Int, val displayName: String) {
    ANY(0, "Any Loader"),
    FORGE(1, "Forge"),
    CAULDRON(2, "Cauldron"),
    LITELOADER(3, "LiteLoader"),
    FABRIC(4, "Fabric"),
    QUILT(5, "Quilt"),
    NEOFORGE(6, "NeoForge");

    companion object {
        val selectableFilters: List<CurseForgeModLoader> =
            listOf(ANY, FORGE, NEOFORGE, FABRIC, QUILT)

        fun fromApiId(id: Int?): CurseForgeModLoader? =
            entries.firstOrNull { it.apiId == id }

        fun fromStarLoader(type: ModLoaderType): CurseForgeModLoader = when (type) {
            ModLoaderType.FORGE -> FORGE
            ModLoaderType.NEOFORGE -> NEOFORGE
            ModLoaderType.FABRIC -> FABRIC
            ModLoaderType.QUILT -> QUILT
            ModLoaderType.VANILLA, ModLoaderType.LWJGL3IFY, ModLoaderType.OTHER -> ANY
        }

        fun fromGameVersionTag(tag: String): CurseForgeModLoader? = when (tag.trim().lowercase()) {
            "forge" -> FORGE
            "neoforge" -> NEOFORGE
            "fabric" -> FABRIC
            "quilt" -> QUILT
            "cauldron" -> CAULDRON
            "liteloader" -> LITELOADER
            else -> null
        }
    }
}

enum class CurseForgeSortField(val apiId: Int, val displayName: String) {
    FEATURED(1, "Featured"),
    POPULARITY(2, "Popular"),
    LAST_UPDATED(3, "Recently Updated"),
    NAME(4, "Name"),
    TOTAL_DOWNLOADS(6, "Most Downloaded")
}

enum class CurseForgeReleaseType(val apiId: Int, val displayName: String) {
    RELEASE(1, "Release"),
    BETA(2, "Beta"),
    ALPHA(3, "Alpha");

    companion object {
        fun fromApiId(id: Int): CurseForgeReleaseType =
            entries.firstOrNull { it.apiId == id } ?: RELEASE
    }
}

enum class CurseForgeRelationType(val apiId: Int, val displayName: String) {
    EMBEDDED_LIBRARY(1, "Embedded Library"),
    OPTIONAL_DEPENDENCY(2, "Optional"),
    REQUIRED_DEPENDENCY(3, "Required"),
    TOOL(4, "Tool"),
    INCOMPATIBLE(5, "Incompatible"),
    INCLUDE(6, "Included");

    companion object {
        fun fromApiId(id: Int): CurseForgeRelationType =
            entries.firstOrNull { it.apiId == id } ?: OPTIONAL_DEPENDENCY
    }
}

enum class CurseForgeHashAlgorithm(val apiId: Int) {
    SHA1(1),
    MD5(2);

    companion object {
        fun fromApiId(id: Int): CurseForgeHashAlgorithm? =
            entries.firstOrNull { it.apiId == id }
    }
}

data class CurseForgeAuthor(
    val id: Long,
    val name: String,
    val url: String?
)

data class CurseForgeCategory(
    val id: Int,
    val name: String,
    val slug: String?,
    val iconUrl: String?,
    val parentCategoryId: Int?,
    val classId: Int?
)

data class CurseForgeAsset(
    val id: Long,
    val title: String?,
    val description: String?,
    val thumbnailUrl: String?,
    val url: String?
)

data class CurseForgeLinks(
    val websiteUrl: String?,
    val wikiUrl: String?,
    val issuesUrl: String?,
    val sourceUrl: String?
)

data class CurseForgeFileHash(
    val value: String,
    val algorithm: CurseForgeHashAlgorithm?
)

data class CurseForgeFileDependency(
    val modId: Long,
    val relationType: CurseForgeRelationType
)

data class CurseForgeFile(
    val id: Long,
    val modId: Long,
    val displayName: String,
    val fileName: String,
    val releaseType: CurseForgeReleaseType,
    val isAvailable: Boolean,
    val isServerPack: Boolean,
    val fileDateIso: String?,
    val fileLengthBytes: Long,
    val downloadUrl: String?,
    val hashes: List<CurseForgeFileHash>,
    val gameVersions: List<String>,
    val dependencies: List<CurseForgeFileDependency>
) {
    val sha1: String?
        get() = hashes.firstOrNull { it.algorithm == CurseForgeHashAlgorithm.SHA1 }?.value
            ?.lowercase()?.takeIf { it.isNotBlank() }

    val md5: String?
        get() = hashes.firstOrNull { it.algorithm == CurseForgeHashAlgorithm.MD5 }?.value
            ?.lowercase()?.takeIf { it.isNotBlank() }

    val declaredLoaders: Set<CurseForgeModLoader>
        get() = gameVersions.mapNotNull { CurseForgeModLoader.fromGameVersionTag(it) }.toSet()

    val declaredMinecraftVersions: List<String>
        get() = gameVersions.filter { MC_VERSION_REGEX.matches(it.trim()) }

    companion object {
        private val MC_VERSION_REGEX = Regex("""^\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9._-]+)?$""")
    }
}

data class CurseForgeMod(
    val id: Long,
    val name: String,
    val slug: String?,
    val summary: String,
    val downloadCount: Long,
    val authors: List<CurseForgeAuthor>,
    val categories: List<CurseForgeCategory>,
    val logo: CurseForgeAsset?,
    val screenshots: List<CurseForgeAsset>,
    val links: CurseForgeLinks,
    val allowModDistribution: Boolean?,
    val dateModifiedIso: String?,
    val latestFiles: List<CurseForgeFile>,
    val supportedMinecraftVersionsFromLatest: List<String>,
    val supportedLoadersFromLatest: Set<CurseForgeModLoader>
) {
    val primaryAuthor: String
        get() = authors.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Unknown author"
}

data class CurseForgePagination(
    val index: Int,
    val pageSize: Int,
    val resultCount: Int,
    val totalCount: Long
) {
    val hasMore: Boolean
        get() = (index + resultCount) < totalCount && resultCount > 0
}

data class CurseForgeSearchQuery(
    val searchFilter: String = "",
    val gameVersion: String? = null,
    val modLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
    val categoryId: Int? = null,
    val sortField: CurseForgeSortField = CurseForgeSortField.POPULARITY,
    val sortOrderDesc: Boolean = true,
    val index: Int = 0,
    val pageSize: Int = 20
)

data class CurseForgeSearchResponse(
    val mods: List<CurseForgeMod>,
    val pagination: CurseForgePagination
)

data class CurseForgeFilesResponse(
    val files: List<CurseForgeFile>,
    val pagination: CurseForgePagination
)

data class CurseForgeMinecraftVersion(
    val id: Int,
    val versionString: String,
    val approved: Boolean
)

data class CurseForgeModLoaderIndex(
    val name: String,
    val gameVersion: String,
    val latest: Boolean,
    val recommended: Boolean,
    val type: CurseForgeModLoader?
)

sealed interface CurseForgeDownloadUrlResult {
    data class Available(val url: String) : CurseForgeDownloadUrlResult
    data class Restricted(
        val reason: String,
        val websiteUrl: String?
    ) : CurseForgeDownloadUrlResult
}

enum class CurseForgeConnectionState(val displayLabel: String) {
    CONNECTED("Connected"),
    CONFIGURATION_REQUIRED("Configuration Required"),
    CONNECTION_FAILED("Connection Failed"),
    CHECKING("Checking...")
}

enum class CurseForgeErrorCategory {
    NONE,
    MISSING_CONFIGURATION,
    INVALID_API_KEY,
    RATE_LIMITED,
    NETWORK_FAILURE,
    SERVICE_ERROR
}

data class CurseForgeConnectionTestResult(
    val state: CurseForgeConnectionState,
    val credentialStatus: CurseForgeCredentialStatus,
    val errorCategory: CurseForgeErrorCategory = CurseForgeErrorCategory.NONE,
    val message: String,
    val latencyMs: Long = 0L
)
