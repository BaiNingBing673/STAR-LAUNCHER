package com.star.launcher.mods.compat

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.version.ParsedVersionManifest
import com.star.launcher.version.VersionManager
import org.json.JSONObject
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
        val instanceDir = instance.gameDirPath.takeIf { it.isNotBlank() }?.let { File(it) }

        // Check instance-level loader metadata (.star_loader.json / mmc-pack.json)
        val instanceLoaderMeta = instanceDir?.let { readInstanceDirectoryLoaderMeta(it, rawVersionId) }

        val effectiveVersionId = when {
            rawVersionId == null -> instanceLoaderMeta?.installedVersionId
            instanceLoaderMeta != null &&
                instanceLoaderMeta.installedVersionId.isNotBlank() &&
                (rawVersionId == instanceLoaderMeta.installedVersionId || rawVersionId == instanceLoaderMeta.minecraftVersion) ->
                instanceLoaderMeta.installedVersionId
            else -> rawVersionId
        }

        if (effectiveVersionId == null && instanceLoaderMeta == null) {
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

        val dbVersion: GameVersionEntity? = effectiveVersionId?.let { gameVersionDao.getById(it) }
            ?: rawVersionId?.takeIf { it != effectiveVersionId }?.let { gameVersionDao.getById(it) }

        // 1. Prefer on-disk manifest chain (versionsRoot, custom manifestPath, or instance directory)
        if (effectiveVersionId != null) {
            val chain = readManifestChain(
                startVersionId = effectiveVersionId,
                customManifestPath = dbVersion?.manifestPath,
                instanceDir = instanceDir
            )
            if (chain.isNotEmpty()) {
                val moddedNode = chain.firstOrNull { it.modLoaderType != ModLoaderType.VANILLA }
                val primaryNode = moddedNode ?: chain.first()
                val loaderType = when {
                    primaryNode.modLoaderType != ModLoaderType.VANILLA -> primaryNode.modLoaderType
                    instanceLoaderMeta != null && instanceLoaderMeta.loaderType != ModLoaderType.VANILLA -> instanceLoaderMeta.loaderType
                    else -> ModLoaderType.VANILLA
                }
                val loaderVersion = primaryNode.modLoaderVersion ?: instanceLoaderMeta?.loaderVersion
                val baseMc = chain.firstNotNullOfOrNull { node ->
                    node.detectedMinecraftVersion?.takeIf { MC_VERSION_EXACT_REGEX.matches(it) }
                } ?: chain.lastOrNull()?.let { rootNode ->
                    if (rootNode.modLoaderType == ModLoaderType.VANILLA && rootNode.inheritsFrom == null) {
                        rootNode.versionId
                    } else {
                        null
                    }
                } ?: resolveBaseMinecraftVersion(
                    versionId = primaryNode.versionId,
                    inheritsFrom = primaryNode.inheritsFrom,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion
                ) ?: instanceLoaderMeta?.minecraftVersion

                return InstanceEnvironment(
                    instanceId = instance.instanceId,
                    instanceName = instance.name,
                    gameDirPath = instance.gameDirPath,
                    rawVersionId = effectiveVersionId,
                    baseMinecraftVersion = baseMc,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
                )
            }
        }

        // 2. If instance directory has verified loader metadata (.star_loader.json / mmc-pack.json)
        if (instanceLoaderMeta != null && instanceLoaderMeta.loaderType != ModLoaderType.VANILLA) {
            return InstanceEnvironment(
                instanceId = instance.instanceId,
                instanceName = instance.name,
                gameDirPath = instance.gameDirPath,
                rawVersionId = effectiveVersionId ?: rawVersionId,
                baseMinecraftVersion = instanceLoaderMeta.minecraftVersion ?: rawVersionId?.let { inferFromVersionId(it).first },
                loaderType = instanceLoaderMeta.loaderType,
                loaderVersion = instanceLoaderMeta.loaderVersion,
                detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
            )
        }

        // 3. Check Room catalog entry (if scanned or synced)
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

        // 4. Infer from versionId string patterns as fallback (marked VERSION_ID_INFERRED)
        val targetId = effectiveVersionId ?: rawVersionId ?: ""
        val inferred = inferFromVersionId(targetId)
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

    private fun readManifestChain(
        startVersionId: String,
        customManifestPath: String?,
        instanceDir: File?
    ): List<ParsedVersionManifest> {
        val chain = mutableListOf<ParsedVersionManifest>()
        val visited = HashSet<String>()
        var currentId: String? = startVersionId
        var isFirst = true

        while (currentId != null && visited.add(currentId) && chain.size < 6) {
            val manifestFile = findManifestFile(
                versionId = currentId,
                customManifestPath = if (isFirst) customManifestPath else null,
                instanceDir = instanceDir
            ) ?: break
            isFirst = false
            val parsed = runCatching {
                VersionManager.parseManifestJson(manifestFile.readText(), currentId)
            }.getOrNull() ?: break
            chain.add(parsed)
            currentId = parsed.inheritsFrom?.trim()?.takeIf { it.isNotEmpty() }
        }
        return chain
    }

    private fun findManifestFile(
        versionId: String,
        customManifestPath: String?,
        instanceDir: File?
    ): File? {
        val candidates = buildList {
            add(File(storageManager.versionsRoot(), "$versionId/$versionId.json"))
            if (!customManifestPath.isNullOrBlank()) {
                add(File(customManifestPath))
            }
            if (instanceDir != null) {
                add(File(instanceDir, "versions/$versionId/$versionId.json"))
                add(File(instanceDir, "$versionId.json"))
                add(File(instanceDir, "version.json"))
            }
        }
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    private data class InstanceDirectoryLoaderMeta(
        val installedVersionId: String,
        val minecraftVersion: String?,
        val loaderType: ModLoaderType,
        val loaderVersion: String?
    )

    private fun readInstanceDirectoryLoaderMeta(
        instanceDir: File,
        rawVersionId: String?
    ): InstanceDirectoryLoaderMeta? {
        if (!instanceDir.isDirectory) return null

        // 1. Check STAR Launcher's authoritative instance loader metadata file (.star_loader.json)
        val starMetaFile = File(instanceDir, STAR_LOADER_METADATA_FILE)
        if (starMetaFile.isFile && starMetaFile.length() > 0L) {
            runCatching {
                val obj = JSONObject(starMetaFile.readText())
                val loaderRaw = obj.optString("loaderType").trim().uppercase()
                val loaderType = runCatching { ModLoaderType.valueOf(loaderRaw) }.getOrNull()
                val installedId = obj.optString("installedVersionId").trim()
                val mcVer = obj.optString("minecraftVersion").trim().ifEmpty { null }
                val loaderVer = obj.optString("loaderVersion").trim().ifEmpty { null }
                if (loaderType != null && loaderType != ModLoaderType.VANILLA) {
                    if (rawVersionId == null || rawVersionId == installedId || rawVersionId == mcVer) {
                        return InstanceDirectoryLoaderMeta(
                            installedVersionId = installedId,
                            minecraftVersion = mcVer,
                            loaderType = loaderType,
                            loaderVersion = loaderVer
                        )
                    }
                }
            }
        }

        // 2. Check MultiMC / Prism Launcher mmc-pack.json if present in instance directory
        val mmcPackFile = File(instanceDir, "mmc-pack.json")
        if (mmcPackFile.isFile && mmcPackFile.length() > 0L) {
            runCatching {
                val obj = JSONObject(mmcPackFile.readText())
                val components = obj.optJSONArray("components")
                if (components != null) {
                    var mcVer: String? = null
                    var detectedLoader = ModLoaderType.VANILLA
                    var detectedLoaderVer: String? = null
                    for (i in 0 until components.length()) {
                        val comp = components.optJSONObject(i) ?: continue
                        val uid = comp.optString("uid").lowercase()
                        val ver = comp.optString("version").trim().ifEmpty { null }
                        when {
                            uid == "net.minecraft" -> mcVer = ver
                            uid == "org.quiltmc.quilt-loader" -> {
                                detectedLoader = ModLoaderType.QUILT
                                detectedLoaderVer = ver
                            }
                            uid == "net.fabricmc.fabric-loader" -> {
                                if (detectedLoader == ModLoaderType.VANILLA) {
                                    detectedLoader = ModLoaderType.FABRIC
                                    detectedLoaderVer = ver
                                }
                            }
                            uid == "net.neoforged" -> {
                                detectedLoader = ModLoaderType.NEOFORGE
                                detectedLoaderVer = ver
                            }
                            uid == "net.minecraftforge" -> {
                                if (detectedLoader == ModLoaderType.VANILLA) {
                                    detectedLoader = ModLoaderType.FORGE
                                    detectedLoaderVer = ver
                                }
                            }
                        }
                    }
                    if (detectedLoader != ModLoaderType.VANILLA) {
                        return InstanceDirectoryLoaderMeta(
                            installedVersionId = rawVersionId.orEmpty(),
                            minecraftVersion = mcVer,
                            loaderType = detectedLoader,
                            loaderVersion = detectedLoaderVer
                        )
                    }
                }
            }
        }

        return null
    }

    companion object {
        const val STAR_LOADER_METADATA_FILE = ".star_loader.json"
        private val FABRIC_ID_REGEX = Regex("""^fabric-loader-([^-]+)-(.+)$""", RegexOption.IGNORE_CASE)
        private val QUILT_ID_REGEX = Regex("""^quilt-loader-([^-]+)-(.+)$""", RegexOption.IGNORE_CASE)
        private val FORGE_ID_REGEX = Regex("""^(\d+\.\d+(?:\.\d+)?)-forge-(.+)$""", RegexOption.IGNORE_CASE)
        private val NEOFORGE_ID_REGEX = Regex("""^neoforge-(.+)$""", RegexOption.IGNORE_CASE)
        private val MC_VERSION_REGEX = Regex("""(\d+\.\d+(?:\.\d+)?)""")
        private val MC_VERSION_EXACT_REGEX = Regex("""^\d+\.\d+(?:\.\d+)?$""")

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
