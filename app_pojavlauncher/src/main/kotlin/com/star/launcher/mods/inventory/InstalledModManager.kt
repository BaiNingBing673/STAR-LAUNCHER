package com.star.launcher.mods.inventory

import com.star.launcher.data.db.dao.InstalledModDao
import com.star.launcher.data.db.entity.InstalledModEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.compat.ModCompatibilityEngine
import com.star.launcher.mods.compat.ModCompatibilityLevel
import java.io.File

data class InstalledModUpdateInfo(
    val latestCompatibleFile: CurseForgeFile,
    val isRestrictedDistribution: Boolean
)

data class InstalledModInventoryItem(
    val fileName: String,
    val displayName: String,
    val modIdInJar: String?,
    val versionLabel: String?,
    val isEnabled: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val detectedLoader: ModLoaderType?,
    val mcVersionHint: String?,
    val metadataSource: ModMetadataSource,
    val compatibilityLevel: ModCompatibilityLevel,
    val compatibilitySummary: String,
    val warnings: List<String>,
    val trackedRecord: InstalledModEntity?,
    val availableUpdate: InstalledModUpdateInfo? = null
) {
    val isDuplicateOrSuspicious: Boolean
        get() = warnings.isNotEmpty()
}

data class InstalledModInventoryReport(
    val instanceId: String,
    val instanceName: String,
    val modsDirPath: String,
    val mods: List<InstalledModInventoryItem>,
    val suspiciousNonJarFiles: List<String>
)

/**
 * Manages installed mods strictly within the selected instance's `<gameDirPath>/mods/` directory.
 *
 * Guarantees:
 * - Disabling renames `<name>.jar` <-> `<name>.jar.disabled` without deleting mod files or configs.
 * - Deletion happens only when explicitly confirmed via [removeMod].
 * - Detects duplicate mod IDs, enabled+disabled pairs, zero-byte JARs, and loader mismatches.
 */
