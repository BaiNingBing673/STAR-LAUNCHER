package com.star.launcher.mods.compat

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.version.VersionManager
import java.io.File

enum class EnvironmentDetectionSource {
    MANIFEST_VERIFIED,
    VERSION_ID_INFERRED,
    UNKNOWN
}

data class InstanceEnvironment(
    val instanceId: String,
    val instanceName: String,
    val gameDirPath: String,
    val rawVersionId: String?,
    val baseMinecraftVersion: String?,
    val loaderType: ModLoaderType,
    val loaderVersion: String?,
    val detectionSource: EnvironmentDetectionSource
) {
    val curseForgeLoader: CurseForgeModLoader
        get() = CurseForgeModLoader.fromStarLoader(loaderType)

    val isModdedLoader: Boolean
        get() = loaderType in setOf(
            ModLoaderType.FORGE,
            ModLoaderType.NEOFORGE,
            ModLoaderType.FABRIC,
            ModLoaderType.QUILT
        )
}

/**
 * Resolves the effective Minecraft version and mod loader for a [GameInstanceEntity],
 * clearly distinguishing manifest-verified metadata from `versionId` inference.
 */
class InstanceEnvironmentResolver(
    private val gameVersionDao: GameVersionDao,
    private val storageManager: AetherStorageManager
) {
    suspend fun resolve(instance: GameInstanceEntity): InstanceEnvironment {
        val rawVersionId = instance.versionId?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("unknown", ignoreCase = true)
        }
        if (rawVersionId == null) {
            return InstanceEnvironment(
                instanceId = instance.instanceId,
                instanceName = instance.name,
                gameDirPath = instance.gameDirPath,
                rawVersionId = null,
                baseMinecraftVersion = null,
                loaderType = ModLoaderType.VANILLA,
                loaderVersion = null,
                detectionSource = EnvironmentDetectionSource.UNKNOWN
            )
        }

        // 1. Prefer on-disk manifest if readable (authoritative)
        val manifestFile = File(storageManager.versionsRoot(), "$rawVersionId/$rawVersionId.json")
        if (manifestFile.isFile && manifestFile.length() > 0L) {
            val parsed = runCatching {
                VersionManager.parseManifestJson(manifestFile.readText(), rawVersionId)
            }.getOrNull()
            if (parsed != null) {
                val baseMc = resolveBaseMinecraftVersion(
                    versionId = parsed.versionId,
                    inheritsFrom = parsed.inheritsFrom,
                    loaderType = parsed.modLoaderType,
                    loaderVersion = parsed.modLoaderVersion
                )
                return InstanceEnvironment(
                    instanceId = instance.instanceId,
                    instanceName = instance.name,
                    gameDirPath = instance.gameDirPath,
                    rawVersionId = rawVersionId,
                    baseMinecraftVersion = baseMc,
                    loaderType = parsed.modLoaderType,
                    loaderVersion = parsed.modLoaderVersion,
                    detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
                )
            }
        }

        // 2. Check Room catalog entry (if scanned or synced)
        val dbVersion: GameVersionEntity? = gameVersionDao.getById(rawVersionId)
        if (dbVersion != null && (dbVersion.isInstalled || dbVersion.modLoaderType != ModLoaderType.VANILLA || dbVersion.inheritsFrom != null)) {
            val baseMc = resolveBaseMinecraftVersion(
                versionId = dbVersion.versionId,
                inheritsFrom = dbVersion.inheritsFrom,
                loaderType = dbVersion.modLoaderType,
                loaderVersion = dbVersion.modLoaderVersion
            )
            return InstanceEnvironment(
                instanceId = instance.instanceId,
                instanceName = instance.name,
                gameDirPath = instance.gameDirPath,
                rawVersionId = rawVersionId,
                baseMinecraftVersion = baseMc,
                loaderType = dbVersion.modLoaderType,
                loaderVersion = dbVersion.modLoaderVersion,
                detectionSource = if (dbVersion.isInstalled) {
                    EnvironmentDetectionSource.MANIFEST_VERIFIED
                } else {
                    EnvironmentDetectionSource.VERSION_ID_INFERRED
                }
            )
        }

        // 3. Infer from versionId string patterns as fallback (marked VERSION_ID_INFERRED)
        val inferred = inferFromVersionId(rawVersionId)
        return InstanceEnvironment(
            instanceId = instance.instanceId,
            instanceName = instance.name,
            gameDirPath = instance.gameDirPath,
            rawVersionId = rawVersionId,
            baseMinecraftVersion = inferred.first,
            loaderType = inferred.second,
            loaderVersion = inferred.third,
            detectionSource = EnvironmentDetectionSource.VERSION_ID_INFERRED
        )
    }

    companion object {
        private val FABRIC_ID_REGEX = Regex("""^fabric-loader-([^-]+)-(.+)$""", RegexOption.IGNORE_CASE)
        private val QUILT_ID_REGEX = Regex("""^quilt-loader-([^-]+)-(.+)$""", RegexOption.IGNORE_CASE)
        private val FORGE_ID_REGEX = Regex("""^(\d+\.\d+(?:\.\d+)?)-forge-(.+)$""", RegexOption.IGNORE_CASE)
        private val NEOFORGE_ID_REGEX = Regex("""^neoforge-(.+)$""", RegexOption.IGNORE_CASE)
        private val MC_VERSION_REGEX = Regex("""(\d+\.\d+(?:\.\d+)?)""")

        fun resolveBaseMinecraftVersion(
            versionId: String,
            inheritsFrom: String?,
            loaderType: ModLoaderType,
            loaderVersion: String?
        ): String? {
            if (!inheritsFrom.isNullOrBlank()) return inheritsFrom.trim()
            if (loaderType == ModLoaderType.VANILLA) {
                return versionId.takeIf { it.isNotBlank() }
            }
            return inferFromVersionId(versionId).first
                ?: loaderVersion?.let { inferMcFromNeoForgeVersion(it) }
        }

        fun inferFromVersionId(versionId: String): Triple<String?, ModLoaderType, String?> {
            FABRIC_ID_REGEX.matchEntire(versionId)?.let { m ->
                return Triple(m.groupValues[2], ModLoaderType.FABRIC, m.groupValues[1])
            }
            QUILT_ID_REGEX.matchEntire(versionId)?.let { m ->
                return Triple(m.groupValues[2], ModLoaderType.QUILT, m.groupValues[1])
            }
            FORGE_ID_REGEX.matchEntire(versionId)?.let { m ->
                return Triple(m.groupValues[1], ModLoaderType.FORGE, m.groupValues[2])
            }
            NEOFORGE_ID_REGEX.matchEntire(versionId)?.let { m ->
                val loaderVer = m.groupValues[1]
                return Triple(inferMcFromNeoForgeVersion(loaderVer), ModLoaderType.NEOFORGE, loaderVer)
            }
            val lower = versionId.lowercase()
            val loader = when {
                lower.contains("neoforge") -> ModLoaderType.NEOFORGE
                lower.contains("quilt") -> ModLoaderType.QUILT
                lower.contains("fabric") -> ModLoaderType.FABRIC
                lower.contains("forge") -> ModLoaderType.FORGE
                else -> ModLoaderType.VANILLA
            }
            val mcMatch = MC_VERSION_REGEX.find(versionId)?.groupValues?.get(1)
            return Triple(mcMatch ?: versionId.takeIf { loader == ModLoaderType.VANILLA }, loader, null)
        }

        /**
         * NeoForge versioning encodes MC minor.patch in its major.minor (e.g. 21.1.72 -> 1.21.1, 20.4.237 -> 1.20.4).
         */
        internal fun inferMcFromNeoForgeVersion(loaderVersion: String): String? {
            val parts = loaderVersion.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            if (major < 20) return null
            return if (minor == 0) "1.$major" else "1.$major.$minor"
        }
    }
}
