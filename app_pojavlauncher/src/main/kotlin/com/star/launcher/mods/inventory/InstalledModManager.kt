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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    private val compatibilityEngine: ModCompatibilityEngine = ModCompatibilityEngine(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val parsedJarCache = java.util.concurrent.ConcurrentHashMap<String, ParsedJarModMetadata>()

    private fun parseJarCached(file: File): ParsedJarModMetadata {
        val baseKey = file.name.removeSuffix(".disabled").lowercase()
        val cacheKey = "${file.parentFile?.absolutePath}|$baseKey|${file.length()}|${file.lastModified()}"
        parsedJarCache[cacheKey]?.let { return it }
        return JarModMetadataParser.parse(file).also { parsed ->
            if (parsedJarCache.size > 512) {
                parsedJarCache.clear()
            }
            parsedJarCache[cacheKey] = parsed
        }
    }

    suspend fun inspectInventory(environment: InstanceEnvironment): InstalledModInventoryReport =
        withContext(ioDispatcher) {
            val modsDir = File(environment.gameDirPath, "mods")
            val trackedList = installedModDao.getForInstance(environment.instanceId)
            val trackedByFile = trackedList.associateBy { it.fileName }

            if (!modsDir.isDirectory) {
                for (orphan in trackedList) {
                    installedModDao.deleteByFileName(environment.instanceId, orphan.fileName)
                }
                return@withContext InstalledModInventoryReport(
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

            // Reconcile DB rows whose mod JAR was removed outside the launcher UI.
            val presentNamesLower = jarFiles.map { it.name.lowercase() }.toSet()
            for (tracked in trackedList) {
                val enabledName = tracked.fileName.removeSuffix(".disabled").lowercase()
                val disabledName = "$enabledName.disabled"
                if (enabledName !in presentNamesLower && disabledName !in presentNamesLower) {
                    installedModDao.deleteByFileName(environment.instanceId, tracked.fileName)
                }
            }

        val parsedEntries = jarFiles.map { file ->
            val enabled = file.name.endsWith(".jar", ignoreCase = true)
            val baseName = if (enabled) file.name else file.name.removeSuffix(".disabled")
            val tracked = trackedByFile[file.name] ?: trackedByFile[baseName]
            val parsed = parseJarCached(file)
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
            } else if (!parsed.isValidArchive) {
                warnings += "File is not a valid JAR/ZIP archive (corrupted download)."
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

        InstalledModInventoryReport(
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
    ): Result<InstalledModInventoryItem?> = withContext(ioDispatcher) {
        if (modFileName.contains('/') || modFileName.contains('\\') || modFileName.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid mod filename: $modFileName"))
        }
        val modsDir = File(environment.gameDirPath, "mods")
        val source = File(modsDir, modFileName)
        if (!source.isFile) {
            return@withContext Result.failure(IllegalStateException("Mod file '$modFileName' no longer exists."))
        }

        val targetName = when {
            modFileName.endsWith(".jar.disabled", ignoreCase = true) ->
                modFileName.substring(0, modFileName.length - ".disabled".length)
            modFileName.endsWith(".jar", ignoreCase = true) ->
                "$modFileName.disabled"
            else -> return@withContext Result.failure(IllegalArgumentException("Unsupported mod file extension: $modFileName"))
        }
        val target = File(modsDir, targetName)
        if (target.exists()) {
            return@withContext Result.failure(IllegalStateException("Cannot toggle: '$targetName' already exists in mods/."))
        }
        if (!source.renameTo(target)) {
            return@withContext Result.failure(IllegalStateException("Failed to rename '$modFileName' to '$targetName'."))
        }

        installedModDao.updateFileName(environment.instanceId, modFileName, targetName)
        val refreshed = inspectInventory(environment)
        Result.success(refreshed.mods.firstOrNull { it.fileName == targetName })
    }

    suspend fun removeMod(
        environment: InstanceEnvironment,
        modFileName: String
    ): Result<Unit> = withContext(ioDispatcher) {
        if (modFileName.contains('/') || modFileName.contains('\\') || modFileName.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid mod filename: $modFileName"))
        }
        val modsDir = File(environment.gameDirPath, "mods")
        val target = File(modsDir, modFileName)
        val canonicalMods = modsDir.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (!canonicalTarget.startsWith(canonicalMods + File.separator)) {
            return@withContext Result.failure(SecurityException("Refused path traversal outside mods directory."))
        }
        if (target.exists() && !target.delete()) {
            return@withContext Result.failure(IllegalStateException("Unable to delete '$modFileName'."))
        }
        installedModDao.deleteByFileName(environment.instanceId, modFileName)
        Result.success(Unit)
    }

    /**
     * Checks CurseForge for newer compatible files on tracked mods without applying updates automatically.
     */
    suspend fun checkUpdates(
        environment: InstanceEnvironment,
        items: List<InstalledModInventoryItem>
    ): List<InstalledModInventoryItem> = withContext(ioDispatcher) {
        val repo = repository ?: return@withContext items
        val semaphore = Semaphore(4)
        coroutineScope {
            items.map { item ->
                async {
                    val tracked = item.trackedRecord
                    val cfModId = tracked?.curseForgeModId
                    val cfFileId = tracked?.curseForgeFileId
                    if (cfModId == null || cfFileId == null) return@async item

                    val filesResp = semaphore.withPermit {
                        repo.getModFiles(
                            modId = cfModId,
                            gameVersion = environment.baseMinecraftVersion,
                            modLoader = environment.curseForgeLoader,
                            index = 0,
                            pageSize = 25
                        ).getOrNull()
                    } ?: return@async item

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
            }.awaitAll()
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
