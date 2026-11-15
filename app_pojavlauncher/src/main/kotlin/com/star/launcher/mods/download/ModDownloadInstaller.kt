package com.star.launcher.mods.download

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.DownloadTaskDao
import com.star.launcher.data.db.dao.InstalledModDao
import com.star.launcher.data.db.entity.DownloadTaskEntity
import com.star.launcher.data.db.entity.InstalledModEntity
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.download.HttpTransport
import com.star.launcher.download.UrlConnectionHttpTransport
import com.star.launcher.mods.api.CurseForgeDownloadUrlResult
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.deps.ModInstallPlan
import com.star.launcher.mods.deps.PlannedModInstallItem
import com.star.launcher.mods.inventory.JarModMetadataParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

enum class ModDownloadPhase {
    QUEUED,
    RESOLVING_URL,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RESTRICTED
}

data class ModDownloadProgressItem(
    val modId: Long,
    val fileId: Long,
    val fileName: String,
    val displayName: String,
    val instanceId: String,
    val phase: ModDownloadPhase,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val restrictedWebsiteUrl: String? = null,
    val taskId: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

sealed interface ModInstallOutcome {
    data class Success(val installed: List<InstalledModEntity>) : ModInstallOutcome
    data class Restricted(val modName: String, val reason: String, val websiteUrl: String?) : ModInstallOutcome
    data class Cancelled(val fileName: String) : ModInstallOutcome
    data class Failed(val message: String, val retryable: Boolean = true) : ModInstallOutcome
}

interface StorageSpaceChecker {
    fun usableBytes(directory: File): Long
}

class DefaultStorageSpaceChecker : StorageSpaceChecker {
    override fun usableBytes(directory: File): Long {
        directory.mkdirs()
        return directory.usableSpace
    }
}

/**
 * Reliable background downloader and installer for CurseForge mods:
 * - Resolves download URLs via official CurseForge API (respects distribution restrictions).
 * - Checks free storage space before downloading.
 * - Prevents silent overwrites of existing files in `mods/` unless explicitly confirmed.
 * - Streams to `.part` temporary files in [AetherStorageManager.downloadStagingDir].
 * - Validates expected byte size and SHA-1 / MD5 hashes before moving into instance `mods/`.
 * - Supports cancellation and retry.
 */
class ModDownloadInstaller(
    private val repository: CurseForgeRepository,
    private val downloadTaskDao: DownloadTaskDao,
    private val installedModDao: InstalledModDao,
    private val storageManager: AetherStorageManager,
    private val httpTransport: HttpTransport = UrlConnectionHttpTransport(),
    private val storageSpaceChecker: StorageSpaceChecker = DefaultStorageSpaceChecker(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val _activeProgress = MutableStateFlow<Map<Long, ModDownloadProgressItem>>(emptyMap())
    val activeProgress: StateFlow<Map<Long, ModDownloadProgressItem>> = _activeProgress.asStateFlow()

    @Volatile
    private var cancelledFileIds = mutableSetOf<Long>()

    fun requestCancel(fileId: Long) {
        synchronized(cancelledFileIds) {
            cancelledFileIds.add(fileId)
        }
        val current = _activeProgress.value[fileId]
        if (current != null) {
            _activeProgress.update { map ->
                map + (fileId to current.copy(phase = ModDownloadPhase.CANCELLED, errorMessage = "Cancelled"))
            }
        }
    }

    fun clearFinishedProgress(fileId: Long) {
        _activeProgress.update { it - fileId }
    }

    suspend fun installPlan(
        plan: ModInstallPlan,
        environment: InstanceEnvironment,
        allowOverwrite: Boolean = false
    ): ModInstallOutcome = withContext(Dispatchers.IO) {
        if (!plan.canProceed) {
            val reason = buildList {
                if (!plan.rootItem.compatibility.canInstall) add(plan.rootItem.compatibility.summary)
                addAll(plan.conflicts)
                addAll(plan.unresolvedDependencies.map { it.reason })
            }.joinToString(" ").ifBlank { "Install plan cannot proceed due to compatibility or dependency errors." }
            return@withContext ModInstallOutcome.Failed(reason, retryable = false)
        }

        val items = plan.filesToDownload
        val modsDir = File(environment.gameDirPath, "mods").apply { mkdirs() }
        val stagingDir = storageManager.downloadStagingDir()

        // Storage preflight across the full plan
        val requiredBytes = items.sumOf { it.file.fileLengthBytes.coerceAtLeast(0L) } + MIN_STORAGE_HEADROOM_BYTES
        val availableBytes = storageSpaceChecker.usableBytes(modsDir)
        if (availableBytes in 1 until requiredBytes) {
            return@withContext ModInstallOutcome.Failed(
                "Insufficient storage: need ~${formatMb(requiredBytes)}, only ${formatMb(availableBytes)} available.",
                retryable = false
            )
        }

        val stagedItems = mutableListOf<StagedModArtifact>()
        for (item in items) {
            when (val outcome = stageAndVerifyItem(item, environment, modsDir, stagingDir, allowOverwrite)) {
                is StageOutcome.Ready -> stagedItems += outcome.artifact
                is StageOutcome.Restricted -> {
                    return@withContext ModInstallOutcome.Restricted(
                        modName = item.mod?.name ?: item.file.displayName,
                        reason = outcome.reason,
                        websiteUrl = outcome.websiteUrl
                    )
                }
                is StageOutcome.Cancelled -> return@withContext ModInstallOutcome.Cancelled(item.file.fileName)
                is StageOutcome.Failed -> return@withContext ModInstallOutcome.Failed(outcome.message, outcome.retryable)
            }
        }

        val installedResults = commitStagedBatch(stagedItems, allowOverwrite)
            ?: return@withContext ModInstallOutcome.Failed(
                "Atomic mod installation failed while committing files into ${modsDir.absolutePath}; changes rolled back.",
                retryable = true
            )
        ModInstallOutcome.Success(installedResults)
    }

    suspend fun installSingleFile(
        mod: CurseForgeMod,
        file: CurseForgeFile,
        environment: InstanceEnvironment,
        allowOverwrite: Boolean = false
    ): ModInstallOutcome = withContext(Dispatchers.IO) {
        val modsDir = File(environment.gameDirPath, "mods").apply { mkdirs() }
        val stagingDir = storageManager.downloadStagingDir()
        val requiredBytes = file.fileLengthBytes.coerceAtLeast(0L) + MIN_STORAGE_HEADROOM_BYTES
        val availableBytes = storageSpaceChecker.usableBytes(modsDir)
        if (availableBytes in 1 until requiredBytes) {
            return@withContext ModInstallOutcome.Failed(
                "Insufficient storage: need ~${formatMb(requiredBytes)}, only ${formatMb(availableBytes)} available.",
                retryable = false
            )
        }

        val item = PlannedModInstallItem(
            mod = mod,
            file = file,
            isRootMod = true,
            compatibility = com.star.launcher.mods.compat.ModCompatibilityEngine().evaluateFile(environment, file, mod)
        )
        if (!item.compatibility.canInstall) {
            return@withContext ModInstallOutcome.Failed(item.compatibility.summary, retryable = false)
        }

        when (val outcome = stageAndVerifyItem(item, environment, modsDir, stagingDir, allowOverwrite)) {
            is StageOutcome.Ready -> {
                val committed = commitStagedBatch(listOf(outcome.artifact), allowOverwrite)
                    ?: return@withContext ModInstallOutcome.Failed(
                        "Failed to install verified mod into ${modsDir.absolutePath}",
                        retryable = true
                    )
                ModInstallOutcome.Success(committed)
            }
            is StageOutcome.Restricted -> ModInstallOutcome.Restricted(
                modName = mod.name,
                reason = outcome.reason,
                websiteUrl = outcome.websiteUrl
            )
            is StageOutcome.Cancelled -> ModInstallOutcome.Cancelled(file.fileName)
            is StageOutcome.Failed -> ModInstallOutcome.Failed(outcome.message, outcome.retryable)
        }
    }

    private data class StagedModArtifact(
        val item: PlannedModInstallItem,
        val safeFileName: String,
        val partFile: File,
        val targetFile: File,
        val disabledTarget: File,
        val initialEntity: DownloadTaskEntity,
        val entity: InstalledModEntity
    )

    private sealed interface StageOutcome {
        data class Ready(val artifact: StagedModArtifact) : StageOutcome
        data class Restricted(val reason: String, val websiteUrl: String?) : StageOutcome
        data object Cancelled : StageOutcome
        data class Failed(val message: String, val retryable: Boolean) : StageOutcome
    }

    private suspend fun commitStagedBatch(
        artifacts: List<StagedModArtifact>,
        allowOverwrite: Boolean
    ): List<InstalledModEntity>? {
        val promoted = mutableListOf<Pair<StagedModArtifact, File?>>()
        try {
            val installed = mutableListOf<InstalledModEntity>()
            for (artifact in artifacts) {
                val file = artifact.item.file
                val mod = artifact.item.mod
                updateProgress(
                    ModDownloadProgressItem(
                        modId = file.modId,
                        fileId = file.id,
                        fileName = artifact.safeFileName,
                        displayName = mod?.name ?: file.displayName,
                        instanceId = artifact.entity.instanceId,
                        phase = ModDownloadPhase.INSTALLING,
                        downloadedBytes = artifact.partFile.length(),
                        totalBytes = artifact.partFile.length(),
                        taskId = artifact.initialEntity.taskId
                    )
                )

                var backupFile: File? = null
                if (allowOverwrite) {
                    if (artifact.targetFile.exists()) {
                        backupFile = File(artifact.partFile.parentFile, "${artifact.partFile.name}.bak").apply {
                            delete()
                        }
                        if (!artifact.targetFile.renameTo(backupFile)) {
                            artifact.targetFile.delete()
                            backupFile = null
                        }
                    }
                    if (artifact.disabledTarget.exists()) {
                        artifact.disabledTarget.delete()
                    }
                } else if (artifact.targetFile.exists() || artifact.disabledTarget.exists()) {
                    throw IOException("Target file '${artifact.safeFileName}' already exists in mods directory.")
                }

                val moved = artifact.partFile.renameTo(artifact.targetFile) || run {
                    artifact.partFile.copyTo(artifact.targetFile, overwrite = allowOverwrite)
                    artifact.partFile.delete()
                    artifact.targetFile.isFile
                }
                if (!moved) {
                    backupFile?.renameTo(artifact.targetFile)
                    throw IOException("Failed to move verified mod into ${artifact.targetFile.absolutePath}")
                }
                promoted += artifact to backupFile

                val finalSize = artifact.targetFile.length()
                val finalizedEntity = artifact.entity.copy(fileSizeBytes = finalSize)
                installedModDao.upsert(finalizedEntity)
                downloadTaskDao.upsert(
                    artifact.initialEntity.copy(
                        downloadedBytes = finalSize,
                        totalBytes = finalSize,
                        status = DownloadStatus.COMPLETED,
                        errorMessage = null,
                        updatedAt = clockMillis()
                    )
                )
                updateProgress(
                    ModDownloadProgressItem(
                        modId = file.modId,
                        fileId = file.id,
                        fileName = artifact.safeFileName,
                        displayName = mod?.name ?: file.displayName,
                        instanceId = finalizedEntity.instanceId,
                        phase = ModDownloadPhase.COMPLETED,
                        downloadedBytes = finalSize,
                        totalBytes = finalSize,
                        taskId = artifact.initialEntity.taskId
                    )
                )
                installed += finalizedEntity
            }
            promoted.forEach { (_, backup) -> backup?.delete() }
            return installed
        } catch (t: Throwable) {
            for ((artifact, backup) in promoted.asReversed()) {
                artifact.targetFile.delete()
                backup?.renameTo(artifact.targetFile)
                runCatching {
                    installedModDao.deleteByFileName(artifact.entity.instanceId, artifact.safeFileName)
                }
            }
            artifacts.forEach { it.partFile.delete() }
            return null
        }
    }

    private suspend fun stageAndVerifyItem(
        item: PlannedModInstallItem,
        environment: InstanceEnvironment,
        modsDir: File,
        stagingDir: File,
        allowOverwrite: Boolean
    ): StageOutcome {
        val file = item.file
        val mod = item.mod
        val safeFileName = sanitizeModFileName(file.fileName)
        val targetFile = File(modsDir, safeFileName)
        val disabledTarget = File(modsDir, "$safeFileName.disabled")

        // Verify target path stays inside the selected instance's mods directory
        val canonicalModsDir = modsDir.canonicalPath
        val canonicalTarget = targetFile.canonicalPath
        if (!canonicalTarget.startsWith(canonicalModsDir + File.separator)) {
            return StageOutcome.Failed("Invalid mod target path for '${file.fileName}'.", retryable = false)
        }

        if (!allowOverwrite && (targetFile.exists() || disabledTarget.exists())) {
            return StageOutcome.Failed(
                "File '$safeFileName' already exists in '${environment.instanceName}'. Confirm replace or update to overwrite.",
                retryable = false
            )
        }

        synchronized(cancelledFileIds) {
            cancelledFileIds.remove(file.id)
        }

        updateProgress(
            ModDownloadProgressItem(
                modId = file.modId,
                fileId = file.id,
                fileName = safeFileName,
                displayName = mod?.name ?: file.displayName,
                instanceId = environment.instanceId,
                phase = ModDownloadPhase.RESOLVING_URL,
                totalBytes = file.fileLengthBytes.coerceAtLeast(0L)
            )
        )

        val resolvedUrl = when (val urlResult = repository.resolveDownloadUrl(mod, file).getOrElse { err ->
            updateProgress(
                ModDownloadProgressItem(
                    modId = file.modId,
                    fileId = file.id,
                    fileName = safeFileName,
                    displayName = mod?.name ?: file.displayName,
                    instanceId = environment.instanceId,
                    phase = ModDownloadPhase.FAILED,
                    errorMessage = err.message ?: "Failed to resolve download URL"
                )
            )
            return StageOutcome.Failed(err.message ?: "Failed to resolve download URL", retryable = true)
        }) {
            is CurseForgeDownloadUrlResult.Available -> urlResult.url
            is CurseForgeDownloadUrlResult.Restricted -> {
                updateProgress(
                    ModDownloadProgressItem(
                        modId = file.modId,
                        fileId = file.id,
                        fileName = safeFileName,
                        displayName = mod?.name ?: file.displayName,
                        instanceId = environment.instanceId,
                        phase = ModDownloadPhase.RESTRICTED,
                        errorMessage = urlResult.reason,
                        restrictedWebsiteUrl = urlResult.websiteUrl
                    )
                )
                return StageOutcome.Restricted(urlResult.reason, urlResult.websiteUrl)
            }
        }

        val taskId = idGenerator()
        // Deterministic .part filename enables HTTP Range resume on retry.
        val partFile = File(stagingDir, "mod_${file.id}.part")
        if (file.fileLengthBytes > 0L && partFile.isFile && partFile.length() > file.fileLengthBytes) {
            partFile.delete()
        }
        val initialEntity = DownloadTaskEntity(
            taskId = taskId,
            instanceId = environment.instanceId,
            versionId = null,
            taskType = DownloadTaskType.MOD,
            sourceUrl = resolvedUrl,
            targetPath = targetFile.absolutePath,
            expectedSha1 = file.sha1,
            totalBytes = file.fileLengthBytes.coerceAtLeast(0L),
            downloadedBytes = 0L,
            status = DownloadStatus.RUNNING,
            retryCount = 0,
            errorMessage = null,
            updatedAt = clockMillis()
        )
        downloadTaskDao.upsert(initialEntity)

        return try {
            var lastPersistMs = 0L
            val downloadHeaders = repository.getDownloadHeaders(resolvedUrl)
            httpTransport.downloadToFile(resolvedUrl, partFile, downloadHeaders) { downloaded, reportedTotal ->
                if (isFileCancelled(file.id)) {
                    throw CancellationException("Download cancelled by user")
                }
                val effectiveTotal = when {
                    reportedTotal > 0L -> reportedTotal
                    file.fileLengthBytes > 0L -> file.fileLengthBytes
                    else -> downloaded
                }
                updateProgress(
                    ModDownloadProgressItem(
                        modId = file.modId,
                        fileId = file.id,
                        fileName = safeFileName,
                        displayName = mod?.name ?: file.displayName,
                        instanceId = environment.instanceId,
                        phase = ModDownloadPhase.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = effectiveTotal,
                        taskId = taskId
                    )
                )
                val now = clockMillis()
                if (now - lastPersistMs >= 250L) {
                    lastPersistMs = now
                    downloadTaskDao.upsert(
                        initialEntity.copy(
                            downloadedBytes = downloaded,
                            totalBytes = effectiveTotal,
                            status = DownloadStatus.RUNNING,
                            updatedAt = now
                        )
                    )
                }
            }

            if (isFileCancelled(file.id)) {
                throw CancellationException("Download cancelled by user")
            }

            updateProgress(
                ModDownloadProgressItem(
                    modId = file.modId,
                    fileId = file.id,
                    fileName = safeFileName,
                    displayName = mod?.name ?: file.displayName,
                    instanceId = environment.instanceId,
                    phase = ModDownloadPhase.VERIFYING,
                    downloadedBytes = partFile.length(),
                    totalBytes = file.fileLengthBytes.takeIf { it > 0L } ?: partFile.length(),
                    taskId = taskId
                )
            )
            downloadTaskDao.upsert(
                initialEntity.copy(
                    downloadedBytes = partFile.length(),
                    totalBytes = partFile.length(),
                    status = DownloadStatus.VERIFYING,
                    updatedAt = clockMillis()
                )
            )

            // Integrity checks: non-empty + exact fileLength + SHA-1 + MD5 + valid JAR/ZIP container
            if (!partFile.isFile || partFile.length() <= 0L) {
                throw CorruptArtifactException("Downloaded mod file is empty.")
            }
            if (file.fileLengthBytes > 0L && partFile.length() != file.fileLengthBytes) {
                throw CorruptArtifactException(
                    "File size mismatch: expected ${file.fileLengthBytes} bytes, got ${partFile.length()} bytes."
                )
            }
            file.sha1?.let { expectedSha1 ->
                val actualSha1 = computeDigestHex(partFile, "SHA-1")
                if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
                    throw CorruptArtifactException("SHA-1 verification failed for '$safeFileName'.")
                }
            }
            file.md5?.let { expectedMd5 ->
                val actualMd5 = computeDigestHex(partFile, "MD5")
                if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
                    throw CorruptArtifactException("MD5 verification failed for '$safeFileName'.")
                }
            }
            if (!JarModMetadataParser.isValidZipArchive(partFile)) {
                throw CorruptArtifactException("Downloaded file '$safeFileName' is not a valid JAR/ZIP archive.")
            }

            val entity = InstalledModEntity(
                recordId = "${environment.instanceId}:$safeFileName",
                instanceId = environment.instanceId,
                fileName = safeFileName,
                curseForgeModId = file.modId,
                curseForgeFileId = file.id,
                displayName = mod?.name ?: file.displayName,
                installedVersionLabel = file.displayName,
                mcVersionHint = file.declaredMinecraftVersions.firstOrNull() ?: environment.baseMinecraftVersion,
                loaderType = mapLoaderToStar(file.declaredLoaders, environment.loaderType),
                sha1 = file.sha1 ?: computeDigestHex(partFile, "SHA-1"),
                fileSizeBytes = partFile.length(),
                allowModDistribution = mod?.allowModDistribution,
                websiteUrl = mod?.links?.websiteUrl,
                iconUrl = mod?.logo?.thumbnailUrl ?: mod?.logo?.url,
                installedAt = clockMillis()
            )
            StageOutcome.Ready(
                StagedModArtifact(
                    item = item,
                    safeFileName = safeFileName,
                    partFile = partFile,
                    targetFile = targetFile,
                    disabledTarget = disabledTarget,
                    initialEntity = initialEntity,
                    entity = entity
                )
            )
        } catch (ce: CancellationException) {
            partFile.delete()
            downloadTaskDao.upsert(
                initialEntity.copy(
                    status = DownloadStatus.CANCELLED,
                    errorMessage = "Cancelled",
                    updatedAt = clockMillis()
                )
            )
            updateProgress(
                ModDownloadProgressItem(
                    modId = file.modId,
                    fileId = file.id,
                    fileName = safeFileName,
                    displayName = mod?.name ?: file.displayName,
                    instanceId = environment.instanceId,
                    phase = ModDownloadPhase.CANCELLED,
                    errorMessage = "Cancelled",
                    taskId = taskId
                )
            )
            StageOutcome.Cancelled
        } catch (t: Throwable) {
            if (t is CorruptArtifactException) {
                partFile.delete()
            }
            val message = t.message ?: t::class.java.simpleName
            downloadTaskDao.upsert(
                initialEntity.copy(
                    downloadedBytes = partFile.takeIf { it.isFile }?.length() ?: 0L,
                    status = DownloadStatus.FAILED,
                    retryCount = initialEntity.retryCount + 1,
                    errorMessage = message,
                    updatedAt = clockMillis()
                )
            )
            updateProgress(
                ModDownloadProgressItem(
                    modId = file.modId,
                    fileId = file.id,
                    fileName = safeFileName,
                    displayName = mod?.name ?: file.displayName,
                    instanceId = environment.instanceId,
                    phase = ModDownloadPhase.FAILED,
                    downloadedBytes = partFile.takeIf { it.isFile }?.length() ?: 0L,
                    totalBytes = file.fileLengthBytes.coerceAtLeast(0L),
                    errorMessage = message,
                    taskId = taskId
                )
            )
            StageOutcome.Failed(message, retryable = true)
        }
    }

    private class CorruptArtifactException(message: String) : IOException(message)

    private fun isFileCancelled(fileId: Long): Boolean =
        synchronized(cancelledFileIds) { fileId in cancelledFileIds }

    private fun updateProgress(item: ModDownloadProgressItem) {
        _activeProgress.update { it + (item.fileId to item) }
    }

    private fun mapLoaderToStar(
        declared: Set<CurseForgeModLoader>,
        fallback: ModLoaderType
    ): ModLoaderType = when {
        CurseForgeModLoader.NEOFORGE in declared -> ModLoaderType.NEOFORGE
        CurseForgeModLoader.FORGE in declared -> ModLoaderType.FORGE
        CurseForgeModLoader.FABRIC in declared -> ModLoaderType.FABRIC
        CurseForgeModLoader.QUILT in declared -> ModLoaderType.QUILT
        else -> fallback
    }

    companion object {
        private const val MIN_STORAGE_HEADROOM_BYTES = 2L * 1024L * 1024L

        fun sanitizeModFileName(rawName: String): String {
            val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = base.replace(Regex("""[^a-zA-Z0-9._+\-()\[\] ]"""), "_")
            require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
                "Invalid mod filename: $rawName"
            }
            return if (cleaned.endsWith(".jar", ignoreCase = true)) cleaned else "$cleaned.jar"
        }

        fun computeDigestHex(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
