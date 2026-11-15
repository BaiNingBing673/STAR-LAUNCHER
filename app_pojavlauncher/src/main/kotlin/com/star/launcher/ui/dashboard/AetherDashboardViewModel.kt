package com.star.launcher.ui.dashboard

import android.content.Context
import com.star.launcher.account.AccountRepository
import com.star.launcher.account.AetherAccountSummary
import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.CompatibilityTarget
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.compat.TieredCompatibilityReport
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.db.entity.DiagnosticLogEntity
import com.star.launcher.data.db.entity.DownloadTaskEntity
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameSessionEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.DownloadTaskType
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.CrossProcessSpoolBridge
import com.star.launcher.diagnostics.DiagnosticExporter
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.download.DownloadManager
import com.star.launcher.engine.adapter.AetherLaunchPayload
import com.star.launcher.engine.adapter.NativeEngineAdapter
import com.star.launcher.engine.adapter.PojavLaunchBridge
import com.star.launcher.engine.adapter.RendererFacade
import com.star.launcher.engine.adapter.RuntimeFacade
import com.star.launcher.instance.InstanceContentManager
import com.star.launcher.instance.InstanceContentSummary
import com.star.launcher.instance.InstanceManager
import com.star.launcher.instance.LaunchPreparationResult
import com.star.launcher.ui.theme.CompatibilityBadgeTier
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.version.InstallErrorCategory
import com.star.launcher.version.InstallationProgress
import com.star.launcher.version.InstallationStage
import com.star.launcher.version.VersionCatalogItem
import com.star.launcher.version.VersionCatalogService
import com.star.launcher.version.VersionFilter
import com.star.launcher.version.VersionInstallCoordinator
import com.star.launcher.version.VersionInstallMetadata
import com.star.launcher.version.VersionManager
import com.star.launcher.version.VersionSort
import com.star.launcher.version.VersionState
import com.star.launcher.version.VersionVerificationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.NewJREUtil
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles

enum class DashboardTab(val title: String) {
    HOME("Home"),
    LIBRARY("Library"),
    VERSIONS("Versions"),
    MODS("Mods"),
    COMMUNITY("Community"),
    SETTINGS("Settings")
}

data class RuntimeInspectionItem(
    val name: String,
    val javaVersion: Int,
    val arch: String,
    val status: MultiRTUtils.RuntimeValidationStatus,
    val reason: String
)

data class DownloadableRuntimeOption(
    val name: String,
    val majorVersion: Int,
    val isInstalled: Boolean,
    val isSupportedOnDevice: Boolean
)

