package com.star.launcher.version

import android.content.Context
import android.os.StatFs
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import net.kdt.pojavlaunch.utils.DownloadUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend abstraction over the inherited Amethyst-Android/PojavLauncher download engine so unit
 * tests can verify stage transitions, byte/unknown totals, cancellation, failure recovery, and
 * single-flight gating.
 */
interface MinecraftInstallBackend {
    interface BackendHandle {
        fun cancel()
    }

    interface Callbacks {
        fun onStageChanged(stage: InstallationStage, currentFile: String?)
        fun onProgress(
            downloadedBytes: Long,
            totalBytes: Long?,
            processedFiles: Int,
            totalFiles: Int,
            currentFile: String?
        )
        fun onCompleted()
        fun onFailed(error: Throwable)
        fun onCancelled()
    }

    fun isNetworkAvailable(): Boolean
    fun availableStorageBytes(): Long
    fun startInstall(
        versionId: String,
        manifestUrl: String?,
        manifestSha1: String?,
        callbacks: Callbacks
    ): BackendHandle
}

class PojavMinecraftInstallBackend(
    private val context: Context,
    private val storageManager: AetherStorageManager
) : MinecraftInstallBackend {

    override fun isNetworkAvailable(): Boolean = Tools.isOnline(context)

    override fun availableStorageBytes(): Long {
        val root = storageManager.versionsRoot().parentFile?.apply { mkdirs() } ?: context.filesDir
        return runCatching {
            StatFs(root.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    override fun startInstall(
        versionId: String,
        manifestUrl: String?,
        manifestSha1: String?,
        callbacks: MinecraftInstallBackend.Callbacks
    ): MinecraftInstallBackend.BackendHandle {
        val downloader = MinecraftDownloader()
        val listedVersion = runCatching {
            AsyncMinecraftDownloader.getListedVersion(versionId)
        }.getOrNull() ?: manifestUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url ->
            JMinecraftVersionList.Version().apply {
                id = versionId
                this.url = url
                this.sha1 = manifestSha1
            }
        }

        val handle = downloader.startInstallOnly(
            context.applicationContext,
            listedVersion,
            versionId,
            object : MinecraftDownloader.InstallProgressListener {
                override fun onStageChanged(stage: MinecraftDownloader.InstallStage, currentFile: String?) {
                    callbacks.onStageChanged(mapStage(stage), currentFile)
                }

                override fun onProgress(
                    downloadedBytes: Long,
                    totalBytes: Long,
                    processedFiles: Int,
                    totalFiles: Int,
                    currentFile: String?
                ) {
                    callbacks.onProgress(
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes.takeIf { it > 0L },
                        processedFiles = processedFiles,
                        totalFiles = totalFiles,
                        currentFile = currentFile
                    )
                }

                override fun onCompleted() {
                    callbacks.onCompleted()
                }

                override fun onFailed(error: Throwable) {
                    callbacks.onFailed(error)
                }

                override fun onCancelled() {
                    callbacks.onCancelled()
                }
            }
        )

        return object : MinecraftInstallBackend.BackendHandle {
            override fun cancel() {
                handle.cancel()
            }
        }
    }

    private fun mapStage(stage: MinecraftDownloader.InstallStage): InstallationStage = when (stage) {
        MinecraftDownloader.InstallStage.FETCHING_METADATA -> InstallationStage.FETCHING_METADATA
        MinecraftDownloader.InstallStage.DOWNLOADING_CLIENT_JAR -> InstallationStage.DOWNLOADING_CLIENT_JAR
        MinecraftDownloader.InstallStage.DOWNLOADING_LIBRARIES -> InstallationStage.DOWNLOADING_LIBRARIES
        MinecraftDownloader.InstallStage.DOWNLOADING_ASSETS -> InstallationStage.DOWNLOADING_ASSETS
        MinecraftDownloader.InstallStage.PREPARING_NATIVES -> InstallationStage.PREPARING_NATIVES
        MinecraftDownloader.InstallStage.VALIDATING -> InstallationStage.VALIDATING
        MinecraftDownloader.InstallStage.COMPLETED -> InstallationStage.COMPLETED
    }
}

/**
 * Coordinates single-flight Minecraft version installations, verification gates,
 * cancellation/partial cleanup, error diagnostics, and persisted metadata across navigation/process lifecycle.
 */
class VersionInstallCoordinator(
    private val versionManager: VersionManager,
    private val settingsRepository: AetherSettingsRepository,
    private val backend: MinecraftInstallBackend,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val minRequiredStorageBytes: Long = DEFAULT_MIN_REQUIRED_STORAGE_BYTES
) {
    private val _activeProgress = MutableStateFlow<Map<String, InstallationProgress>>(emptyMap())
    val activeProgress: StateFlow<Map<String, InstallationProgress>> = _activeProgress.asStateFlow()

    private val handlesLock = Any()
    private val activeHandles = ConcurrentHashMap<String, MinecraftInstallBackend.BackendHandle>()
    private val cancelledVersions = ConcurrentHashMap.newKeySet<String>()

    fun observeMetadata(): Flow<Map<String, VersionInstallMetadata>> {
        return settingsRepository.observeAll().map { settingsMap ->
            val raw = settingsMap[KEY_INSTALL_METADATA]?.value
            parseMetadataMap(raw)
        }
    }

    suspend fun getMetadata(versionId: String): VersionInstallMetadata? {
        val raw = settingsRepository.getString(KEY_INSTALL_METADATA, "")
        return parseMetadataMap(raw)[versionId]
    }

    /**
     * Detects any installation that was running when the app process died and marks it safely
     * as interrupted while purging leftover `.aetherpart` temporary files.
     */
    suspend fun recoverInterruptedInstalls() {
        val interruptedId = settingsRepository.getString(KEY_ACTIVE_INSTALL_VERSION_ID, "").trim()
        if (interruptedId.isBlank()) return
        synchronized(handlesLock) {
            if (activeHandles.containsKey(interruptedId)) return
        }
        versionManager.cleanupPartialFiles(interruptedId)
        val existingMeta = getMetadata(interruptedId) ?: VersionInstallMetadata(versionId = interruptedId)
        val updatedMeta = existingMeta.copy(
            wasInterrupted = true,
            lastErrorCategory = InstallErrorCategory.PROCESS_INTERRUPTED,
            lastErrorMessage = "Installation of $interruptedId was interrupted when the app closed. Tap Retry to finish cleanly.",
            lastDiagnosticDetails = "Active installation lock found on startup for version '$interruptedId' after previous process termination. Cleaned staged .aetherpart files.",
            lastAttemptAt = clockMillis()
        )
        upsertMetadata(updatedMeta)
        settingsRepository.remove(KEY_ACTIVE_INSTALL_VERSION_ID)
        _activeProgress.update { current ->
            current + (interruptedId to InstallationProgress(
                versionId = interruptedId,
                stage = InstallationStage.FETCHING_METADATA,
                errorMessage = updatedMeta.lastErrorMessage,
                errorCategory = InstallErrorCategory.PROCESS_INTERRUPTED,
                diagnosticDetails = updatedMeta.lastDiagnosticDetails,
                startedAt = updatedMeta.lastAttemptAt ?: clockMillis(),
                isFinished = true,
                isCancellable = false
            ))
        }
    }

    suspend fun recordVerifiedMetadata(result: VersionVerificationResult) {
        if (!result.isValid) return
        val existing = getMetadata(result.versionId) ?: VersionInstallMetadata(versionId = result.versionId)
        upsertMetadata(
            existing.copy(
                installedAt = result.installedAt,
                diskSizeBytes = result.diskSizeBytes,
                installedManifestSha1 = result.installedManifestSha1,
                updateAvailable = existing.remoteManifestSha1 != null &&
                    result.installedManifestSha1 != null &&
                    !existing.remoteManifestSha1.equals(result.installedManifestSha1, ignoreCase = true),
                lastErrorMessage = null,
                lastErrorCategory = null,
                lastDiagnosticDetails = null,
                wasInterrupted = false
            )
        )
    }

    suspend fun recordRemoteManifestHashes(remoteSha1ByVersionId: Map<String, String>) {
        if (remoteSha1ByVersionId.isEmpty()) return
        val map = parseMetadataMap(settingsRepository.getString(KEY_INSTALL_METADATA, "")).toMutableMap()
        var changed = false
        for ((versionId, remoteSha) in remoteSha1ByVersionId) {
            val prev = map[versionId] ?: VersionInstallMetadata(versionId = versionId)
            val installedSha = prev.installedManifestSha1
            val hasUpdate = installedSha != null && !installedSha.equals(remoteSha, ignoreCase = true)
            if (prev.remoteManifestSha1 != remoteSha || prev.updateAvailable != hasUpdate) {
                map[versionId] = prev.copy(
                    remoteManifestSha1 = remoteSha,
                    updateAvailable = hasUpdate
                )
                changed = true
            }
        }
        if (changed) {
            saveMetadataMap(map)
        }
    }

    /**
     * Starts an asynchronous installation for [versionId]. Duplicate requests while already
     * installing [versionId] are ignored so only one download job runs per version.
     */
    suspend fun installVersion(
        versionId: String,
        manifestUrl: String? = null,
        manifestSha1: String? = null
    ): Boolean {
        val trimmedId = versionId.trim()
        if (trimmedId.isBlank() || trimmedId.equals("unknown", ignoreCase = true)) {
            emitImmediateFailure(
                versionId = trimmedId.ifBlank { "unknown" },
                category = InstallErrorCategory.INVALID_METADATA,
                message = "No valid Minecraft version was specified.",
                diagnostics = "versionId='$versionId'"
            )
            return false
        }

        synchronized(handlesLock) {
            val current = _activeProgress.value[trimmedId]
            if (activeHandles.containsKey(trimmedId) ||
                (current != null && !current.isFinished && current.errorCategory == null)
            ) {
                return false
            }
            cancelledVersions.remove(trimmedId)
            _activeProgress.update { map ->
                map + (trimmedId to InstallationProgress(
                    versionId = trimmedId,
                    stage = InstallationStage.FETCHING_METADATA,
                    downloadedBytes = 0L,
                    totalBytes = null,
                    progressPercent = null,
                    startedAt = clockMillis(),
                    isFinished = false,
                    isCancellable = true
                ))
            }
        }

        if (!backend.isNetworkAvailable()) {
            emitImmediateFailure(
                versionId = trimmedId,
                category = InstallErrorCategory.NETWORK_UNAVAILABLE,
                message = "No network connection available. Connect to Wi-Fi or mobile data to download Minecraft $trimmedId.",
                diagnostics = "Network check returned offline prior to starting installation of $trimmedId."
            )
            return false
        }

        val freeBytes = backend.availableStorageBytes()
        if (freeBytes < minRequiredStorageBytes) {
            val freeMb = freeBytes / (1024L * 1024L)
            val needMb = minRequiredStorageBytes / (1024L * 1024L)
            emitImmediateFailure(
                versionId = trimmedId,
                category = InstallErrorCategory.INSUFFICIENT_STORAGE,
                message = "Insufficient storage space ($freeMb MB available, at least $needMb MB required).",
                diagnostics = "availableStorageBytes=$freeBytes, minRequiredStorageBytes=$minRequiredStorageBytes"
            )
            return false
        }

        val existingEntity = versionManager.getVersion(trimmedId)
        val resolvedUrl = manifestUrl ?: existingEntity?.manifestPath?.takeIf { it.startsWith("http", ignoreCase = true) }
        val resolvedSha1 = manifestSha1 ?: getMetadata(trimmedId)?.remoteManifestSha1

        settingsRepository.setString(KEY_ACTIVE_INSTALL_VERSION_ID, trimmedId)
        val startedAt = _activeProgress.value[trimmedId]?.startedAt ?: clockMillis()
        val backendTerminated = AtomicBoolean(false)

        val callbacks = object : MinecraftInstallBackend.Callbacks {
            override fun onStageChanged(stage: InstallationStage, currentFile: String?) {
                if (cancelledVersions.contains(trimmedId)) return
                _activeProgress.update { map ->
                    val prev = map[trimmedId] ?: InstallationProgress(versionId = trimmedId, stage = stage, startedAt = startedAt)
                    map + (trimmedId to prev.copy(
                        stage = stage,
                        currentFile = currentFile ?: prev.currentFile,
                        errorMessage = null,
                        errorCategory = null,
                        isFinished = false,
                        isCancellable = stage != InstallationStage.VALIDATING && stage != InstallationStage.COMPLETED
                    ))
                }
            }

            override fun onProgress(
                downloadedBytes: Long,
                totalBytes: Long?,
                processedFiles: Int,
                totalFiles: Int,
                currentFile: String?
            ) {
                if (cancelledVersions.contains(trimmedId)) return
                val validTotal = totalBytes?.takeIf { it > 0L }
                val percent = validTotal?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                }
                _activeProgress.update { map ->
                    val prev = map[trimmedId] ?: InstallationProgress(
                        versionId = trimmedId,
                        stage = InstallationStage.DOWNLOADING_CLIENT_JAR,
                        startedAt = startedAt
                    )
                    map + (trimmedId to prev.copy(
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        totalBytes = validTotal,
                        progressPercent = percent,
                        processedFiles = processedFiles,
                        totalFiles = totalFiles,
                        currentFile = currentFile ?: prev.currentFile,
                        isFinished = false
                    ))
                }
            }

            override fun onCompleted() {
                backendTerminated.set(true)
                scope.launch {
                    handleBackendCompleted(trimmedId, startedAt)
                }
            }

            override fun onFailed(error: Throwable) {
                backendTerminated.set(true)
                scope.launch {
                    handleBackendFailed(trimmedId, startedAt, error)
                }
            }

            override fun onCancelled() {
                backendTerminated.set(true)
                scope.launch {
                    handleBackendCancelled(trimmedId)
                }
            }
        }

        val handle = backend.startInstall(
            versionId = trimmedId,
            manifestUrl = resolvedUrl,
            manifestSha1 = resolvedSha1,
            callbacks = callbacks
        )
        synchronized(handlesLock) {
            if (cancelledVersions.contains(trimmedId)) {
                handle.cancel()
            } else if (!backendTerminated.get()) {
                activeHandles[trimmedId] = handle
            }
        }
        return true
    }

    suspend fun retryInstall(versionId: String): Boolean {
        clearErrorState(versionId)
        return installVersion(versionId)
    }

    suspend fun clearErrorState(versionId: String) {
        val prev = getMetadata(versionId)
        if (prev != null) {
            upsertMetadata(
                prev.copy(
                    lastErrorMessage = null,
                    lastErrorCategory = null,
                    lastDiagnosticDetails = null,
                    wasInterrupted = false
                )
            )
        }
        _activeProgress.update { it - versionId }
    }

    fun cancelInstall(versionId: String) {
        val handle = synchronized(handlesLock) {
            cancelledVersions.add(versionId)
            activeHandles.remove(versionId)
        }
        handle?.cancel()
        scope.launch {
            cleanupAfterCancel(versionId)
        }
    }

    suspend fun verifyInstalledVersion(versionId: String): VersionVerificationResult {
        val result = versionManager.verifyInstallation(versionId)
        if (result.isValid) {
            recordVerifiedMetadata(result)
        } else {
            val prev = getMetadata(versionId) ?: VersionInstallMetadata(versionId = versionId)
            upsertMetadata(
                prev.copy(
                    lastErrorCategory = result.failureCategory ?: InstallErrorCategory.CORRUPTED_FILES,
                    lastErrorMessage = result.failureMessage ?: "Verification failed for $versionId.",
                    lastDiagnosticDetails = result.diagnosticDetails,
                    lastAttemptAt = clockMillis()
                )
            )
        }
        return result
    }

    private suspend fun handleBackendCompleted(versionId: String, startedAt: Long) {
        synchronized(handlesLock) {
            activeHandles.remove(versionId)
        }
        if (cancelledVersions.remove(versionId)) {
            cleanupAfterCancel(versionId)
            return
        }

        _activeProgress.update { map ->
            val prev = map[versionId] ?: InstallationProgress(versionId = versionId, stage = InstallationStage.VALIDATING, startedAt = startedAt)
            map + (versionId to prev.copy(
                stage = InstallationStage.VALIDATING,
                isCancellable = false,
                isFinished = false
            ))
        }

        val verification = versionManager.verifyInstallation(versionId)
        clearActiveLockIfMatches(versionId)

        if (!verification.isValid) {
            val category = verification.failureCategory ?: InstallErrorCategory.CORRUPTED_FILES
            val message = verification.failureMessage ?: "Installation verification failed for $versionId."
            val prevMeta = getMetadata(versionId) ?: VersionInstallMetadata(versionId = versionId)
            upsertMetadata(
                prevMeta.copy(
                    lastErrorCategory = category,
                    lastErrorMessage = message,
                    lastDiagnosticDetails = verification.diagnosticDetails,
                    lastAttemptAt = clockMillis(),
                    wasInterrupted = false
                )
            )
            _activeProgress.update { map ->
                val prev = map[versionId] ?: InstallationProgress(versionId = versionId, stage = InstallationStage.VALIDATING, startedAt = startedAt)
                map + (versionId to prev.copy(
                    stage = InstallationStage.VALIDATING,
                    errorMessage = message,
                    errorCategory = category,
                    diagnosticDetails = verification.diagnosticDetails,
                    isFinished = true,
                    isCancellable = false
                ))
            }
            return
        }

        val lastDownloadBytes = _activeProgress.value[versionId]?.totalBytes
            ?: _activeProgress.value[versionId]?.downloadedBytes?.takeIf { it > 0L }
        val prevMeta = getMetadata(versionId) ?: VersionInstallMetadata(versionId = versionId)
        upsertMetadata(
            prevMeta.copy(
                installedAt = verification.installedAt,
                diskSizeBytes = verification.diskSizeBytes,
                downloadSizeBytes = lastDownloadBytes ?: prevMeta.downloadSizeBytes,
                installedManifestSha1 = verification.installedManifestSha1,
                updateAvailable = prevMeta.remoteManifestSha1 != null &&
                    verification.installedManifestSha1 != null &&
                    !prevMeta.remoteManifestSha1.equals(verification.installedManifestSha1, ignoreCase = true),
                lastErrorMessage = null,
                lastErrorCategory = null,
                lastDiagnosticDetails = null,
                wasInterrupted = false
            )
        )

        _activeProgress.update { map ->
            val prev = map[versionId] ?: InstallationProgress(versionId = versionId, stage = InstallationStage.COMPLETED, startedAt = startedAt)
            map + (versionId to prev.copy(
                stage = InstallationStage.COMPLETED,
                progressPercent = prev.totalBytes?.let { 100f },
                errorMessage = null,
                errorCategory = null,
                diagnosticDetails = null,
                isFinished = true,
                isCancellable = false
            ))
        }
    }

    private suspend fun handleBackendFailed(versionId: String, startedAt: Long, error: Throwable) {
        synchronized(handlesLock) {
            activeHandles.remove(versionId)
        }
        if (cancelledVersions.remove(versionId) || error is CancellationException) {
            cleanupAfterCancel(versionId)
            return
        }
        clearActiveLockIfMatches(versionId)
        versionManager.cleanupPartialFiles(versionId)

        val classified = classifyThrowable(versionId, error)
        val prevProgress = _activeProgress.value[versionId]
        val prevMeta = getMetadata(versionId) ?: VersionInstallMetadata(versionId = versionId)
        upsertMetadata(
            prevMeta.copy(
                lastErrorCategory = classified.category,
                lastErrorMessage = classified.userMessage,
                lastDiagnosticDetails = classified.diagnostics,
                lastAttemptAt = clockMillis(),
                wasInterrupted = false
            )
        )
        _activeProgress.update { map ->
            map + (versionId to (prevProgress ?: InstallationProgress(versionId = versionId, stage = InstallationStage.FETCHING_METADATA, startedAt = startedAt)).copy(
                errorMessage = classified.userMessage,
                errorCategory = classified.category,
                diagnosticDetails = classified.diagnostics,
                isFinished = true,
                isCancellable = false
            ))
        }
    }

    private suspend fun handleBackendCancelled(versionId: String) {
        synchronized(handlesLock) {
            activeHandles.remove(versionId)
            cancelledVersions.remove(versionId)
        }
        cleanupAfterCancel(versionId)
    }

    private suspend fun cleanupAfterCancel(versionId: String) {
        clearActiveLockIfMatches(versionId)
        versionManager.cleanupPartialFiles(versionId)
        _activeProgress.update { it - versionId }
    }

    private suspend fun clearActiveLockIfMatches(versionId: String) {
        val currentLock = settingsRepository.getString(KEY_ACTIVE_INSTALL_VERSION_ID, "")
        if (currentLock == versionId) {
            settingsRepository.remove(KEY_ACTIVE_INSTALL_VERSION_ID)
        }
    }

    private suspend fun emitImmediateFailure(
        versionId: String,
        category: InstallErrorCategory,
        message: String,
        diagnostics: String
    ) {
        val now = clockMillis()
        val prevMeta = getMetadata(versionId) ?: VersionInstallMetadata(versionId = versionId)
        upsertMetadata(
            prevMeta.copy(
                lastErrorCategory = category,
                lastErrorMessage = message,
                lastDiagnosticDetails = diagnostics,
                lastAttemptAt = now,
                wasInterrupted = false
            )
        )
        _activeProgress.update { map ->
            map + (versionId to InstallationProgress(
                versionId = versionId,
                stage = InstallationStage.FETCHING_METADATA,
                errorMessage = message,
                errorCategory = category,
                diagnosticDetails = diagnostics,
                startedAt = now,
                isFinished = true,
                isCancellable = false
            ))
        }
    }

    private suspend fun upsertMetadata(metadata: VersionInstallMetadata) {
        val map = parseMetadataMap(settingsRepository.getString(KEY_INSTALL_METADATA, "")).toMutableMap()
        map[metadata.versionId] = metadata
        saveMetadataMap(map)
    }

    private suspend fun saveMetadataMap(map: Map<String, VersionInstallMetadata>) {
        val array = JSONArray()
        map.values.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("versionId", item.versionId)
                    item.installedAt?.let { put("installedAt", it) }
                    item.diskSizeBytes?.let { put("diskSizeBytes", it) }
                    item.downloadSizeBytes?.let { put("downloadSizeBytes", it) }
                    put("updateAvailable", item.updateAvailable)
                    item.remoteManifestSha1?.let { put("remoteManifestSha1", it) }
                    item.installedManifestSha1?.let { put("installedManifestSha1", it) }
                    item.lastErrorMessage?.let { put("lastErrorMessage", it) }
                    item.lastErrorCategory?.let { put("lastErrorCategory", it.name) }
                    item.lastDiagnosticDetails?.let { put("lastDiagnosticDetails", it) }
                    item.lastAttemptAt?.let { put("lastAttemptAt", it) }
                    put("wasInterrupted", item.wasInterrupted)
                }
            )
        }
        settingsRepository.setString(KEY_INSTALL_METADATA, array.toString())
    }

    private fun parseMetadataMap(raw: String?): Map<String, VersionInstallMetadata> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            val result = LinkedHashMap<String, VersionInstallMetadata>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("versionId")
                if (id.isBlank()) continue
                val catName = obj.optString("lastErrorCategory").ifBlank { null }
                val category = catName?.let { runCatching { InstallErrorCategory.valueOf(it) }.getOrNull() }
                result[id] = VersionInstallMetadata(
                    versionId = id,
                    installedAt = if (obj.has("installedAt")) obj.optLong("installedAt") else null,
                    diskSizeBytes = if (obj.has("diskSizeBytes")) obj.optLong("diskSizeBytes") else null,
                    downloadSizeBytes = if (obj.has("downloadSizeBytes")) obj.optLong("downloadSizeBytes") else null,
                    updateAvailable = obj.optBoolean("updateAvailable", false),
                    remoteManifestSha1 = obj.optString("remoteManifestSha1").ifBlank { null },
                    installedManifestSha1 = obj.optString("installedManifestSha1").ifBlank { null },
                    lastErrorMessage = obj.optString("lastErrorMessage").ifBlank { null },
                    lastErrorCategory = category,
                    lastDiagnosticDetails = obj.optString("lastDiagnosticDetails").ifBlank { null },
                    lastAttemptAt = if (obj.has("lastAttemptAt")) obj.optLong("lastAttemptAt") else null,
                    wasInterrupted = obj.optBoolean("wasInterrupted", false)
                )
            }
            result
        }.getOrDefault(emptyMap())
    }

    private data class ClassifiedError(
        val category: InstallErrorCategory,
        val userMessage: String,
        val diagnostics: String
    )

    private fun classifyThrowable(versionId: String, error: Throwable): ClassifiedError {
        val chain = generateSequence(error) { it.cause }.toList()
        val combinedMessage = chain.mapNotNull { it.message }.joinToString(" | ")
        val lower = combinedMessage.lowercase()
        val stackSnippet = buildString {
            append("${error::class.java.name}: ${error.message ?: "no message"}")
            error.stackTrace.take(6).forEach { frame ->
                append("\n  at $frame")
            }
        }

        return when {
            chain.any { it is CancellationException } -> ClassifiedError(
                category = InstallErrorCategory.CANCELLED,
                userMessage = "Installation of $versionId was cancelled.",
                diagnostics = stackSnippet
            )
            lower.contains("enospc") || lower.contains("no space left") || lower.contains("disk full") -> ClassifiedError(
                category = InstallErrorCategory.INSUFFICIENT_STORAGE,
                userMessage = "Not enough storage space to finish installing Minecraft $versionId.",
                diagnostics = stackSnippet
            )
            chain.any { it is MirrorTamperedException || it is DownloadUtils.SHA1VerificationException } ||
                lower.contains("sha1") || lower.contains("checksum") -> ClassifiedError(
                category = InstallErrorCategory.CORRUPTED_FILES,
                userMessage = "Downloaded files for $versionId failed checksum verification and were discarded for safety.",
                diagnostics = stackSnippet
            )
            lower.contains("no internet") || lower.contains("network") && lower.contains("offline") ||
                chain.any { it is UnknownHostException || it is ConnectException } -> ClassifiedError(
                category = InstallErrorCategory.NETWORK_UNAVAILABLE,
                userMessage = "Unable to reach Minecraft download servers. Check your internet connection and retry.",
                diagnostics = stackSnippet
            )
            chain.any { it is InterruptedIOException || it is SocketTimeoutException || it is SocketException || it is EOFException } ||
                lower.contains("timed out") || lower.contains("connection reset") || lower.contains("stream") -> ClassifiedError(
                category = InstallErrorCategory.DOWNLOAD_INTERRUPTED,
                userMessage = "Download was interrupted before all files for $versionId finished transferring. Tap Retry to resume.",
                diagnostics = stackSnippet
            )
            chain.any { it is JSONException } ||
                lower.contains("unable to read version json") || lower.contains("malformed") -> ClassifiedError(
                category = InstallErrorCategory.INVALID_METADATA,
                userMessage = "Version metadata for $versionId could not be parsed or was unavailable.",
                diagnostics = stackSnippet
            )
            lower.contains("runtime") || lower.contains("jre") || lower.contains("java") && lower.contains("missing") -> ClassifiedError(
                category = InstallErrorCategory.MISSING_RUNTIME,
                userMessage = "Required Java runtime for $versionId is missing. Install or select a compatible runtime before launching.",
                diagnostics = stackSnippet
            )
            lower.contains("unsupported") -> ClassifiedError(
                category = InstallErrorCategory.UNSUPPORTED_VERSION,
                userMessage = "Minecraft $versionId is not supported on this device configuration.",
                diagnostics = stackSnippet
            )
            else -> ClassifiedError(
                category = InstallErrorCategory.UNKNOWN,
                userMessage = "Installation of $versionId failed: ${error.message ?: error::class.java.simpleName}",
                diagnostics = stackSnippet
            )
        }
    }

    companion object {
        const val KEY_INSTALL_METADATA = "version_install_metadata_v1"
        const val KEY_ACTIVE_INSTALL_VERSION_ID = "active_install_version_id_v1"
        const val KEY_SELECTED_VERSION_ID = "selected_version_id_v1"
        const val DEFAULT_MIN_REQUIRED_STORAGE_BYTES = 128L * 1024L * 1024L
    }
}
