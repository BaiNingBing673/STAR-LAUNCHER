package com.star.launcher.bridge

import com.star.launcher.bridge.auth.BridgeAuthenticationManager
import com.star.launcher.bridge.auth.FloodgateManager
import com.star.launcher.bridge.compat.BridgeCompatibilityEngine
import com.star.launcher.bridge.config.GeyserConfigManager
import com.star.launcher.bridge.config.LocalServerConfigManager
import com.star.launcher.bridge.diagnostics.BridgeDiagnostics
import com.star.launcher.bridge.discovery.ServerDiscoveryManager
import com.star.launcher.bridge.discovery.ServerProbeSummary
import com.star.launcher.bridge.discovery.ServerProfileManager
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.BridgeAdvancedConfig
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.BridgeCompatibilityReport
import com.star.launcher.bridge.model.BridgeDiagnosticError
import com.star.launcher.bridge.model.BridgeErrorCode
import com.star.launcher.bridge.model.BridgeLifecycleState
import com.star.launcher.bridge.model.BridgeLogEntry
import com.star.launcher.bridge.model.BridgeMode
import com.star.launcher.bridge.model.BridgeServerProfile
import com.star.launcher.bridge.model.BridgeStatusSnapshot
import com.star.launcher.bridge.model.BridgeTransportType
import com.star.launcher.bridge.model.ExternalServerSoftware
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.GeyserReleaseInfo
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.network.BridgeNetworkManager
import com.star.launcher.bridge.network.BridgePortManager
import com.star.launcher.bridge.process.BridgeConnectionMonitor
import com.star.launcher.bridge.process.BridgeHealthMonitor
import com.star.launcher.bridge.process.BridgeProcessController
import com.star.launcher.bridge.runtime.BridgeRuntimeManager
import com.star.launcher.bridge.runtime.BridgeVersionManager
import com.star.launcher.bridge.runtime.GeyserRuntimeManager
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.core.AetherSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Central coordinator for STAR Bedrock Bridge across Mode A (Local Server Bridge)
 * and Mode B (External Server Bridge).
 */