data class AetherDashboardUiState(
    val selectedTab: DashboardTab = DashboardTab.HOME,
    val selectedInstanceId: String? = null,
    val selectedVersionId: String? = null,
    val instances: List<GameInstanceEntity> = emptyList(),
    val versions: List<GameVersionEntity> = emptyList(),
    val versionInstallProgress: Map<String, InstallationProgress> = emptyMap(),
    val versionInstallMetadata: Map<String, VersionInstallMetadata> = emptyMap(),
    val versionSearchQuery: String = "",
    val versionFilter: VersionFilter = VersionFilter.ALL,
    val versionSort: VersionSort = VersionSort.NEWEST,
    val runtimes: List<RuntimeRegistryEntity> = emptyList(),
    val runtimeInspections: List<RuntimeInspectionItem> = emptyList(),
    val downloadableRuntimes: List<DownloadableRuntimeOption> = emptyList(),
    val isDownloadingRuntimeJava: Int? = null,
    val rendererProfiles: List<RendererProfileEntity> = emptyList(),
    val accounts: List<AetherAccountSummary> = emptyList(),
    val activeDownloads: List<DownloadTaskEntity> = emptyList(),
    val recentSessions: List<GameSessionEntity> = emptyList(),
    val recentLogs: List<DiagnosticLogEntity> = emptyList(),
    val deviceCapabilities: DeviceCapabilitiesSnapshot? = null,
    val selectedCompatibilityReport: TieredCompatibilityReport? = null,
    val missingNativeLibraries: List<String> = emptyList(),
    val lastSpoolIngestedCount: Int = 0,
    val showCreateInstanceDialog: Boolean = false,
    val editingInstance: GameInstanceEntity? = null,
    val editingInstanceContent: InstanceContentSummary? = null,
    val detailInstanceContent: InstanceContentSummary? = null,
    val lastExportedReportPath: String? = null,
    val discordInviteUrl: String = DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL,
    val reducedMotionEnabled: Boolean = false,
    val ramAllocationMb: Int = LauncherPreferences.PREF_RAM_ALLOCATION,
    val statusBanner: String? = null,
    val isBusy: Boolean = false
) {
    @delegate:Transient
    val featuredInstance: GameInstanceEntity? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        instances.firstOrNull { it.instanceId == selectedInstanceId }
            ?: instances.maxByOrNull { it.lastPlayedAt ?: it.createdAt }
    }

    @delegate:Transient
    val hasSessionHistory: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        recentSessions.isNotEmpty() || instances.any { it.lastPlayedAt != null }
    }

    @delegate:Transient
    val hasModdedVersions: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        versions.any { it.modLoaderType != ModLoaderType.VANILLA }
    }

    @delegate:Transient
    private val catalogSnapshot: MemoizedCatalogSnapshot by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveCatalogSnapshot()
    }

    val versionCatalogItems: List<VersionCatalogItem>
        get() = catalogSnapshot.items

    @delegate:Transient
    val selectedVersionItem: VersionCatalogItem? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val snap = catalogSnapshot
        val catalog = snap.items
        (selectedVersionId?.let { snap.byVersionId[it] })
            ?: (featuredInstance?.versionId?.let { snap.byVersionId[it] })
            ?: snap.firstInstalled
            ?: catalog.firstOrNull()
    }

    private fun resolveCatalogSnapshot(): MemoizedCatalogSnapshot {
        val isArm64 = deviceCapabilities?.is64Bit ?: true
        val prev = lastCatalogSnapshot
        if (prev != null &&
            prev.versions === versions &&
            prev.instances === instances &&
            prev.recentSessions === recentSessions &&
            prev.runtimes === runtimes &&
            prev.versionInstallProgress === versionInstallProgress &&
            prev.versionInstallMetadata === versionInstallMetadata &&
            prev.isArm64 == isArm64
        ) {
            return prev
        }
        val built = buildVersionCatalogItems(isArm64)
        val byId = HashMap<String, VersionCatalogItem>(built.size * 2)
        var firstInstalledItem: VersionCatalogItem? = null
        for (item in built) {
            byId[item.versionId] = item
            if (firstInstalledItem == null && item.isInstalled) {
                firstInstalledItem = item
            }
        }
        return MemoizedCatalogSnapshot(
            versions = versions,
            instances = instances,
            recentSessions = recentSessions,
            runtimes = runtimes,
            versionInstallProgress = versionInstallProgress,
            versionInstallMetadata = versionInstallMetadata,
            isArm64 = isArm64,
            items = built,
            byVersionId = byId,
            firstInstalled = firstInstalledItem
        ).also { lastCatalogSnapshot = it }
    }

    private fun buildVersionCatalogItems(isArm64: Boolean): List<VersionCatalogItem> {
        val lastPlayedByVersion = HashMap<String, Long>()
        for (inst in instances) {
            val verId = inst.versionId ?: continue
            val playedAt = inst.lastPlayedAt ?: continue
            val prev = lastPlayedByVersion[verId]
            if (prev == null || playedAt > prev) {
                lastPlayedByVersion[verId] = playedAt
            }
        }
        val instanceById = instances.associateBy { it.instanceId }
        for (session in recentSessions) {
            val verId = instanceById[session.instanceId]?.versionId ?: continue
            val prev = lastPlayedByVersion[verId]
            if (prev == null || session.startedAt > prev) {
                lastPlayedByVersion[verId] = session.startedAt
            }
        }

        val installedJavaMajors = runtimes.filter { it.isVerified }.map { it.majorVersion }.toSet()

        return versions.map { entity ->
            val progress = versionInstallProgress[entity.versionId]
            val meta = versionInstallMetadata[entity.versionId]
            val unsupportedReason = when {
                !isArm64 && entity.requiredJavaMajor >= 21 ->
                    "Java ${entity.requiredJavaMajor} required by ${entity.versionId} is unsupported on 32-bit ARM devices."
                else -> null
            }
            val isActivelyInstalling = progress != null && !progress.isFinished && progress.errorCategory == null
            val hasFailure = !entity.isInstalled && (
                (progress != null && progress.isFinished && progress.errorCategory != null) ||
                    (meta?.lastErrorCategory != null && meta.lastErrorCategory != InstallErrorCategory.CANCELLED)
                )

            val state = when {
                unsupportedReason != null -> VersionState.UNSUPPORTED
                isActivelyInstalling -> VersionState.INSTALLING
                entity.isInstalled -> VersionState.INSTALLED
                hasFailure -> VersionState.FAILED
                else -> VersionState.NOT_INSTALLED
            }
            val loaderLabel = when (entity.modLoaderType) {
                ModLoaderType.VANILLA -> null
                else -> listOfNotNull(
                    entity.modLoaderType.name.lowercase().replaceFirstChar { it.uppercase() },
                    entity.modLoaderVersion
                ).joinToString(" ")
            }
            val tier = when {
                unsupportedReason != null -> CompatibilityTier.THEORETICAL
                entity.isInstalled && (installedJavaMajors.isEmpty() || installedJavaMajors.contains(entity.requiredJavaMajor)) ->
                    CompatibilityTier.VERIFIED_RUNTIME
                entity.isInstalled -> CompatibilityTier.TESTED
                else -> CompatibilityTier.DETECTED
            }
            VersionCatalogItem(
                entity = entity,
                state = state,
                progress = progress,
                metadata = meta,
                loaderType = entity.modLoaderType,
                loaderLabel = loaderLabel,
                unsupportedReason = unsupportedReason,
                updateAvailable = entity.isInstalled && (meta?.updateAvailable == true),
                lastPlayedAt = lastPlayedByVersion[entity.versionId],
                compatibilityTier = tier
            )
        }
    }

    private class MemoizedCatalogSnapshot(
        val versions: List<GameVersionEntity>,
        val instances: List<GameInstanceEntity>,
        val recentSessions: List<GameSessionEntity>,
        val runtimes: List<RuntimeRegistryEntity>,
        val versionInstallProgress: Map<String, InstallationProgress>,
        val versionInstallMetadata: Map<String, VersionInstallMetadata>,
        val isArm64: Boolean,
        val items: List<VersionCatalogItem>,
        val byVersionId: Map<String, VersionCatalogItem>,
        val firstInstalled: VersionCatalogItem?
    )

    companion object {
        @Volatile
        private var lastCatalogSnapshot: MemoizedCatalogSnapshot? = null
    }
}

/**
 * Coordinates Phase 1–3 UI state across Instance, Version, Download, Account,
 * Compatibility, Runtime/Renderer, Settings, Community, and Diagnostics managers.
 */