class InstalledModManager(
    private val installedModDao: InstalledModDao,
    private val repository: CurseForgeRepository? = null,
    private val compatibilityEngine: ModCompatibilityEngine = ModCompatibilityEngine()
) {
    suspend fun inspectInventory(environment: InstanceEnvironment): InstalledModInventoryReport {
        val modsDir = File(environment.gameDirPath, "mods")
        val trackedList = installedModDao.getForInstance(environment.instanceId)
        val trackedByFile = trackedList.associateBy { it.fileName }

        if (!modsDir.isDirectory) {
            return InstalledModInventoryReport(
                instanceId = environment.instanceId,
                instanceName = environment.instanceName,
                modsDirPath = modsDir.absolutePath,
                mods = emptyList(),
                suspiciousNonJarFiles = emptyList()
            )
        }

        val allEntries = modsDir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        val suspiciousFiles = allEntries
            .filter {
                it.isFile &&
                    !it.name.endsWith(".jar", ignoreCase = true) &&
                    !it.name.endsWith(".jar.disabled", ignoreCase = true) &&
                    !it.name.startsWith(".")
            }
            .map { it.name }

        val jarFiles = allEntries.filter {
            it.isFile &&
                (it.name.endsWith(".jar", ignoreCase = true) ||
                    it.name.endsWith(".jar.disabled", ignoreCase = true))
        }

        val parsedEntries = jarFiles.map { file ->
            val enabled = file.name.endsWith(".jar", ignoreCase = true)
            val baseName = if (enabled) file.name else file.name.removeSuffix(".disabled")
            val tracked = trackedByFile[file.name] ?: trackedByFile[baseName]
            val parsed = JarModMetadataParser.parse(file)
            Triple(file, parsed, tracked)
        }

        // Group counts for duplicate detection
        val countByJarModId = parsedEntries
            .mapNotNull { it.second.modId?.lowercase() }
            .groupingBy { it }
            .eachCount()
        val countByBaseFileName = jarFiles
            .map { it.name.removeSuffix(".disabled").lowercase() }
            .groupingBy { it }
            .eachCount()
        val countByCurseForgeId = parsedEntries
            .mapNotNull { it.third?.curseForgeModId }
            .groupingBy { it }
            .eachCount()

        val items = parsedEntries.map { (file, parsed, tracked) ->
            val enabled = file.name.endsWith(".jar", ignoreCase = true)
            val baseLower = file.name.removeSuffix(".disabled").lowercase()
            val warnings = mutableListOf<String>()

            if (file.length() <= 0L) {
                warnings += "File is 0 bytes (corrupted or incomplete download)."
            }
            if ((countByBaseFileName[baseLower] ?: 0) > 1) {
                warnings += "Both enabled and disabled copies exist for '$baseLower'."
            }
            parsed.modId?.lowercase()?.let { modId ->
                if ((countByJarModId[modId] ?: 0) > 1) {
                    warnings += "Duplicate mod ID '$modId' detected across multiple JARs."
                }
            }
            tracked?.curseForgeModId?.let { cfId ->
                if ((countByCurseForgeId[cfId] ?: 0) > 1) {
                    warnings += "Duplicate CurseForge project #$cfId installed in this instance."
                }
            }
            if (file.name.contains(" (1)") || file.name.contains("-copy", ignoreCase = true)) {
                warnings += "Suspicious duplicate filename pattern detected."
            }

            val effectiveLoader = parsed.loaderType ?: tracked?.loaderType
            val effectiveMcHint = parsed.mcVersionHint ?: tracked?.mcVersionHint
            val (compatLevel, compatSummary) = evaluateLocalCompatibility(
                environment = environment,
                modLoader = effectiveLoader,
                mcHint = effectiveMcHint
            )

            InstalledModInventoryItem(
                fileName = file.name,
                displayName = tracked?.displayName ?: parsed.displayName,
                modIdInJar = parsed.modId,
                versionLabel = tracked?.installedVersionLabel ?: parsed.version,
                isEnabled = enabled,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
                detectedLoader = effectiveLoader,
                mcVersionHint = effectiveMcHint,
                metadataSource = parsed.source,
                compatibilityLevel = compatLevel,
                compatibilitySummary = compatSummary,
                warnings = warnings,
                trackedRecord = tracked
            )
        }

        return InstalledModInventoryReport(
            instanceId = environment.instanceId,
            instanceName = environment.instanceName,
            modsDirPath = modsDir.absolutePath,
            mods = items,
            suspiciousNonJarFiles = suspiciousFiles
        )
    }

    suspend fun toggleModEnabled(
        environment: InstanceEnvironment,
        modFileName: String
    ): Result<InstalledModInventoryItem?> {
        if (modFileName.contains('/') || modFileName.contains('\\') || modFileName.contains("..")) {
            return Result.failure(IllegalArgumentException("Invalid mod filename: $modFileName"))
        }
        val modsDir = File(environment.gameDirPath, "mods")
        val source = File(modsDir, modFileName)
        if (!source.isFile) {
            return Result.failure(IllegalStateException("Mod file '$modFileName' no longer exists."))
        }

        val targetName = when {
            modFileName.endsWith(".jar.disabled", ignoreCase = true) ->
                modFileName.substring(0, modFileName.length - ".disabled".length)
            modFileName.endsWith(".jar", ignoreCase = true) ->
                "$modFileName.disabled"
            else -> return Result.failure(IllegalArgumentException("Unsupported mod file extension: $modFileName"))
        }
        val target = File(modsDir, targetName)
        if (target.exists()) {
            return Result.failure(IllegalStateException("Cannot toggle: '$targetName' already exists in mods/."))
        }
        if (!source.renameTo(target)) {
            return Result.failure(IllegalStateException("Failed to rename '$modFileName' to '$targetName'."))
        }

        installedModDao.updateFileName(environment.instanceId, modFileName, targetName)
        val refreshed = inspectInventory(environment)
        return Result.success(refreshed.mods.firstOrNull { it.fileName == targetName })
    }

    suspend fun removeMod(
        environment: InstanceEnvironment,
        modFileName: String
    ): Result<Unit> {
        if (modFileName.contains('/') || modFileName.contains('\\') || modFileName.contains("..")) {
            return Result.failure(IllegalArgumentException("Invalid mod filename: $modFileName"))
        }
        val modsDir = File(environment.gameDirPath, "mods")
        val target = File(modsDir, modFileName)
        val canonicalMods = modsDir.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (!canonicalTarget.startsWith(canonicalMods + File.separator)) {
            return Result.failure(SecurityException("Refused path traversal outside mods directory."))
        }
        if (target.exists() && !target.delete()) {
            return Result.failure(IllegalStateException("Unable to delete '$modFileName'."))
        }
        installedModDao.deleteByFileName(environment.instanceId, modFileName)
        return Result.success(Unit)
    }

    /**
     * Checks CurseForge for newer compatible files on tracked mods without applying updates automatically.
     */
    suspend fun checkUpdates(
        environment: InstanceEnvironment,
        items: List<InstalledModInventoryItem>
    ): List<InstalledModInventoryItem> {
        val repo = repository ?: return items
        return items.map { item ->
            val tracked = item.trackedRecord
            val cfModId = tracked?.curseForgeModId
            val cfFileId = tracked?.curseForgeFileId
            if (cfModId == null || cfFileId == null) return@map item

            val filesResp = repo.getModFiles(
                modId = cfModId,
                gameVersion = environment.baseMinecraftVersion,
                modLoader = environment.curseForgeLoader,
                index = 0,
                pageSize = 25
            ).getOrNull() ?: return@map item

            val newerCandidate = filesResp.files
                .filter { it.isAvailable && !it.isServerPack && it.id > cfFileId }
                .filter { candidate ->
                    compatibilityEngine.evaluateFile(environment, candidate).canInstall
                }
                .maxByOrNull { it.id }

            if (newerCandidate != null) {
                item.copy(
                    availableUpdate = InstalledModUpdateInfo(
                        latestCompatibleFile = newerCandidate,
                        isRestrictedDistribution = tracked.allowModDistribution == false ||
                            newerCandidate.downloadUrl.isNullOrBlank()
                    )
                )
            } else {
                item
            }
        }
    }

    private fun evaluateLocalCompatibility(
        environment: InstanceEnvironment,
        modLoader: ModLoaderType?,
        mcHint: String?
    ): Pair<ModCompatibilityLevel, String> {
        if (!environment.isModdedLoader) {
            return ModCompatibilityLevel.INCOMPATIBLE to
                "Instance runs ${environment.loaderType.name}; mods require Forge, NeoForge, Fabric, or Quilt."
        }
        if (modLoader != null && modLoader != environment.loaderType) {
            if (environment.loaderType == ModLoaderType.QUILT && modLoader == ModLoaderType.FABRIC) {
                return ModCompatibilityLevel.POTENTIALLY_COMPATIBLE to
                    "Fabric mod on Quilt instance — verify runtime behavior."
            }
            return ModCompatibilityLevel.INCOMPATIBLE to
                "Mod targets $modLoader, but instance runs ${environment.loaderType}."
        }
        val baseMc = environment.baseMinecraftVersion
        if (baseMc != null && mcHint != null && !mcHint.contains(baseMc, ignoreCase = true)) {
            return ModCompatibilityLevel.POTENTIALLY_COMPATIBLE to
                "Mod metadata mentions '$mcHint' (instance is $baseMc)."
        }
        if (modLoader == environment.loaderType) {
            return ModCompatibilityLevel.POTENTIALLY_COMPATIBLE to
                "Loader matches ${environment.loaderType}; full compatibility depends on exact MC version."
        }
        return ModCompatibilityLevel.UNKNOWN to "Unable to verify loader or MC version from local JAR metadata."
    }
}