class BridgeController(
    private val paths: BridgeStoragePaths,
    private val settingsRepository: AetherSettingsRepository,
    private val runtimeManager: BridgeRuntimeManager,
    private val geyserRuntimeManager: GeyserRuntimeManager,
    private val versionManager: BridgeVersionManager,
    private val geyserConfigManager: GeyserConfigManager,
    private val localServerConfigManager: LocalServerConfigManager,
    private val networkManager: BridgeNetworkManager,
    private val portManager: BridgePortManager,
    private val floodgateManager: FloodgateManager,
    private val authManager: BridgeAuthenticationManager,
    private val processController: BridgeProcessController,
    private val healthMonitor: BridgeHealthMonitor,
    private val connectionMonitor: BridgeConnectionMonitor,
    private val profileManager: ServerProfileManager,
    private val discoveryManager: ServerDiscoveryManager,
    private val compatibilityEngine: BridgeCompatibilityEngine,
    private val logManager: BridgeLogManager,
    private val diagnostics: BridgeDiagnostics,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val lifecycleMutex = Mutex()
    private val _status = MutableStateFlow(BridgeStatusSnapshot())
    val status: StateFlow<BridgeStatusSnapshot> = _status.asStateFlow()

    val logs: StateFlow<List<BridgeLogEntry>> = logManager.logsFlow
    val profiles: Flow<List<BridgeServerProfile>> = profileManager.observeProfiles()

    private val _compatibilityReport = MutableStateFlow<BridgeCompatibilityReport?>(null)
    val compatibilityReport: StateFlow<BridgeCompatibilityReport?> = _compatibilityReport.asStateFlow()

    private val _latestGeyserRelease = MutableStateFlow<GeyserReleaseInfo?>(null)
    val latestGeyserRelease: StateFlow<GeyserReleaseInfo?> = _latestGeyserRelease.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val downloadProgress: StateFlow<Pair<Long, Long>?> = _downloadProgress.asStateFlow()

    private var monitorJob: Job? = null
    private var expectServerAlive = false
    private var expectGeyserAlive = false
    private var initialized = false

    suspend fun initialize(scope: CoroutineScope? = null) {
        lifecycleMutex.withLock {
            if (!initialized) {
                loadPersistedConfig()
                initialized = true
            }
            refreshEnvironmentSnapshotInternal(probeNetworkLatency = false)
        }
        if (scope != null) {
            ensureMonitorRunning(scope)
        }
    }

    fun startMonitoring(scope: CoroutineScope) {
        ensureMonitorRunning(scope)
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    suspend fun setMode(mode: BridgeMode) {
        _status.update { it.copy(mode = mode) }
        withContext(ioDispatcher) {
            settingsRepository.setString(KEY_BRIDGE_MODE, mode.name)
        }
        refreshEnvironmentSnapshot()
    }

    suspend fun updateLocalServerConfig(transform: (LocalServerConfig) -> LocalServerConfig) {
        val updated = transform(_status.value.localServerConfig)
        _status.update { state ->
            val syncedGeyser = state.geyserConfig.copy(remotePort = updated.javaPort)
            state.copy(localServerConfig = updated, geyserConfig = syncedGeyser)
        }
        persistLocalServerConfig(updated)
        persistGeyserConfig(_status.value.geyserConfig)
        refreshEnvironmentSnapshot()
    }

    suspend fun updateGeyserConfig(transform: (GeyserConfig) -> GeyserConfig) {
        val updated = transform(_status.value.geyserConfig)
        _status.update { it.copy(geyserConfig = updated) }
        persistGeyserConfig(updated)
        refreshEnvironmentSnapshot()
    }

    suspend fun updateAdvancedConfig(transform: (BridgeAdvancedConfig) -> BridgeAdvancedConfig) {
        val updated = transform(_status.value.advancedConfig)
        logManager.updateRetentionLimit(updated.diagnosticLogRetentionLines)
        _status.update {
            it.copy(
                advancedConfig = updated,
                geyserConfig = it.geyserConfig.copy(debugMode = updated.debugMode)
            )
        }
        persistAdvancedConfig(updated)
        persistGeyserConfig(_status.value.geyserConfig)
    }

    suspend fun refreshEnvironmentSnapshot(probeNetworkLatency: Boolean = false) {
        lifecycleMutex.withLock {
            refreshEnvironmentSnapshotInternal(probeNetworkLatency)
        }
    }

    private suspend fun refreshEnvironmentSnapshotInternal(probeNetworkLatency: Boolean) {
        withContext(ioDispatcher) {
            logManager.pollProcessLogs()
            val cur = _status.value
            val geyserRt = runtimeManager.validateGeyserRuntime(
                preferredRuntimeName = cur.geyserConfig.preferredRuntimeName,
                memoryLimitMb = cur.geyserConfig.memoryLimitMb
            )
            val serverRt = runtimeManager.validateLocalServerRuntime(
                minecraftVersion = cur.localServerConfig.minecraftVersion,
                preferredRuntimeName = cur.localServerConfig.preferredRuntimeName,
                memoryLimitMb = cur.localServerConfig.memoryLimitMb
            )

            val assessment = healthMonitor.inspectProcessHealth(
                expectServerRunning = expectServerAlive,
                expectGeyserRunning = expectGeyserAlive
            )
            var health = healthMonitor.mergeHealthSnapshot(
                cur.health,
                assessment,
                cur.localServerConfig.maxPlayers
            )
            if (probeNetworkLatency || assessment.serverAlive || assessment.geyserAlive) {
                health = connectionMonitor.probeLocalEndpoints(
                    javaPort = cur.localServerConfig.javaPort,
                    bedrockPort = cur.geyserConfig.bedrockPort,
                    current = health
                )
            }

            val activeGeyserConfig = geyserConfigManager.readActiveConfig(cur.geyserConfig)
            val endpoints = networkManager.inspectEndpoints(
                javaPort = cur.localServerConfig.javaPort,
                bedrockPort = activeGeyserConfig.bedrockPort,
                transport = activeGeyserConfig.transport,
                checkPortConflicts = true,
                localServerRunning = assessment.serverAlive,
                geyserRunning = assessment.geyserAlive
            )

            val fgReport = floodgateManager.inspectFloodgateState(cur.geyserConfig.floodgateKeyFile)
            val geyserArtifact = geyserRuntimeManager.getInstalledMetadata()
            val serverArtifact = runtimeManager.getInstalledServerMetadata()
            val floodgateArtifact = floodgateManager.getInstalledPluginMetadata()

            val derivedState = deriveLifecycleState(
                currentState = cur.lifecycleState,
                serverAlive = assessment.serverAlive,
                geyserAlive = assessment.geyserAlive,
                serverCrash = assessment.serverCrashedDetail,
                geyserCrash = assessment.geyserCrashedDetail
            )

            val updatedSnapshot = cur.copy(
                lifecycleState = derivedState,
                geyserArtifact = geyserArtifact,
                floodgateArtifact = floodgateArtifact,
                localServerArtifact = serverArtifact,
                runtimeValidation = geyserRt,
                serverRuntimeValidation = serverRt,
                networkEndpoints = endpoints,
                health = health,
                floodgateKeyPresent = fgReport.geyserKeyPresent || fgReport.serverKeyPresent,
                floodgateKeyBackedUpInVault = fgReport.backedUpInVault
            )
            _status.value = updatedSnapshot

            if (cur.mode == BridgeMode.LOCAL_HOST) {
                _compatibilityReport.value = compatibilityEngine.evaluateLocalHosting(
                    geyserRuntime = geyserRt,
                    serverRuntime = serverRt,
                    installedGeyser = geyserArtifact,
                    latestGeyserBuild = _latestGeyserRelease.value?.buildNumber,
                    localConfig = cur.localServerConfig,
                    geyserConfig = cur.geyserConfig,
                    endpoints = endpoints,
                    authManager = authManager
                )
            }
        }
    }

    suspend fun checkForGeyserUpdates(): Result<GeyserReleaseInfo> = withContext(ioDispatcher) {
        runCatching {
            val info = geyserRuntimeManager.checkLatestRelease()
            _latestGeyserRelease.value = info
            logManager.logInfo("VersionManager", "Latest GeyserMC release: ${info.version} (#${info.buildNumber})")
            info
        }.onFailure { err ->
            logManager.logWarn("VersionManager", "Failed checking Geyser updates: ${err.message}")
        }
    }

    suspend fun downloadOrUpdateGeyser(): Result<String> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            downloadOrUpdateGeyserInternal()
        }
    }

    private suspend fun downloadOrUpdateGeyserInternal(): Result<String> = runCatching {
        val alive = processController.isProcessAlive(processController.readGeyserStatus())
        if (alive) {
            throw IllegalStateException("Stop Geyser Standalone before updating its JAR.")
        }
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.INSTALLING_RUNTIME, statusMessage = "Downloading verified Geyser Standalone...") }
        val result = geyserRuntimeManager.installOrUpdateLatest(
            isGeyserRunning = { processController.isProcessAlive(processController.readGeyserStatus()) },
            onProgress = { dl, total -> _downloadProgress.value = dl to total }
        )
        _downloadProgress.value = null
        refreshEnvironmentSnapshotInternal(probeNetworkLatency = false)
        val msg = if (result.skippedDuplicateDownload) {
            "Geyser ${result.metadata.version} (#${result.metadata.buildNumber}) already verified"
        } else {
            "Installed Geyser ${result.metadata.version} (#${result.metadata.buildNumber})"
        }
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.IDLE, statusMessage = msg, lastError = null) }
        msg
    }.onFailure { err ->
        _downloadProgress.value = null
        val diag = diagnostics.createError(
            BridgeErrorCode.ARTIFACT_DOWNLOAD_FAILED,
            err.message ?: "Geyser download failed"
        )
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.ERROR, lastError = diag, statusMessage = diag.title) }
    }

    suspend fun downloadOrUpdateLocalServer(): Result<String> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            downloadOrUpdateLocalServerInternal()
        }
    }

    private suspend fun downloadOrUpdateLocalServerInternal(): Result<String> = runCatching {
        val alive = processController.isProcessAlive(processController.readServerStatus())
        if (alive) {
            throw IllegalStateException("Stop Local Java Server before updating server.jar.")
        }
        val cfg = _status.value.localServerConfig
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.INSTALLING_RUNTIME, statusMessage = "Downloading ${cfg.software.displayName} ${cfg.minecraftVersion}...") }
        val result = runtimeManager.installOrUpdateLocalServer(
            software = cfg.software,
            minecraftVersion = cfg.minecraftVersion,
            isServerRunning = { processController.isProcessAlive(processController.readServerStatus()) },
            onProgress = { dl, total -> _downloadProgress.value = dl to total }
        )
        _downloadProgress.value = null
        refreshEnvironmentSnapshotInternal(probeNetworkLatency = false)
        val msg = if (result.skippedDuplicateDownload) {
            "${cfg.software.displayName} ${result.metadata.version} already verified"
        } else {
            "Installed ${cfg.software.displayName} ${result.metadata.version}"
        }
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.IDLE, statusMessage = msg, lastError = null) }
        msg
    }.onFailure { err ->
        _downloadProgress.value = null
        val diag = diagnostics.createError(
            BridgeErrorCode.ARTIFACT_DOWNLOAD_FAILED,
            err.message ?: "Local Java server download failed"
        )
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.ERROR, lastError = diag, statusMessage = diag.title) }
    }

    suspend fun downloadOrUpdateFloodgate(): Result<String> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            downloadOrUpdateFloodgateInternal()
        }
    }

    private suspend fun downloadOrUpdateFloodgateInternal(): Result<String> = runCatching {
        val alive = processController.isProcessAlive(processController.readServerStatus())
        if (alive) {
            throw IllegalStateException("Stop Local Java Server before installing/updating Floodgate plugin.")
        }
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.INSTALLING_RUNTIME, statusMessage = "Downloading Floodgate plugin...") }
        val result = floodgateManager.installOrUpdateFloodgatePlugin(
            isServerRunning = { processController.isProcessAlive(processController.readServerStatus()) },
            onProgress = { dl, total -> _downloadProgress.value = dl to total }
        )
        _downloadProgress.value = null
        floodgateManager.synchronizeOrGenerateKeys(_status.value.geyserConfig.floodgateKeyFile)
        refreshEnvironmentSnapshotInternal(probeNetworkLatency = false)
        val msg = "Floodgate ${result.metadata.version} (#${result.metadata.buildNumber}) ready"
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.IDLE, statusMessage = msg, lastError = null) }
        msg
    }.onFailure { err ->
        _downloadProgress.value = null
        val diag = diagnostics.createError(
            BridgeErrorCode.FLOODGATE_CONFIGURATION_INVALID,
            err.message ?: "Floodgate installation failed"
        )
        _status.update { it.copy(lifecycleState = BridgeLifecycleState.ERROR, lastError = diag, statusMessage = diag.title) }
    }

    suspend fun rollbackGeyser(): Result<String> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                val restored = geyserRuntimeManager.rollbackToPrevious {
                    processController.isProcessAlive(processController.readGeyserStatus())
                }
                refreshEnvironmentSnapshotInternal(probeNetworkLatency = false)
                val msg = "Rolled back Geyser to ${restored.version}"
                _status.update { it.copy(statusMessage = msg, lastError = null) }
                msg
            }
        }
    }

    suspend fun rollbackLocalServer(): Result<String> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                val restored = runtimeManager.rollbackLocalServer {
                    processController.isProcessAlive(processController.readServerStatus())
                }
                refreshEnvironmentSnapshotInternal(probeNetworkLatency = false)
                val msg = "Rolled back Local Server to ${restored.version}"
                _status.update { it.copy(statusMessage = msg, lastError = null) }
                msg
            }
        }
    }

    suspend fun startLocalJavaServer(autoDownloadMissing: Boolean = true): Result<Unit> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                ensureLocalServerPreconditionsAndArtifacts(autoDownloadMissing)
                startLocalJavaServerInternal()
            }.onFailure { err ->
                logManager.logError("BridgeController", "Failed to start local Java server", err)
            }
        }
    }

    suspend fun startGeyserStandalone(autoDownloadMissing: Boolean = true): Result<Unit> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                if (autoDownloadMissing && !geyserRuntimeManager.isInstalled()) {
                    downloadOrUpdateGeyserInternal().getOrThrow()
                }
                startGeyserStandaloneInternal()
            }.onFailure { err ->
                logManager.logError("BridgeController", "Failed to start Geyser Standalone", err)
            }
        }
    }

    suspend fun startLocalHostingBridge(autoDownloadMissing: Boolean = true): Result<Unit> = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                ensureLocalServerPreconditionsAndArtifacts(autoDownloadMissing)
                if (autoDownloadMissing && !geyserRuntimeManager.isInstalled()) {
                    downloadOrUpdateGeyserInternal().getOrThrow()
                }
                startLocalJavaServerInternal()
                startGeyserStandaloneInternal()
            }.onFailure { err ->
                logManager.logError("BridgeController", "Failed starting Local Server Bridge", err)
            }
        }
    }

    private suspend fun ensureLocalServerPreconditionsAndArtifacts(autoDownloadMissing: Boolean) {
        val state = _status.value
        val srvCfg = state.localServerConfig
        if (!srvCfg.eulaAccepted) {
            failWithError(
                BridgeErrorCode.EULA_NOT_ACCEPTED,
                "Mojang Minecraft Server EULA is not accepted."
            )
            throw IllegalStateException("EULA not accepted")
        }
        val srvValidation = localServerConfigManager.validate(srvCfg)
        if (!srvValidation.isValid) {
            failWithError(BridgeErrorCode.CONFIGURATION_INVALID, srvValidation.issues.joinToString("; "))
            throw IllegalStateException(srvValidation.issues.first())
        }
        if (autoDownloadMissing && !runtimeManager.isLocalServerJarInstalled()) {
            downloadOrUpdateLocalServerInternal().getOrThrow()
        }
        if (autoDownloadMissing &&
            state.geyserConfig.authType == BridgeAuthMode.FLOODGATE &&
            srvCfg.software.supportsFloodgatePlugin &&
            !floodgateManager.isPluginInstalled()
        ) {
            downloadOrUpdateFloodgateInternal().getOrThrow()
        }
    }

    suspend fun stopLocalJavaServer(graceful: Boolean = true) = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            expectServerAlive = false
            processController.stopLocalServer(graceful)
            _status.update {
                it.copy(
                    lifecycleState = if (expectGeyserAlive) BridgeLifecycleState.DEGRADED else BridgeLifecycleState.STOPPING,
                    statusMessage = "Stopping Local Java Server..."
                )
            }
        }
    }

    suspend fun stopGeyserStandalone(graceful: Boolean = true) = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            expectGeyserAlive = false
            processController.stopGeyser(graceful)
            _status.update {
                it.copy(
                    lifecycleState = if (expectServerAlive) BridgeLifecycleState.DEGRADED else BridgeLifecycleState.STOPPING,
                    statusMessage = "Stopping Geyser Standalone..."
                )
            }
        }
    }

    suspend fun stopAllBridgeProcesses(graceful: Boolean = true) = lifecycleMutex.withLock {
        withContext(ioDispatcher) {
            expectServerAlive = false
            expectGeyserAlive = false
            processController.stopGeyser(graceful)
            processController.stopLocalServer(graceful)
            _status.update {
                it.copy(
                    lifecycleState = BridgeLifecycleState.STOPPING,
                    statusMessage = "Stopping Bridge processes..."
                )
            }
        }
    }

    fun sendServerCommand(command: String) {
        if (command.isBlank()) return
        logManager.logInfo("ServerCmd", "> $command")
        processController.sendServerCommand(command)
    }

    fun sendGeyserCommand(command: String) {
        if (command.isBlank()) return
        logManager.logInfo("GeyserCmd", "> $command")
        processController.sendGeyserCommand(command)
    }

    fun syncOrGenerateFloodgateKeys(): Boolean {
        val ok = floodgateManager.synchronizeOrGenerateKeys(_status.value.geyserConfig.floodgateKeyFile)
        _status.update {
            it.copy(
                floodgateKeyPresent = ok,
                floodgateKeyBackedUpInVault = true,
                statusMessage = "Floodgate key.pem synchronized & backed up in Keystore"
            )
        }
        return ok
    }

    fun importFloodgateKey(rawBytes: ByteArray): Boolean {
        val ok = floodgateManager.importExternalKeyBytes(rawBytes, _status.value.geyserConfig.floodgateKeyFile)
        if (!ok) {
            failWithError(
                BridgeErrorCode.FLOODGATE_CONFIGURATION_INVALID,
                "Imported key file is smaller than 16 bytes or unreadable."
            )
            return false
        }
        _status.update {
            it.copy(
                floodgateKeyPresent = true,
                floodgateKeyBackedUpInVault = true,
                statusMessage = "Imported Floodgate key.pem securely"
            )
        }
        return true
    }

    fun regenerateFloodgateKeys() {
        floodgateManager.regenerateKeys(_status.value.geyserConfig.floodgateKeyFile)
        _status.update {
            it.copy(
                floodgateKeyPresent = true,
                floodgateKeyBackedUpInVault = true,
                statusMessage = "Generated fresh Floodgate key.pem pair"
            )
        }
    }

    // Mode B — External Server Profile & Discovery Operations
    suspend fun addExternalProfile(
        name: String,
        description: String,
        javaAddress: String,
        javaPort: Int,
        bedrockAddress: String,
        bedrockPort: Int,
        software: ExternalServerSoftware,
        authMode: BridgeAuthMode
    ): Result<BridgeServerProfile> = withContext(ioDispatcher) {
        runCatching {
            val created = profileManager.addProfile(
                name = name,
                description = description,
                javaAddress = javaAddress,
                javaPort = javaPort,
                bedrockAddress = bedrockAddress,
                bedrockPort = bedrockPort,
                serverSoftware = software,
                authMode = authMode
            )
            logManager.logInfo("Discovery", "Added server profile '${created.name}' (Java=${created.javaAddress}:${created.javaPort}, Bedrock=${created.bedrockAddress}:${created.bedrockPort})")
            created
        }
    }

    suspend fun updateExternalProfile(profile: BridgeServerProfile): Result<BridgeServerProfile> =
        withContext(ioDispatcher) { runCatching { profileManager.updateProfile(profile) } }

    suspend fun deleteExternalProfile(profileId: String) = withContext(ioDispatcher) {
        profileManager.deleteProfile(profileId)
        logManager.logInfo("Discovery", "Deleted server profile '$profileId' (server world files untouched)")
    }

    suspend fun toggleFavoriteProfile(profileId: String) = withContext(ioDispatcher) {
        profileManager.toggleFavorite(profileId)
    }

    suspend fun duplicateProfile(profileId: String) = withContext(ioDispatcher) {
        profileManager.duplicateProfile(profileId)
    }

    suspend fun exportProfilesJson(): String = withContext(ioDispatcher) {
        profileManager.exportProfilesJson()
    }

    suspend fun importProfilesJson(rawJson: String): Result<Int> = withContext(ioDispatcher) {
        runCatching { profileManager.importProfilesJson(rawJson).size }
    }

    suspend fun probeExternalProfile(profileId: String): Result<ServerProbeSummary> = withContext(ioDispatcher) {
        runCatching {
            val profile = profileManager.getProfile(profileId)
                ?: throw IllegalArgumentException("Server profile not found: $profileId")
            logManager.logInfo("Discovery", "Probing '${profile.name}' (Java ${profile.javaAddress}:${profile.javaPort}, Bedrock ${profile.bedrockAddress}:${profile.bedrockPort})")
            val summary = discoveryManager.probeAndUpdateProfile(profile)
            val report = compatibilityEngine.evaluateExternalServer(
                profile = summary.profile,
                javaPing = summary.javaPing,
                bedrockPing = summary.bedrockPing,
                endpoints = _status.value.networkEndpoints,
                authManager = authManager,
                geyserConfig = _status.value.geyserConfig
            )
            _compatibilityReport.value = report
            _status.update {
                it.copy(
                    activeProfileId = profileId,
                    statusMessage = summary.guidanceMessage
                )
            }
            if (summary.javaPing.reachable && !summary.bedrockPing.reachable) {
                val diag = diagnostics.createError(
                    BridgeErrorCode.SERVER_NOT_GEYSER_ENABLED,
                    "Java endpoint ${profile.javaAddress}:${profile.javaPort} responded, but Bedrock UDP ${profile.bedrockAddress}:${profile.bedrockPort} did not respond.",
                    overrideResolution = summary.guidanceMessage
                )
                _status.update { it.copy(lastError = diag) }
            } else if (!summary.javaPing.reachable && !summary.bedrockPing.reachable) {
                val diag = diagnostics.createError(
                    BridgeErrorCode.JAVA_SERVER_UNREACHABLE,
                    summary.javaPing.errorMessage ?: "Server endpoints unreachable"
                )
                _status.update { it.copy(lastError = diag) }
            } else {
                _status.update { it.copy(lastError = null) }
            }
            summary
        }
    }

    fun buildDiagnosticReport(): String =
        diagnostics.buildFullReportText(_status.value, _compatibilityReport.value)

    fun exportDiagnosticBundle(): File =
        diagnostics.exportDiagnosticBundle(_status.value, _compatibilityReport.value)

    fun clearLogs() = logManager.clearLogs()

    fun dismissLastError() {
        _status.update { it.copy(lastError = null) }
    }

    private fun startLocalJavaServerInternal() {
        val state = _status.value
        val srvCfg = state.localServerConfig
        if (!srvCfg.eulaAccepted) {
            failWithError(
                BridgeErrorCode.EULA_NOT_ACCEPTED,
                "Mojang Minecraft Server EULA is not accepted."
            )
            throw IllegalStateException("EULA not accepted")
        }
        val srvValidation = localServerConfigManager.validate(srvCfg)
        if (!srvValidation.isValid) {
            failWithError(BridgeErrorCode.CONFIGURATION_INVALID, srvValidation.issues.joinToString("; "))
            throw IllegalStateException(srvValidation.issues.first())
        }
        if (!runtimeManager.isLocalServerJarInstalled()) {
            failWithError(
                BridgeErrorCode.LOCAL_SERVER_JAR_MISSING,
                "Local Java server JAR is not installed at ${paths.serverJarFile().absolutePath}.",
                overrideResolution = "Download ${srvCfg.software.displayName} ${srvCfg.minecraftVersion} in Local Hosting before starting."
            )
            throw IllegalStateException("Local server JAR missing")
        }
        val rt = runtimeManager.validateLocalServerRuntime(
            minecraftVersion = srvCfg.minecraftVersion,
            preferredRuntimeName = srvCfg.preferredRuntimeName,
            memoryLimitMb = srvCfg.memoryLimitMb
        )
        if (!rt.isCompatible || rt.selectedRuntimeName == null) {
            val code = if (rt.availableMemoryMb < srvCfg.memoryLimitMb) {
                BridgeErrorCode.INSUFFICIENT_MEMORY
            } else if (rt.selectedJavaVersion in 1 until rt.requiredJavaVersion) {
                BridgeErrorCode.JAVA_VERSION_UNSUPPORTED
            } else {
                BridgeErrorCode.BRIDGE_RUNTIME_MISSING
            }
            failWithError(code, rt.issues.joinToString("; ").ifBlank { rt.summary })
            throw IllegalStateException(rt.summary)
        }

        val currentlyAlive = processController.isProcessAlive(processController.readServerStatus())
        if (!currentlyAlive) {
            val tcpReport = portManager.checkTcpPort(srvCfg.javaPort)
            if (!tcpReport.tcpAvailable) {
                failWithError(
                    BridgeErrorCode.CONFIGURATION_INVALID,
                    tcpReport.errorDetail ?: "TCP port ${srvCfg.javaPort} is already occupied"
                )
                throw IllegalStateException("Java TCP port ${srvCfg.javaPort} occupied")
            }
        }

        if (state.geyserConfig.authType == BridgeAuthMode.FLOODGATE) {
            if (!srvCfg.software.supportsFloodgatePlugin) {
                failWithError(
                    BridgeErrorCode.FLOODGATE_CONFIGURATION_INVALID,
                    "Vanilla server does not support Floodgate plugin; switch to PaperMC or Online auth."
                )
                throw IllegalStateException("Floodgate unsupported on Vanilla")
            }
            if (!floodgateManager.isPluginInstalled()) {
                failWithError(
                    BridgeErrorCode.FLOODGATE_CONFIGURATION_INVALID,
                    "floodgate-spigot.jar is missing from server plugins/."
                )
                throw IllegalStateException("Floodgate plugin missing")
            }
            floodgateManager.synchronizeOrGenerateKeys(state.geyserConfig.floodgateKeyFile)
        }

        localServerConfigManager.writeServerConfiguration(srvCfg)
        _status.update {
            it.copy(
                lifecycleState = BridgeLifecycleState.STARTING_SERVER,
                lastError = null,
                statusMessage = "Starting ${srvCfg.software.displayName} ${srvCfg.minecraftVersion} on port ${srvCfg.javaPort}...",
                health = it.health.copy(startedAtMs = System.currentTimeMillis(), restartAttempts = 0)
            )
        }
        expectServerAlive = true
        if (!currentlyAlive) {
            processController.startLocalServer(
                runtimeName = rt.selectedRuntimeName,
                memoryMb = srvCfg.memoryLimitMb,
                customJvmArgs = srvCfg.customJvmArgs
            )
        }
    }

    private fun startGeyserStandaloneInternal() {
        val state = _status.value
        val geyserCfg = if (state.mode == BridgeMode.LOCAL_HOST) {
            state.geyserConfig.copy(
                remoteAddress = "127.0.0.1",
                remotePort = state.localServerConfig.javaPort
            )
        } else {
            state.geyserConfig
        }
        val cfgValidation = geyserConfigManager.validate(geyserCfg)
        if (!cfgValidation.isValid) {
            failWithError(BridgeErrorCode.CONFIGURATION_INVALID, cfgValidation.issues.joinToString("; "))
            throw IllegalStateException(cfgValidation.issues.first())
        }
        if (!geyserRuntimeManager.isInstalled()) {
            failWithError(
                BridgeErrorCode.BRIDGE_RUNTIME_MISSING,
                "Geyser-Standalone.jar is not installed at ${paths.geyserJarFile().absolutePath}."
            )
            throw IllegalStateException("Geyser-Standalone.jar missing")
        }
        val rt = runtimeManager.validateGeyserRuntime(
            preferredRuntimeName = geyserCfg.preferredRuntimeName,
            memoryLimitMb = geyserCfg.memoryLimitMb
        )
        if (!rt.isCompatible || rt.selectedRuntimeName == null) {
            val code = when {
                rt.availableMemoryMb < geyserCfg.memoryLimitMb -> BridgeErrorCode.INSUFFICIENT_MEMORY
                rt.selectedJavaVersion in 1 until rt.requiredJavaVersion -> BridgeErrorCode.JAVA_VERSION_UNSUPPORTED
                !rt.socketBindSupported -> BridgeErrorCode.NETWORK_PERMISSION_ERROR
                else -> BridgeErrorCode.BRIDGE_RUNTIME_MISSING
            }
            failWithError(code, rt.issues.joinToString("; ").ifBlank { rt.summary })
            throw IllegalStateException(rt.summary)
        }

        val currentlyAlive = processController.isProcessAlive(processController.readGeyserStatus())
        if (!currentlyAlive) {
            val portReport = portManager.checkBedrockPort(geyserCfg.bedrockPort)
            if (!portReport.isFullyAvailable) {
                failWithError(
                    BridgeErrorCode.BEDROCK_PORT_OCCUPIED,
                    portReport.errorDetail ?: "Bedrock port ${geyserCfg.bedrockPort} is occupied"
                )
                throw IllegalStateException("Bedrock port ${geyserCfg.bedrockPort} occupied")
            }
        }

        val authResult = if (state.mode == BridgeMode.LOCAL_HOST) {
            authManager.validateLocalHostingAuth(state.localServerConfig, geyserCfg)
        } else {
            authManager.validateExternalServerAuth(geyserCfg)
        }
        if (!authResult.isValid) {
            failWithError(BridgeErrorCode.FLOODGATE_CONFIGURATION_INVALID, authResult.errors.joinToString("; "))
            throw IllegalStateException(authResult.summary)
        }

        geyserConfigManager.writeConfig(geyserCfg)
        _status.update {
            it.copy(
                geyserConfig = geyserCfg,
                lifecycleState = BridgeLifecycleState.STARTING_GEYSER,
                lastError = null,
                statusMessage = "Starting Geyser Standalone on Bedrock port ${geyserCfg.bedrockPort}..."
            )
        }
        expectGeyserAlive = true
        if (!currentlyAlive) {
            processController.startGeyser(
                runtimeName = rt.selectedRuntimeName,
                memoryMb = geyserCfg.memoryLimitMb,
                debugMode = geyserCfg.debugMode
            )
        }
    }

    private fun failWithError(
        code: BridgeErrorCode,
        detail: String,
        overrideResolution: String? = null
    ) {
        val err = diagnostics.createError(code, detail, overrideResolution)
        logManager.logError("BridgeDiagnostics", err.formatReport())
        _status.update {
            it.copy(
                lifecycleState = BridgeLifecycleState.ERROR,
                lastError = err,
                statusMessage = err.title
            )
        }
    }

    private fun deriveLifecycleState(
        currentState: BridgeLifecycleState,
        serverAlive: Boolean,
        geyserAlive: Boolean,
        serverCrash: String?,
        geyserCrash: String?
    ): BridgeLifecycleState {
        if (currentState == BridgeLifecycleState.INSTALLING_RUNTIME) return currentState
        if (serverCrash != null || geyserCrash != null) {
            return BridgeLifecycleState.ERROR
        }
        return when {
            serverAlive && geyserAlive -> BridgeLifecycleState.RUNNING
            serverAlive || geyserAlive -> {
                if (expectServerAlive && expectGeyserAlive) BridgeLifecycleState.DEGRADED
                else BridgeLifecycleState.RUNNING
            }
            expectServerAlive || expectGeyserAlive -> currentState
            else -> BridgeLifecycleState.IDLE
        }
    }

    private fun ensureMonitorRunning(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(ioDispatcher) {
            while (isActive) {
                val active = expectServerAlive || expectGeyserAlive ||
                    _status.value.health.serverProcessAlive || _status.value.health.geyserProcessAlive
                if (active) {
                    runCatching {
                        handleMonitorTick()
                    }
                    delay(MONITOR_ACTIVE_INTERVAL_MS)
                } else {
                    delay(MONITOR_IDLE_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun handleMonitorTick() {
        lifecycleMutex.withLock {
            logManager.pollProcessLogs()
            val cur = _status.value
            val assessment = healthMonitor.inspectProcessHealth(
                expectServerRunning = expectServerAlive,
                expectGeyserRunning = expectGeyserAlive
            )
            var health = healthMonitor.mergeHealthSnapshot(
                cur.health,
                assessment,
                cur.localServerConfig.maxPlayers
            )
            if (assessment.serverAlive || assessment.geyserAlive) {
                health = connectionMonitor.probeLocalEndpoints(
                    javaPort = cur.localServerConfig.javaPort,
                    bedrockPort = cur.geyserConfig.bedrockPort,
                    current = health
                )
            }

            // Crash detection & controlled recovery
            val crashDetail = assessment.serverCrashedDetail ?: assessment.geyserCrashedDetail
            if (crashDetail != null) {
                val canRecover = cur.advancedConfig.autoCrashRecovery &&
                    cur.health.restartAttempts < cur.advancedConfig.maxCrashRestarts
                if (canRecover) {
                    val attempt = cur.health.restartAttempts + 1
                    logManager.logWarn("HealthMonitor", "Detected crash ($crashDetail); attempting recovery #$attempt")
                    _status.update {
                        it.copy(
                            health = health.copy(restartAttempts = attempt),
                            statusMessage = "Recovering from crash (attempt $attempt)..."
                        )
                    }
                    if (assessment.serverCrashedDetail != null) {
                        runCatching { startLocalJavaServerInternal() }
                    }
                    if (assessment.geyserCrashedDetail != null) {
                        runCatching { startGeyserStandaloneInternal() }
                    }
                    return
                } else {
                    if (assessment.serverCrashedDetail != null) expectServerAlive = false
                    if (assessment.geyserCrashedDetail != null) expectGeyserAlive = false
                    val errCode = if (assessment.geyserCrashedDetail != null && !health.geyserProcessAlive) {
                        BridgeErrorCode.GEYSER_STARTUP_FAILED
                    } else {
                        BridgeErrorCode.RUNTIME_PROCESS_TERMINATED
                    }
                    failWithError(errCode, crashDetail)
                    _status.update { it.copy(health = health) }
                    return
                }
            }

            val nextState = deriveLifecycleState(
                currentState = cur.lifecycleState,
                serverAlive = assessment.serverAlive,
                geyserAlive = assessment.geyserAlive,
                serverCrash = null,
                geyserCrash = null
            )
            val statusMsg = when (nextState) {
                BridgeLifecycleState.RUNNING -> {
                    if (assessment.serverAlive && assessment.geyserAlive) {
                        "Bridge active · Java :${cur.localServerConfig.javaPort} & Bedrock :${cur.geyserConfig.bedrockPort}"
                    } else if (assessment.serverAlive) {
                        "Local Java Server active on :${cur.localServerConfig.javaPort}"
                    } else {
                        "Geyser Standalone active on :${cur.geyserConfig.bedrockPort}"
                    }
                }
                BridgeLifecycleState.IDLE -> "Bridge idle"
                else -> cur.statusMessage
            }

            _status.update {
                it.copy(
                    lifecycleState = nextState,
                    health = health,
                    statusMessage = statusMsg
                )
            }
        }
    }

    private suspend fun loadPersistedConfig() {
        withContext(ioDispatcher) {
            val mode = runCatching {
                BridgeMode.valueOf(settingsRepository.getString(KEY_BRIDGE_MODE, BridgeMode.LOCAL_HOST.name))
            }.getOrDefault(BridgeMode.LOCAL_HOST)

            val localJson = settingsRepository.getString(KEY_LOCAL_SERVER_CONFIG, "")
            val localCfg = if (localJson.isNotBlank()) decodeLocalServerConfig(localJson) else LocalServerConfig()

            val geyserJson = settingsRepository.getString(KEY_GEYSER_CONFIG, "")
            val geyserCfg = if (geyserJson.isNotBlank()) decodeGeyserConfig(geyserJson) else GeyserConfig()

            val advJson = settingsRepository.getString(KEY_ADVANCED_CONFIG, "")
            val advCfg = if (advJson.isNotBlank()) decodeAdvancedConfig(advJson) else BridgeAdvancedConfig()

            logManager.updateRetentionLimit(advCfg.diagnosticLogRetentionLines)
            _status.update {
                it.copy(
                    mode = mode,
                    localServerConfig = localCfg,
                    geyserConfig = geyserCfg.copy(debugMode = advCfg.debugMode),
                    advancedConfig = advCfg
                )
            }
        }
    }

    private suspend fun persistLocalServerConfig(cfg: LocalServerConfig) {
        val json = JSONObject().apply {
            put("enabled", cfg.enabled)
            put("software", cfg.software.name)
            put("minecraftVersion", cfg.minecraftVersion)
            put("javaPort", cfg.javaPort)
            put("worldName", cfg.worldName)
            put("motd", cfg.motd)
            put("maxPlayers", cfg.maxPlayers)
            put("onlineMode", cfg.onlineMode)
            put("enforceSecureProfile", cfg.enforceSecureProfile)
            put("memoryLimitMb", cfg.memoryLimitMb)
            put("eulaAccepted", cfg.eulaAccepted)
            put("customJvmArgs", cfg.customJvmArgs)
            put("preferredRuntimeName", cfg.preferredRuntimeName ?: "")
        }.toString()
        withContext(ioDispatcher) {
            settingsRepository.setString(KEY_LOCAL_SERVER_CONFIG, json)
        }
    }

    private suspend fun persistGeyserConfig(cfg: GeyserConfig) {
        val json = JSONObject().apply {
            put("bedrockAddress", cfg.bedrockAddress)
            put("bedrockPort", cfg.bedrockPort)
            put("remoteAddress", cfg.remoteAddress)
            put("remotePort", cfg.remotePort)
            put("authType", cfg.authType.name)
            put("floodgateKeyFile", cfg.floodgateKeyFile)
            put("motd1", cfg.motd1)
            put("motd2", cfg.motd2)
            put("serverName", cfg.serverName)
            put("transport", cfg.transport.name)
            put("memoryLimitMb", cfg.memoryLimitMb)
            put("preferredRuntimeName", cfg.preferredRuntimeName ?: "")
            put("debugMode", cfg.debugMode)
        }.toString()
        withContext(ioDispatcher) {
            settingsRepository.setString(KEY_GEYSER_CONFIG, json)
        }
    }

    private suspend fun persistAdvancedConfig(cfg: BridgeAdvancedConfig) {
        val json = JSONObject().apply {
            put("autoCheckUpdates", cfg.autoCheckUpdates)
            put("autoCrashRecovery", cfg.autoCrashRecovery)
            put("maxCrashRestarts", cfg.maxCrashRestarts)
            put("diagnosticLogRetentionLines", cfg.diagnosticLogRetentionLines)
            put("debugMode", cfg.debugMode)
            put("preferIpv6", cfg.preferIpv6)
            put("allowExternalLanConnections", cfg.allowExternalLanConnections)
        }.toString()
        withContext(ioDispatcher) {
            settingsRepository.setString(KEY_ADVANCED_CONFIG, json)
        }
    }

    private fun decodeLocalServerConfig(raw: String): LocalServerConfig = runCatching {
        val obj = JSONObject(raw)
        LocalServerConfig(
            enabled = obj.optBoolean("enabled", false),
            software = runCatching { LocalServerSoftware.valueOf(obj.optString("software", "PAPER")) }
                .getOrDefault(LocalServerSoftware.PAPER),
            minecraftVersion = obj.optString("minecraftVersion", "1.21.4"),
            javaPort = obj.optInt("javaPort", LocalServerConfig.DEFAULT_JAVA_PORT),
            worldName = obj.optString("worldName", "world"),
            motd = obj.optString("motd", "STAR Launcher Cross-Play World"),
            maxPlayers = obj.optInt("maxPlayers", 10),
            onlineMode = obj.optBoolean("onlineMode", true),
            enforceSecureProfile = obj.optBoolean("enforceSecureProfile", true),
            memoryLimitMb = obj.optInt("memoryLimitMb", 1024),
            eulaAccepted = obj.optBoolean("eulaAccepted", false),
            customJvmArgs = obj.optString("customJvmArgs", ""),
            preferredRuntimeName = obj.optString("preferredRuntimeName").takeIf { it.isNotBlank() }
        )
    }.getOrDefault(LocalServerConfig())

    private fun decodeGeyserConfig(raw: String): GeyserConfig = runCatching {
        val obj = JSONObject(raw)
        GeyserConfig(
            bedrockAddress = obj.optString("bedrockAddress", "0.0.0.0"),
            bedrockPort = obj.optInt("bedrockPort", GeyserConfig.DEFAULT_BEDROCK_PORT),
            remoteAddress = obj.optString("remoteAddress", "127.0.0.1"),
            remotePort = obj.optInt("remotePort", LocalServerConfig.DEFAULT_JAVA_PORT),
            authType = runCatching { BridgeAuthMode.valueOf(obj.optString("authType", "ONLINE")) }
                .getOrDefault(BridgeAuthMode.ONLINE),
            floodgateKeyFile = obj.optString("floodgateKeyFile", "key.pem"),
            motd1 = obj.optString("motd1", "STAR Bedrock Bridge"),
            motd2 = obj.optString("motd2", "Cross-Platform Java + Bedrock World"),
            serverName = obj.optString("serverName", "STAR Bridge"),
            transport = runCatching { BridgeTransportType.valueOf(obj.optString("transport", "RAKNET")) }
                .getOrDefault(BridgeTransportType.RAKNET),
            memoryLimitMb = obj.optInt("memoryLimitMb", 384),
            preferredRuntimeName = obj.optString("preferredRuntimeName").takeIf { it.isNotBlank() },
            debugMode = obj.optBoolean("debugMode", false)
        )
    }.getOrDefault(GeyserConfig())

    private fun decodeAdvancedConfig(raw: String): BridgeAdvancedConfig = runCatching {
        val obj = JSONObject(raw)
        BridgeAdvancedConfig(
            autoCheckUpdates = obj.optBoolean("autoCheckUpdates", true),
            autoCrashRecovery = obj.optBoolean("autoCrashRecovery", true),
            maxCrashRestarts = obj.optInt("maxCrashRestarts", 2),
            diagnosticLogRetentionLines = obj.optInt("diagnosticLogRetentionLines", 800),
            debugMode = obj.optBoolean("debugMode", false),
            preferIpv6 = obj.optBoolean("preferIpv6", false),
            allowExternalLanConnections = obj.optBoolean("allowExternalLanConnections", true)
        )
    }.getOrDefault(BridgeAdvancedConfig())

    companion object {
        const val KEY_BRIDGE_MODE = "star_bridge_mode"
        const val KEY_LOCAL_SERVER_CONFIG = "star_bridge_local_server_config"
        const val KEY_GEYSER_CONFIG = "star_bridge_geyser_config"
        const val KEY_ADVANCED_CONFIG = "star_bridge_advanced_config"

        private const val MONITOR_ACTIVE_INTERVAL_MS = 2500L
        private const val MONITOR_IDLE_INTERVAL_MS = 10_000L
    }
}