class AetherDashboardController(
    private val instanceManager: InstanceManager,
    private val versionManager: VersionManager,
    private val downloadManager: DownloadManager,
    private val capabilityProbe: DeviceCapabilityProbe,
    private val compatibilityEvaluator: CompatibilityEvaluator,
    private val nativeEngineAdapter: NativeEngineAdapter,
    private val spoolBridge: CrossProcessSpoolBridge,
    private val accountRepository: AccountRepository? = null,
    private val runtimeFacade: RuntimeFacade? = null,
    private val rendererFacade: RendererFacade? = null,
    private val diagnosticsManager: DiagnosticsManager? = null,
    private val versionCatalogService: VersionCatalogService? = null,
    private val instanceContentManager: InstanceContentManager? = null,
    private val diagnosticExporter: DiagnosticExporter? = null,
    private val settingsRepository: AetherSettingsRepository? = null,
    private val versionInstallCoordinator: VersionInstallCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate)
) {
    private val _uiState = MutableStateFlow(AetherDashboardUiState())
    val uiState: StateFlow<AetherDashboardUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null
    private val launchDispatchMutex = Mutex()
    private val latestLaunchRequestId = AtomicLong(0L)
    private val envRefreshMutex = Mutex()
    private val lastEnvRefreshAtMs = AtomicLong(0L)

    fun startObserving() {
        if (observeJob != null) return
        observeJob = scope.launch {
            val (savedVersionId, savedInstanceId) = runCatching {
                withContext(ioDispatcher) {
                    versionInstallCoordinator?.recoverInterruptedInstalls()
                    val vId = settingsRepository
                        ?.getString(VersionInstallCoordinator.KEY_SELECTED_VERSION_ID, "")
                        ?.ifBlank { null }
                    val iId = settingsRepository
                        ?.getString(KEY_SELECTED_INSTANCE_ID, "")
                        ?.ifBlank { null }
                    vId to iId
                }
            }.getOrDefault(null to null)

            if (savedVersionId != null || savedInstanceId != null) {
                _uiState.update { state ->
                    state.copy(
                        selectedVersionId = state.selectedVersionId ?: savedVersionId,
                        selectedInstanceId = state.selectedInstanceId ?: savedInstanceId
                    )
                }
            }

            launch {
                instanceManager.observeInstances().collectLatest { list ->
                    var selectionChangedTo: String? = null
                    var selectionUpdated = false
                    _uiState.update { state ->
                        val currentId = state.selectedInstanceId
                        val currentValid = currentId != null && list.any { it.instanceId == currentId }
                        val nextSelectedId = if (currentValid) {
                            currentId
                        } else {
                            list.maxByOrNull { it.lastPlayedAt ?: it.createdAt }?.instanceId
                        }
                        if (nextSelectedId != currentId) {
                            selectionUpdated = true
                            selectionChangedTo = nextSelectedId
                        }
                        val nextEditing = state.editingInstance?.let { editing ->
                            list.firstOrNull { it.instanceId == editing.instanceId }
                        }
                        state.copy(
                            instances = list,
                            selectedInstanceId = nextSelectedId,
                            editingInstance = nextEditing
                        )
                    }
                    if (selectionUpdated) {
                        persistSelectedInstanceId(selectionChangedTo)
                    }
                }
            }
            launch {
                versionManager.observeCatalog().collectLatest { list ->
                    _uiState.update { state ->
                        val nextSelected = state.selectedVersionId
                            ?.takeIf { selected -> list.any { it.versionId == selected } }
                            ?: state.selectedVersionId
                        state.copy(versions = list, selectedVersionId = nextSelected)
                    }
                }
            }
            versionInstallCoordinator?.let { coordinator ->
                launch {
                    coordinator.activeProgress.collectLatest { progressMap ->
                        _uiState.update { state ->
                            val finishedBannerTarget = progressMap.values.firstOrNull { progress ->
                                progress.isFinished &&
                                    state.statusBanner == "Installing Minecraft ${progress.versionId}..."
                            }
                            val nextBanner = when {
                                finishedBannerTarget == null -> state.statusBanner
                                finishedBannerTarget.errorCategory == InstallErrorCategory.CANCELLED ->
                                    "Cancelled installation of ${finishedBannerTarget.versionId}"
                                finishedBannerTarget.errorCategory != null ->
                                    finishedBannerTarget.errorMessage
                                        ?: "Installation failed for Minecraft ${finishedBannerTarget.versionId}"
                                finishedBannerTarget.stage == InstallationStage.COMPLETED ->
                                    "Installed Minecraft ${finishedBannerTarget.versionId}"
                                else -> state.statusBanner
                            }
                            state.copy(
                                versionInstallProgress = progressMap,
                                statusBanner = nextBanner
                            )
                        }
                    }
                }
                launch {
                    coordinator.observeMetadata().collectLatest { metaMap ->
                        _uiState.update { it.copy(versionInstallMetadata = metaMap) }
                    }
                }
            }
            launch {
                instanceManager.observeRuntimes().collectLatest { list ->
                    _uiState.update { it.copy(runtimes = list) }
                }
            }
            launch {
                instanceManager.observeRendererProfiles().collectLatest { list ->
                    _uiState.update { it.copy(rendererProfiles = list) }
                }
            }
            launch {
                downloadManager.observeActiveTasks().collectLatest { list ->
                    _uiState.update { it.copy(activeDownloads = list) }
                }
            }
            diagnosticsManager?.let { diag ->
                launch {
                    diag.observeRecentSessions(20).collectLatest { list ->
                        _uiState.update { it.copy(recentSessions = list) }
                    }
                }
                launch {
                    diag.observeRecentLogs(50).collectLatest { list ->
                        _uiState.update { it.copy(recentLogs = list) }
                    }
                }
            }
        }
        refreshEnvironmentAndSpools()
    }

    fun selectTab(tab: DashboardTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun openCreateInstanceDialog() {
        _uiState.update { it.copy(showCreateInstanceDialog = true) }
    }

    fun closeCreateInstanceDialog() {
        _uiState.update { it.copy(showCreateInstanceDialog = false) }
    }

    fun openInstanceSettings(instance: GameInstanceEntity) {
        _uiState.update { it.copy(editingInstance = instance, editingInstanceContent = null) }
        val contentMgr = instanceContentManager ?: return
        scope.launch(ioDispatcher) {
            val summary = runCatching { contentMgr.inspectInstanceContent(instance.gameDirPath) }.getOrNull()
            _uiState.update { it.copy(editingInstanceContent = summary) }
        }
    }

    fun closeInstanceSettings() {
        _uiState.update { it.copy(editingInstance = null, editingInstanceContent = null) }
    }

    fun inspectInstanceContent(instance: GameInstanceEntity) {
        val contentMgr = instanceContentManager ?: return
        scope.launch(ioDispatcher) {
            val summary = runCatching { contentMgr.inspectInstanceContent(instance.gameDirPath) }.getOrNull()
            _uiState.update { it.copy(detailInstanceContent = summary) }
        }
    }

    fun toggleInstanceMod(instance: GameInstanceEntity, modFileName: String) {
        val contentMgr = instanceContentManager ?: return
        scope.launch(ioDispatcher) {
            contentMgr.toggleModEnabled(instance.gameDirPath, modFileName)
            val refreshed = contentMgr.inspectInstanceContent(instance.gameDirPath)
            _uiState.update {
                it.copy(
                    editingInstanceContent = refreshed,
                    detailInstanceContent = refreshed
                )
            }
        }
    }

    fun syncRemoteCatalog(includeSnapshots: Boolean = true, includeHistorical: Boolean = true) {
        val catalogSvc = versionCatalogService ?: return
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true, statusBanner = "Syncing Mojang version catalog...") }
            runCatching {
                val result = catalogSvc.syncFullMojangCatalog(
                    includeSnapshots = includeSnapshots,
                    includeHistorical = includeHistorical
                )
                versionInstallCoordinator?.recordRemoteManifestHashes(result.remoteSha1ByVersionId)
                result.entries
            }.onSuccess { list ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Synced ${list.size} Minecraft versions")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Catalog sync failed: ${err.message}")
                }
            }
        }
    }

    fun selectVersion(versionId: String) {
        _uiState.update { it.copy(selectedVersionId = versionId) }
        scope.launch(ioDispatcher) {
            runCatching {
                settingsRepository?.setString(VersionInstallCoordinator.KEY_SELECTED_VERSION_ID, versionId)
            }
        }
    }

    fun setVersionSearchQuery(query: String) {
        _uiState.update { it.copy(versionSearchQuery = query) }
    }

    fun setVersionFilter(filter: VersionFilter) {
        _uiState.update { it.copy(versionFilter = filter) }
    }

    fun setVersionSort(sort: VersionSort) {
        _uiState.update { it.copy(versionSort = sort) }
    }

    fun installVersion(versionId: String) {
        selectVersion(versionId)
        val coordinator = versionInstallCoordinator
        if (coordinator == null) {
            prepareVersionInstall(versionId)
            return
        }
        scope.launch(ioDispatcher) {
            val started = coordinator.installVersion(versionId)
            if (!started) {
                val item = _uiState.value.versionCatalogItems.firstOrNull { it.versionId == versionId }
                if (item?.isInstalling == true) {
                    _uiState.update { it.copy(statusBanner = "Minecraft $versionId is already installing") }
                }
            } else {
                _uiState.update { it.copy(statusBanner = "Installing Minecraft $versionId...") }
            }
        }
    }

    fun cancelVersionInstall(versionId: String) {
        val coordinator = versionInstallCoordinator ?: return
        coordinator.cancelInstall(versionId)
        _uiState.update { it.copy(statusBanner = "Cancelled installation of $versionId") }
    }

    fun retryVersionInstall(versionId: String) {
        selectVersion(versionId)
        val coordinator = versionInstallCoordinator
        if (coordinator == null) {
            prepareVersionInstall(versionId)
            return
        }
        scope.launch(ioDispatcher) {
            val started = coordinator.retryInstall(versionId)
            if (started) {
                _uiState.update { it.copy(statusBanner = "Installing Minecraft $versionId...") }
            }
        }
    }

    fun verifyVersionInstallation(versionId: String) {
        val coordinator = versionInstallCoordinator ?: return
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true) }
            val result = coordinator.verifyInstalledVersion(versionId)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusBanner = if (result.isValid) {
                        "Verified Minecraft $versionId installation integrity"
                    } else {
                        result.failureMessage ?: "Verification failed for $versionId"
                    }
                )
            }
        }
    }

    fun deleteInstalledVersion(versionId: String) {
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true) }
            runCatching {
                versionManager.deleteInstalledVersion(versionId)
                versionInstallCoordinator?.clearErrorState(versionId)
            }.onSuccess {
                _uiState.update { it.copy(isBusy = false, statusBanner = "Removed local files for $versionId") }
            }.onFailure { err ->
                _uiState.update { it.copy(isBusy = false, statusBanner = "Failed to remove $versionId: ${err.message}") }
            }
        }
    }

    fun reportVersionVerificationFailure(versionId: String, result: VersionVerificationResult) {
        _uiState.update {
            it.copy(
                statusBanner = result.failureMessage
                    ?: "Minecraft $versionId is incomplete or corrupted. Tap Retry to repair."
            )
        }
    }

    fun reportLaunchError(versionId: String, error: Throwable) {
        _uiState.update {
            it.copy(
                statusBanner = "Unable to launch $versionId: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    fun playVersion(
        versionId: String,
        onReady: (LaunchPreparationResult.Ready, AetherLaunchPayload) -> Unit = { _, _ -> }
    ) {
        val item = _uiState.value.versionCatalogItems.firstOrNull { it.versionId == versionId }
        if (item == null || !item.isInstalled) {
            _uiState.update {
                it.copy(
                    statusBanner = when (item?.state) {
                        VersionState.NOT_INSTALLED -> "Minecraft $versionId is not installed yet. Tap Install first."
                        VersionState.INSTALLING -> "Minecraft $versionId is currently installing."
                        VersionState.FAILED -> "Minecraft $versionId installation failed. Tap Retry before launching."
                        VersionState.UNSUPPORTED -> item.unsupportedReason ?: "Minecraft $versionId is unsupported on this device."
                        else -> "Select an installed Minecraft version before launching."
                    }
                )
            }
            return
        }
        selectVersion(versionId)
        val requestToken = latestLaunchRequestId.incrementAndGet()
        scope.launch(ioDispatcher) {
            val coordinator = versionInstallCoordinator
            if (coordinator != null) {
                val verification = coordinator.verifyInstalledVersion(versionId)
                if (!verification.isValid) {
                    _uiState.update {
                        it.copy(statusBanner = verification.failureMessage ?: "Minecraft $versionId failed verification. Tap Retry.")
                    }
                    return@launch
                }
            }

            if (requestToken != latestLaunchRequestId.get()) {
                return@launch
            }

            val currentSelectedId = _uiState.value.selectedInstanceId
            val currentSelectedDb = currentSelectedId?.let { instanceManager.getInstance(it) }
            val existingInstance = currentSelectedDb?.takeIf { it.versionId == versionId }
                ?: instanceManager.getAllInstances().firstOrNull { it.versionId == versionId }
            val instance = existingInstance ?: instanceManager.createInstance(
                name = "Minecraft $versionId",
                versionId = versionId,
                runtimeId = null,
                rendererProfileId = null
            )
            _uiState.update { it.copy(selectedInstanceId = instance.instanceId) }
            persistSelectedInstanceId(instance.instanceId)
            prepareInstanceLaunchInternal(instance.instanceId, requestToken, onReady)
        }
    }

    fun installLatestFabricForVersion(gameVersion: String) {
        val catalogSvc = versionCatalogService ?: return
        val cleanVersion = gameVersion.trim()
        if (cleanVersion.isBlank()) {
            _uiState.update { it.copy(statusBanner = "Specify a target Minecraft version first (e.g. 1.21.4)") }
            return
        }
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true, statusBanner = "Installing Fabric profile for $cleanVersion...") }
            runCatching {
                val loaders = catalogSvc.fetchFabricOrQuiltLoaders(ModLoaderType.FABRIC, cleanVersion)
                val preferred = loaders.firstOrNull { it.stable } ?: loaders.firstOrNull()
                    ?: error("No Fabric loader builds available for $cleanVersion")
                catalogSvc.installFabricOrQuiltProfile(ModLoaderType.FABRIC, cleanVersion, preferred.version)
            }.onSuccess { entity ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Installed Fabric profile '${entity.versionId}'")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Fabric install failed: ${err.message}")
                }
            }
        }
    }

    fun exportDiagnosticReport() {
        val exporter = diagnosticExporter ?: return
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true) }
            runCatching {
                exporter.exportRedactedReport()
            }.onSuccess { file ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        lastExportedReportPath = file.absolutePath,
                        statusBanner = "Exported redacted report: ${file.name}"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Export failed: ${err.message}")
                }
            }
        }
    }

    fun refreshEnvironmentAndSpools(forceRefresh: Boolean = false) {
        scope.launch(ioDispatcher) {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val prevMs = lastEnvRefreshAtMs.get()
            if (!forceRefresh && prevMs > 0L && (nowMs - prevMs) < 1_800L && _uiState.value.deviceCapabilities != null) {
                return@launch
            }
            if (!forceRefresh && prevMs > 0L && (nowMs - prevMs) < 8_000L && _uiState.value.deviceCapabilities != null) {
                // Lightweight spool + account refresh only during rapid resume transitions.
                val ingested = spoolBridge.ingestPendingSpools()
                val accounts = runCatching { accountRepository?.listAccounts().orEmpty() }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        lastSpoolIngestedCount = ingested,
                        accounts = accounts,
                        ramAllocationMb = LauncherPreferences.PREF_RAM_ALLOCATION.coerceAtLeast(256)
                    )
                }
                return@launch
            }
            if (!envRefreshMutex.tryLock()) return@launch
            val stageStart = com.star.launcher.diagnostics.StartupProfiler.beginStage()
            val wallStartMs = android.os.SystemClock.elapsedRealtime()
            try {
                _uiState.update { it.copy(isBusy = true) }
                val runtimes = runCatching { runtimeFacade?.listInstalledRuntimes().orEmpty() }.getOrDefault(emptyList())
                val renderers = runCatching { rendererFacade?.listSupportedRenderers().orEmpty() }.getOrDefault(emptyList())
                runCatching { instanceManager.syncRuntimesAndRenderers(runtimes, renderers) }
                ensureActive()

                val capabilities = capabilityProbe.probe()
                val missingLibs = nativeEngineAdapter.verifyEngineLibrariesPresent()
                val ingested = spoolBridge.ingestPendingSpools()
                val versions = versionManager.scanInstalledVersions()
                versionInstallCoordinator?.let { coordinator ->
                    for (installed in versions.filter { it.isInstalled }) {
                        val existingMeta = coordinator.getMetadata(installed.versionId)
                        if (existingMeta?.installedAt == null || existingMeta.diskSizeBytes == null) {
                            val verif = versionManager.verifyInstallation(
                                versionId = installed.versionId,
                                requireAssetIndex = false,
                                requireDeclaredLibraries = false
                            )
                            if (verif.isValid) {
                                coordinator.recordVerifiedMetadata(verif)
                            }
                        }
                    }
                }
                val accounts = runCatching { accountRepository?.listAccounts().orEmpty() }.getOrDefault(emptyList())
                val runtimeReports = runCatching {
                    MultiRTUtils.getInstalledRuntimes().map { rt ->
                        val report = MultiRTUtils.inspectRuntime(rt.name, 0, forceRefresh)
                        RuntimeInspectionItem(
                            name = rt.name,
                            javaVersion = rt.javaVersion,
                            arch = rt.arch ?: "unknown",
                            status = report.status,
                            reason = report.reason
                        )
                    }
                }.getOrDefault(emptyList())
                val deviceArch = Architecture.getDeviceArchitecture()
                val downloadable = NewJREUtil.ExternalRuntime.values().map { ext ->
                    DownloadableRuntimeOption(
                        name = ext.name,
                        majorVersion = ext.majorVersion,
                        isInstalled = MultiRTUtils.getExactJreName(ext.majorVersion) != null,
                        isSupportedOnDevice = !(deviceArch == Architecture.ARCH_X86 && ext.majorVersion >= 21)
                    )
                }
                val discordUrl = runCatching {
                    settingsRepository?.getString(
                        KEY_DISCORD_INVITE_URL,
                        DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL
                    ).orEmpty()
                }.getOrDefault(DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL)
                val reducedMotion = runCatching {
                    settingsRepository?.getBoolean(KEY_REDUCED_MOTION, false) ?: false
                }.getOrDefault(false)
                val savedRam = LauncherPreferences.PREF_RAM_ALLOCATION.coerceAtLeast(256)

                val defaultTarget = versions.firstOrNull()
                val report = compatibilityEvaluator.evaluate(
                    capabilities = capabilities,
                    target = CompatibilityTarget(
                        instanceId = null,
                        versionId = defaultTarget?.versionId ?: "1.21.4",
                        requiredJavaMajor = defaultTarget?.requiredJavaMajor ?: 21,
                        rendererId = renderers.firstOrNull()?.rendererId ?: "opengles_mobileglues",
                        modLoaderType = defaultTarget?.modLoaderType ?: ModLoaderType.VANILLA,
                        selectedRuntimeId = runtimes.firstOrNull()?.runtimeId,
                        selectedRuntimeJavaMajor = runtimes.firstOrNull()?.majorJavaVersion
                    )
                )
                lastEnvRefreshAtMs.set(android.os.SystemClock.elapsedRealtime())
                _uiState.update {
                    it.copy(
                        deviceCapabilities = capabilities,
                        selectedCompatibilityReport = report,
                        missingNativeLibraries = missingLibs,
                        lastSpoolIngestedCount = ingested,
                        accounts = accounts,
                        runtimeInspections = runtimeReports,
                        downloadableRuntimes = downloadable,
                        discordInviteUrl = discordUrl,
                        reducedMotionEnabled = reducedMotion,
                        ramAllocationMb = savedRam,
                        isBusy = false
                    )
                }
            } finally {
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - wallStartMs
                com.star.launcher.diagnostics.StartupProfiler.endStage("environment_refresh", stageStart)
                com.star.launcher.diagnostics.PerformanceTelemetryTracker.markStartupCompleted()
                com.star.launcher.diagnostics.PerformanceTelemetryTracker.recordBackgroundTask("env_refresh", elapsedMs)
                envRefreshMutex.unlock()
            }
        }
    }

    fun createInstance(
        name: String,
        versionId: String?,
        runtimeId: String? = null,
        rendererProfileId: String? = null,
        allocatedRamMb: Int = InstanceManager.DEFAULT_RAM_MB,
        customJvmArgs: String? = null
    ) {
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true, showCreateInstanceDialog = false) }
            val created = instanceManager.createInstance(
                name = name,
                versionId = versionId,
                runtimeId = runtimeId,
                rendererProfileId = rendererProfileId,
                allocatedRamMb = allocatedRamMb,
                customJvmArgs = customJvmArgs
            )
            persistSelectedInstanceId(created.instanceId)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    selectedInstanceId = created.instanceId,
                    statusBanner = "Created isolated instance '${created.name}'"
                )
            }
        }
    }

    fun saveInstanceSettings(
        instanceId: String,
        name: String,
        versionId: String?,
        runtimeId: String?,
        rendererProfileId: String?,
        allocatedRamMb: Int,
        customJvmArgs: String?,
        controlMapPath: String?
    ) {
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true, editingInstance = null) }
            val result = runCatching {
                instanceManager.updateInstanceConfig(
                    instanceId = instanceId,
                    name = name,
                    versionId = versionId,
                    runtimeId = runtimeId,
                    rendererProfileId = rendererProfileId,
                    allocatedRamMb = allocatedRamMb,
                    customJvmArgs = customJvmArgs,
                    controlMapPath = controlMapPath
                )
            }
            result.onSuccess { updated ->
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        selectedInstanceId = state.selectedInstanceId ?: updated.instanceId,
                        statusBanner = "Saved settings for '${updated.name}'"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = "Unable to update profile settings: ${err.message}"
                    )
                }
            }
        }
    }

    fun deleteInstance(instanceId: String, deleteAetherOwnedFiles: Boolean = true) {
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true, editingInstance = null) }
            val deleted = instanceManager.deleteInstance(instanceId, deleteAetherOwnedFiles = deleteAetherOwnedFiles)
            if (!deleted) {
                val blockedByLaunch = instanceManager.isLaunchPreparationActive(instanceId)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = if (blockedByLaunch) {
                            "Cannot delete profile while a launch is being prepared."
                        } else {
                            "Profile '$instanceId' could not be deleted because it no longer exists."
                        }
                    )
                }
                return@launch
            }
            val nextSelected = if (_uiState.value.selectedInstanceId == instanceId) {
                instanceManager.findFallbackInstance(excludedInstanceId = instanceId)?.instanceId
            } else {
                _uiState.value.selectedInstanceId
            }
            if (nextSelected != _uiState.value.selectedInstanceId) {
                persistSelectedInstanceId(nextSelected)
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    selectedInstanceId = nextSelected,
                    statusBanner = "Removed instance '$instanceId'"
                )
            }
        }
    }

    fun importLegacyProfilesReadOnly() {
        scope.launch(ioDispatcher) {
            _uiState.update { it.copy(isBusy = true) }
            val count = runCatching {
                LauncherProfiles.load()
                val profiles = LauncherProfiles.mainProfileJson?.profiles ?: emptyMap()
                instanceManager.importLegacyProfilesReadOnly(profiles)
            }.getOrElse { err ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Legacy scan skipped: ${err.message}")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusBanner = "Imported $count legacy profile(s) read-only"
                )
            }
        }
    }

    fun evaluateInstanceCompatibility(instance: GameInstanceEntity) {
        scope.launch(ioDispatcher) {
            ensureActive()
            val capabilities = capabilityProbe.probe()
            val version = instance.versionId?.let { versionManager.getVersion(it) }
            val runtime = uiState.value.runtimes.firstOrNull { it.runtimeId == instance.runtimeId }
            val profile = uiState.value.rendererProfiles.firstOrNull { it.profileId == instance.rendererProfileId }
            val report = compatibilityEvaluator.evaluate(
                capabilities = capabilities,
                target = CompatibilityTarget(
                    instanceId = instance.instanceId,
                    versionId = instance.versionId ?: "unknown",
                    requiredJavaMajor = version?.requiredJavaMajor ?: 8,
                    rendererId = profile?.rendererId ?: "opengles_mobileglues",
                    modLoaderType = version?.modLoaderType ?: ModLoaderType.VANILLA,
                    selectedRuntimeId = runtime?.runtimeId,
                    selectedRuntimeJavaMajor = runtime?.majorVersion
                )
            )
            _uiState.update {
                it.copy(
                    deviceCapabilities = capabilities,
                    selectedCompatibilityReport = report,
                    selectedTab = DashboardTab.SETTINGS,
                    statusBanner = "Evaluated '${instance.name}': ${report.theoretical.status}"
                )
            }
        }
    }

    fun selectAccount(username: String) {
        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            repo.selectAccount(username)
            val refreshed = repo.listAccounts()
            _uiState.update {
                it.copy(
                    accounts = refreshed,
                    statusBanner = "Active account set to '$username'"
                )
            }
        }
    }

    fun mirrorAccountToVault(username: String) {
        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            val backedUp = repo.mirrorSecretsToVault(username)
            val refreshed = repo.listAccounts()
            _uiState.update {
                it.copy(
                    accounts = refreshed,
                    statusBanner = if (backedUp) {
                        "Encrypted tokens for '$username' stored in Keystore Vault"
                    } else {
                        "No non-local tokens found for '$username' to encrypt"
                    }
                )
            }
        }
    }

    fun createOfflineAccount(rawUsername: String) {
        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            val created = runCatching { repo.createOfflineAccount(rawUsername) }.getOrElse { err ->
                _uiState.update { it.copy(statusBanner = "Invalid username: ${err.message}") }
                return@launch
            }
            val refreshed = repo.listAccounts()
            _uiState.update {
                it.copy(
                    accounts = refreshed,
                    statusBanner = "Created offline account '${created.username}'"
                )
            }
        }
    }

    fun enqueueAndExecuteDownload(
        taskType: DownloadTaskType,
        sourceUrl: String,
        targetPath: String,
        expectedSha1: String? = null,
        instanceId: String? = null,
        versionId: String? = null
    ) {
        scope.launch(ioDispatcher) {
            val task = downloadManager.enqueue(
                taskType = taskType,
                sourceUrl = sourceUrl,
                targetPath = targetPath,
                expectedSha1 = expectedSha1,
                instanceId = instanceId,
                versionId = versionId
            )
            _uiState.update { it.copy(statusBanner = "Queued download ${task.taskId}") }
            downloadManager.executeTask(task.taskId)
        }
    }

    fun retryDownload(taskId: String) {
        scope.launch(ioDispatcher) {
            downloadManager.executeTask(taskId)
        }
    }

    fun prepareVersionInstall(versionId: String) {
        val catalogSvc = versionCatalogService
        scope.launch(ioDispatcher) {
            val existing = _uiState.value.versions.firstOrNull { it.versionId == versionId }
            if (existing != null && existing.isInstalled) {
                _uiState.update {
                    it.copy(statusBanner = "Version $versionId is ready in local catalog")
                }
                return@launch
            }
            if (catalogSvc == null) {
                _uiState.update { it.copy(statusBanner = "Preparing version $versionId...") }
                return@launch
            }
            _uiState.update {
                it.copy(isBusy = true, statusBanner = "Preparing Minecraft $versionId...")
            }
            runCatching {
                catalogSvc.syncMojangManifestCatalog(includeSnapshots = true)
            }.onSuccess {
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Version $versionId metadata synchronized")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isBusy = false, statusBanner = "Version sync notice: ${err.message}")
                }
            }
        }
    }

    fun cancelDownload(taskId: String) {
        scope.launch(ioDispatcher) {
            downloadManager.cancelTask(taskId)
        }
    }

    fun prepareInstanceLaunch(
        instanceId: String,
        onReady: (LaunchPreparationResult.Ready, AetherLaunchPayload) -> Unit = { _, _ -> }
    ) {
        val requestToken = latestLaunchRequestId.incrementAndGet()
        scope.launch(ioDispatcher) {
            prepareInstanceLaunchInternal(instanceId, requestToken, onReady)
        }
    }

    private suspend fun prepareInstanceLaunchInternal(
        instanceId: String,
        requestToken: Long,
        onReady: (LaunchPreparationResult.Ready, AetherLaunchPayload) -> Unit
    ) {
        launchDispatchMutex.withLock {
            if (requestToken != latestLaunchRequestId.get()) {
                return
            }
            val activeAccount = accountRepository?.resolveSelectedAccountName()
            when (val res = instanceManager.prepareLaunch(instanceId, allowWarningsOrExperimental = true, fallbackIfMissing = true)) {
                is LaunchPreparationResult.Ready -> {
                    if (requestToken != latestLaunchRequestId.get()) {
                        return
                    }
                    persistSelectedInstanceId(res.spec.instanceId)
                    val payload = PojavLaunchBridge.fromSpec(
                        spec = res.spec,
                        sessionId = res.sessionId,
                        instanceName = res.instanceName,
                        accountName = activeAccount,
                        useAngle = res.useAngle
                    )
                    _uiState.update {
                        it.copy(
                            selectedInstanceId = res.spec.instanceId,
                            selectedCompatibilityReport = res.compatibility,
                            statusBanner = if (res.recoveredFromMissingProfile) {
                                "Previous profile no longer existed; switched to '${res.instanceName}' (${res.spec.versionId})..."
                            } else {
                                "Launching '${res.instanceName}' (${res.spec.versionId})..."
                            }
                        )
                    }
                    withContext(mainDispatcher) {
                        if (requestToken == latestLaunchRequestId.get()) {
                            onReady(res, payload)
                        }
                    }
                }
                is LaunchPreparationResult.Blocked -> {
                    val hasAnyProfile = instanceManager.getAllInstances().isNotEmpty()
                    val reasonText = if (!hasAnyProfile) {
                        "No profile exists. Please create or select a profile before launching."
                    } else {
                        "Launch blocked: ${res.reasons.joinToString("; ")}"
                    }
                    _uiState.update {
                        it.copy(
                            selectedCompatibilityReport = res.compatibility ?: it.selectedCompatibilityReport,
                            statusBanner = reasonText
                        )
                    }
                }
            }
        }
    }

    fun selectFeaturedInstance(instanceId: String) {
        if (instanceId.isBlank()) return
        if (_uiState.value.instances.any { it.instanceId == instanceId }) {
            _uiState.update { it.copy(selectedInstanceId = instanceId) }
        }
        scope.launch(ioDispatcher) {
            val existing = instanceManager.getInstance(instanceId)
            if (existing != null) {
                persistSelectedInstanceId(existing.instanceId)
                _uiState.update { it.copy(selectedInstanceId = existing.instanceId) }
            } else {
                val fallback = instanceManager.findFallbackInstance(excludedInstanceId = instanceId)
                persistSelectedInstanceId(fallback?.instanceId)
                _uiState.update {
                    it.copy(
                        selectedInstanceId = fallback?.instanceId,
                        statusBanner = if (fallback != null) {
                            "Selected profile no longer existed; switched to '${fallback.name}'."
                        } else {
                            "No profile exists. Please create or select a profile."
                        }
                    )
                }
            }
        }
    }

    private suspend fun persistSelectedInstanceId(instanceId: String?) {
        runCatching {
            settingsRepository?.setString(KEY_SELECTED_INSTANCE_ID, instanceId.orEmpty())
        }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    fun reportStatus(message: String) {
        _uiState.update { it.copy(statusBanner = message) }
    }

    fun downloadExternalRuntime(context: Context, majorVersion: Int) {
        if (_uiState.value.isDownloadingRuntimeJava != null) return
        val option = NewJREUtil.ExternalRuntime.values().firstOrNull { it.majorVersion == majorVersion }
            ?: run {
                _uiState.update { it.copy(statusBanner = "No External JRE package available for Java $majorVersion") }
                return
            }
        scope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    isDownloadingRuntimeJava = majorVersion,
                    isBusy = true,
                    statusBanner = "Downloading ${option.name}..."
                )
            }
            val result = runCatching { option.downloadRuntime(context) }
            _uiState.update {
                it.copy(
                    isDownloadingRuntimeJava = null,
                    isBusy = false,
                    statusBanner = result.fold(
                        onSuccess = { "Installed ${option.name} successfully" },
                        onFailure = { err -> "Failed to install ${option.name}: ${err.message}" }
                    )
                )
            }
            refreshEnvironmentAndSpools()
        }
    }

    fun deleteInstalledRuntime(runtimeName: String) {
        scope.launch(ioDispatcher) {
            val result = runCatching { MultiRTUtils.removeRuntimeNamed(runtimeName) }
            _uiState.update {
                it.copy(
                    statusBanner = result.fold(
                        onSuccess = { "Removed runtime '$runtimeName'" },
                        onFailure = { err -> "Failed to remove '$runtimeName': ${err.message}" }
                    )
                )
            }
            refreshEnvironmentAndSpools()
        }
    }

    fun saveDiscordInviteUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            scope.launch(ioDispatcher) {
                settingsRepository?.remove(KEY_DISCORD_INVITE_URL)
                _uiState.update {
                    it.copy(
                        discordInviteUrl = "",
                        statusBanner = "Cleared configured Discord community invite"
                    )
                }
            }
            return
        }
        val normalized = DiscordLinkHelper.normalizeAndValidateInviteUrl(trimmed)
        if (normalized == null) {
            _uiState.update {
                it.copy(statusBanner = "Enter a valid discord.gg or discord.com/invite link")
            }
            return
        }
        scope.launch(ioDispatcher) {
            settingsRepository?.setString(KEY_DISCORD_INVITE_URL, normalized)
            _uiState.update {
                it.copy(
                    discordInviteUrl = normalized,
                    statusBanner = "Saved Discord community invite link"
                )
            }
        }
    }

    fun setReducedMotionEnabled(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsRepository?.setBoolean(KEY_REDUCED_MOTION, enabled)
            _uiState.update {
                it.copy(
                    reducedMotionEnabled = enabled,
                    statusBanner = if (enabled) "Reduced motion enabled" else "Standard motion enabled"
                )
            }
        }
    }

    fun setGlobalRamAllocation(ramMb: Int) {
        val clamped = ramMb.coerceIn(256, 16384)
        LauncherPreferences.PREF_RAM_ALLOCATION = clamped
        LauncherPreferences.DEFAULT_PREF?.edit()?.putInt("allocation", clamped)?.apply()
        _uiState.update {
            it.copy(
                ramAllocationMb = clamped,
                statusBanner = "Default JVM allocation set to $clamped MB"
            )
        }
    }

    companion object {
        const val KEY_DISCORD_INVITE_URL = "community_discord_invite_url"
        const val KEY_REDUCED_MOTION = "ui_reduced_motion"
        const val KEY_SELECTED_INSTANCE_ID = "selected_instance_id_v1"
    }
}

fun mapStatusToBadgeTier(
    tierName: CompatibilityBadgeTier,
    status: CompatibilityStatus
): CompatibilityBadgeTier = when (status) {
    CompatibilityStatus.FAIL, CompatibilityStatus.UNSUPPORTED -> CompatibilityBadgeTier.BLOCKED
    else -> tierName
}
