package com.star.launcher.ui.dashboard

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.star.launcher.account.AccountRepository
import com.star.launcher.core.AetherContainer
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.engine.adapter.AetherLaunchPayload
import com.star.launcher.engine.adapter.EnginePreferencesAdapter
import com.star.launcher.engine.adapter.ModImportAdapter
import com.star.launcher.engine.adapter.PojavLaunchBridge
import com.star.launcher.instance.InstanceManager
import com.star.launcher.mods.ui.ModsController
import com.star.launcher.mods.ui.ModsUiState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherNavDestination
import com.star.launcher.ui.components.AetherNavigationBar
import com.star.launcher.ui.components.AetherNavigationRail
import com.star.launcher.ui.components.AetherTopAppBar
import com.star.launcher.ui.components.rememberReducedMotionEnabled
import com.star.launcher.ui.navigation.AetherDashboardStateViewModel
import com.star.launcher.ui.navigation.AetherRoute
import com.star.launcher.ui.navigation.PreferenceCategory
import com.star.launcher.ui.screens.CommunityScreen
import com.star.launcher.ui.screens.EnginePreferencesScreen
import com.star.launcher.ui.screens.HudCustomizationScreen
import com.star.launcher.ui.screens.LiveLyricsSettingsScreen
import com.star.launcher.ui.screens.GamepadMapperScreen
import com.star.launcher.ui.screens.HomeScreen
import com.star.launcher.ui.screens.InstanceDetailScreen
import com.star.launcher.ui.screens.LibraryScreen
import com.star.launcher.ui.screens.MicrosoftAuthScreen
import com.star.launcher.ui.screens.ModDetailScreen
import com.star.launcher.ui.screens.ModsScreen
import com.star.launcher.ui.screens.RuntimeDetailScreen
import com.star.launcher.ui.screens.RuntimeManagementScreen
import com.star.launcher.ui.screens.SettingsScreen
import com.star.launcher.ui.screens.VersionInstallScreen
import com.star.launcher.ui.screens.VersionsScreen
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.AetherTheme
import com.star.launcher.version.VersionFilter
import com.star.launcher.version.VersionSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import net.kdt.pojavlaunch.CustomControlsActivity
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension
import net.kdt.pojavlaunch.extra.ExtraConstants
import net.kdt.pojavlaunch.extra.ExtraCore
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener
import net.kdt.pojavlaunch.lifecycle.ContextExecutor
import net.kdt.pojavlaunch.modloaders.modpacks.ModloaderInstallTracker
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.AsyncVersionList
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import java.io.File

class AetherDashboardActivity : ComponentActivity() {
    val stateViewModel: AetherDashboardStateViewModel by viewModels()
    private var installTracker: ModloaderInstallTracker? = null

    private val modInstallerLauncher = registerForActivityResult(
        OpenDocumentWithExtension("jar")
    ) { uri ->
        if (uri != null) {
            ModImportAdapter.launchModInstallerJar(this, uri)
        } else {
            stateViewModel.controller.reportStatus("Mod installer selection cancelled")
        }
    }

    private val modpackImportLauncher = registerForActivityResult(
        OpenDocumentWithExtension(arrayOf("zip", "mrpack"))
    ) { uri ->
        if (uri != null) {
            ModImportAdapter.importModpackFromUri(
                activity = this,
                uri = uri,
                onStatus = stateViewModel.controller::reportStatus,
                onError = { err ->
                    stateViewModel.controller.reportStatus(
                        "Modpack import failed: ${err.message ?: "Unknown error"}"
                    )
                }
            )
        } else {
            stateViewModel.controller.reportStatus("Modpack import cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        com.star.launcher.compat.UiPerformanceProfileManager.ensureInitialized(this)
        com.star.launcher.diagnostics.PerformanceTelemetryTracker.ensureInitialized(this)
        Tools.initStorageConstants(this)
        ContextExecutor.setActivity(this)
        if (ExtraCore.getValue(ExtraConstants.RELEASE_TABLE) == null) {
            AsyncVersionList().getVersionList(
                { versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions) },
                false
            )
        }

        val container = AetherContainer.get(this)
        val controller = stateViewModel.controller
        val navigation = stateViewModel.navigation

        if (savedInstanceState == null) {
            navigation.applyInitialIntentRouteIfFresh(intent?.getStringExtra(EXTRA_INITIAL_ROUTE))
        }
        stateViewModel.syncControllerWithCurrentRoute()

        val backCallback = object : OnBackPressedCallback(navigation.canNavigateBack) {
            override fun handleOnBackPressed() {
                if (navigation.popBack()) {
                    stateViewModel.syncControllerWithCurrentRoute()
                }
                isEnabled = navigation.canNavigateBack
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        lifecycleScope.launch {
            navigation.backStack.collect { stack ->
                backCallback.isEnabled = stack.size > 1
            }
        }

        setContent {
            val runtimeFilePicker = rememberLauncherForActivityResult(
                contract = OpenDocumentWithExtension("xz")
            ) { uri ->
                if (uri != null) {
                    Tools.installRuntimeFromUri(this, uri)
                    controller.reportStatus("Started installing runtime archive")
                    controller.refreshEnvironmentAndSpools()
                }
            }

            LaunchedEffect(controller) {
                controller.startObserving()
            }

            val state by controller.uiState.collectAsStateWithLifecycle()
            val modsController = stateViewModel.modsController
            val modsState by modsController.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(modsController) {
                modsController.startObserving()
            }
            LaunchedEffect(modsState.pendingGuiInstaller) {
                val pending = modsState.pendingGuiInstaller ?: return@LaunchedEffect
                modsController.consumePendingGuiInstaller()
                val installerFile = java.io.File(pending.installerJarPath)
                if (installerFile.isFile) {
                    val intent = Intent(this@AetherDashboardActivity, net.kdt.pojavlaunch.JavaGUILauncherActivity::class.java).apply {
                        when (pending.loaderType) {
                            com.star.launcher.data.db.model.ModLoaderType.FORGE -> {
                                net.kdt.pojavlaunch.modloaders.ForgeUtils.addAutoInstallArgs(this, installerFile, pending.expectedVersionId)
                            }
                            com.star.launcher.data.db.model.ModLoaderType.NEOFORGE -> {
                                putExtra("javaArgs", "-jar ${installerFile.absolutePath} --install-client")
                                putExtra("openLogOutput", true)
                            }
                            else -> putExtra("javaArgs", "-jar ${installerFile.absolutePath}")
                        }
                    }
                    startActivity(intent)
                }
            }
            val backStack by navigation.backStack.collectAsStateWithLifecycle()
            val currentRoute = backStack.lastOrNull() ?: AetherRoute.Tab(DashboardTab.HOME)

            LaunchedEffect(state.featuredInstance, state.instances) {
                modsController.syncSelectedInstance(
                    selectedInstance = state.featuredInstance,
                    allInstances = state.instances
                )
            }

            AetherTheme {
                AetherDashboardScreen(
                    state = state,
                    modsState = modsState,
                    modsController = modsController,
                    currentRoute = currentRoute,
                    accountRepository = container.accountRepository,
                    onSelectTab = { tab ->
                        com.star.launcher.diagnostics.PerformanceTelemetryTracker.onNavigationStarted(tab.name.lowercase())
                        stateViewModel.onTabSelected(tab)
                    },
                    onNavigateRoute = { route ->
                        com.star.launcher.diagnostics.PerformanceTelemetryTracker.onNavigationStarted(route.ownerTab.name.lowercase())
                        navigation.push(route)
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onNavigateBack = {
                        if (navigation.popBack()) {
                            stateViewModel.syncControllerWithCurrentRoute()
                        } else {
                            finish()
                        }
                    },
                    onRefresh = controller::refreshEnvironmentAndSpools,
                    onOpenCreateInstance = controller::openCreateInstanceDialog,
                    onCloseCreateInstance = controller::closeCreateInstanceDialog,
                    onCreateInstance = { name, versionId, runtimeId, rendererId, ramMb, jvmArgs ->
                        controller.createInstance(name, versionId, runtimeId, rendererId, ramMb, jvmArgs)
                    },
                    onImportLegacy = controller::importLegacyProfilesReadOnly,
                    onSelectFeatured = controller::selectFeaturedInstance,
                    onOpenInstanceDetail = { instance ->
                        controller.inspectInstanceContent(instance)
                        navigation.push(AetherRoute.InstanceDetail(instance.instanceId))
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onOpenInstanceSettings = controller::openInstanceSettings,
                    onCloseInstanceSettings = controller::closeInstanceSettings,
                    onSaveInstanceSettings = { id, name, ver, rt, rnd, ram, jvm, ctrl ->
                        controller.saveInstanceSettings(id, name, ver, rt, rnd, ram, jvm, ctrl)
                    },
                    onDeleteInstance = { id -> controller.deleteInstance(id, deleteAetherOwnedFiles = true) },
                    onEvaluateCompat = controller::evaluateInstanceCompatibility,
                    onLaunchInstance = { instanceId ->
                        controller.prepareInstanceLaunch(instanceId) { _, payload ->
                            launchGameWithPayload(payload)
                        }
                    },
                    onCreateInstanceFromVersion = { versionId ->
                        controller.createInstance(
                            name = "MC $versionId",
                            versionId = versionId
                        )
                        stateViewModel.onTabSelected(DashboardTab.LIBRARY)
                    },
                    onOpenVersionInstallProgress = { versionId, label ->
                        if (!versionId.isNullOrBlank()) {
                            controller.installVersion(versionId)
                        }
                        navigation.push(AetherRoute.VersionInstallProgress(versionId, label))
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onSelectVersion = controller::selectVersion,
                    onInstallVersion = controller::installVersion,
                    onCancelVersionInstall = controller::cancelVersionInstall,
                    onRetryVersionInstall = controller::retryVersionInstall,
                    onPlayVersion = { versionId ->
                        controller.playVersion(versionId) { _, payload ->
                            launchGameWithPayload(payload)
                        }
                    },
                    onVerifyVersion = controller::verifyVersionInstallation,
                    onDeleteVersion = controller::deleteInstalledVersion,
                    onVersionSearchChange = controller::setVersionSearchQuery,
                    onVersionFilterChange = controller::setVersionFilter,
                    onVersionSortChange = controller::setVersionSort,
                    onRetryDownload = controller::retryDownload,
                    onCancelDownload = controller::cancelDownload,
                    onSyncRemoteCatalog = { includeSnapshots ->
                        controller.syncRemoteCatalog(
                            includeSnapshots = includeSnapshots,
                            includeHistorical = includeSnapshots
                        )
                    },
                    onInstallFabric = controller::installLatestFabricForVersion,
                    onToggleInstanceMod = controller::toggleInstanceMod,
                    onExportDiagnostics = controller::exportDiagnosticReport,
                    onSelectAccount = controller::selectAccount,
                    onMirrorAccountVault = controller::mirrorAccountToVault,
                    onCreateOfflineAccount = controller::createOfflineAccount,
                    onOpenMicrosoftLogin = {
                        navigation.push(AetherRoute.MicrosoftAuth)
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onMicrosoftLoginSuccess = { username ->
                        controller.reportStatus("Signed in as $username")
                        controller.refreshEnvironmentAndSpools()
                        if (navigation.currentRoute == AetherRoute.MicrosoftAuth) {
                            navigation.popBack()
                        }
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onDownloadRuntime = { major -> controller.downloadExternalRuntime(this, major) },
                    onDeleteRuntime = controller::deleteInstalledRuntime,
                    onSetDefaultRuntime = { runtimeName ->
                        EnginePreferencesAdapter.setSelectedRuntime(this, runtimeName)
                        controller.reportStatus("Default runtime set to $runtimeName")
                        controller.refreshEnvironmentAndSpools()
                    },
                    onImportRuntimeFile = { runtimeFilePicker.launch(null) },
                    onOpenRuntimeManagement = { openRuntimeManagement() },
                    onOpenRuntimeDetail = { runtimeName ->
                        navigation.push(AetherRoute.RuntimeDetail(runtimeName))
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onSetGlobalRam = controller::setGlobalRamAllocation,
                    onOpenCustomControls = {
                        startActivity(Intent(this, CustomControlsActivity::class.java))
                    },
                    onOpenGamepadMapper = {
                        navigation.push(AetherRoute.GamepadMapper)
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onOpenModInstaller = { launchModInstallerPicker() },
                    onOpenModpackImport = { launchModpackImportPicker() },
                    onOpenGameDirectory = {
                        val targetPath = Tools.DIR_GAME_NEW ?: Tools.DIR_GAME_HOME
                        if (targetPath != null) {
                            Tools.openPath(this, File(targetPath), false)
                        } else {
                            controller.reportStatus("Game directory is not initialized yet")
                        }
                    },
                    onShareLogs = { Tools.shareLog(this) },
                    onSaveDiscordInviteUrl = controller::saveDiscordInviteUrl,
                    onSetReducedMotion = controller::setReducedMotionEnabled,
                    onOpenEnginePreferences = {
                        navigation.push(AetherRoute.EnginePreferences(PreferenceCategory.ALL))
                        stateViewModel.syncControllerWithCurrentRoute()
                    },
                    onStatusMessage = controller::reportStatus,
                    onDismissBanner = controller::dismissStatusBanner
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val routeKey = intent.getStringExtra(EXTRA_INITIAL_ROUTE)
        if (!routeKey.isNullOrBlank()) {
            stateViewModel.navigation.navigateFromExternalRouteKey(routeKey)
            stateViewModel.syncControllerWithCurrentRoute()
        }
    }

    override fun onResume() {
        super.onResume()
        ContextExecutor.setActivity(this)
        if (installTracker == null) {
            installTracker = ModloaderInstallTracker(this)
        }
        installTracker?.attach()
        if (!firstLaunchGraphicsChecked) {
            firstLaunchGraphicsChecked = true
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val container = AetherContainer.get(this@AetherDashboardActivity)
                    com.star.launcher.compat.AdaptiveGraphicsManager.initializeDefaultsOnFirstLaunch(
                        this@AetherDashboardActivity,
                        container.capabilityProbe.probe()
                    )
                }
            }
        }
        stateViewModel.controller.refreshEnvironmentAndSpools()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.star.launcher.ui.components.AetherImageCache.trimMemory(
            aggressive = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        )
    }

    override fun onPause() {
        installTracker?.detach()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        stateViewModel.saveExtraUiState(outState)
    }

    fun openRuntimeManagement() {
        stateViewModel.navigation.push(AetherRoute.RuntimeManagement)
        stateViewModel.syncControllerWithCurrentRoute()
    }

    fun launchModInstallerPicker() {
        modInstallerLauncher.launch(null)
    }

    fun launchModpackImportPicker() {
        modpackImportLauncher.launch(null)
    }

    private fun launchGameWithPayload(payload: AetherLaunchPayload) {
        if (!InstanceManager.isValidVersionId(payload.versionId)) {
            stateViewModel.controller.reportStatus(
                "Instance '${payload.instanceName}' has no valid Minecraft version selected. Set one in Library."
            )
            return
        }
        lifecycleScope.launch {
            val container = AetherContainer.get(this@AetherDashboardActivity)
            val instanceManager = container.instanceManager
            val locked = withContext(Dispatchers.IO) {
                instanceManager.acquireLaunchPreparation(payload.instanceId)
            }
            if (!locked) {
                val fallback = withContext(Dispatchers.IO) {
                    instanceManager.findFallbackInstance(excludedInstanceId = payload.instanceId)
                }
                if (fallback != null) {
                    stateViewModel.controller.selectFeaturedInstance(fallback.instanceId)
                    stateViewModel.controller.prepareInstanceLaunch(fallback.instanceId) { _, nextPayload ->
                        launchGameWithPayload(nextPayload)
                    }
                } else {
                    stateViewModel.controller.reportStatus(
                        "The selected profile no longer exists. Please create or select a profile before launching."
                    )
                }
                return@launch
            }

            val lockReleased = AtomicBoolean(false)
            fun releaseLockAsync() {
                if (lockReleased.compareAndSet(false, true)) {
                    CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                        instanceManager.releaseLaunchPreparation(payload.instanceId)
                    }
                }
            }

            try {
                val (normalizedVersionId, mcVersion) = withContext(Dispatchers.IO) {
                    var normalized = AsyncMinecraftDownloader.normalizeVersionId(payload.versionId)
                    var listed = AsyncMinecraftDownloader.getListedVersion(normalized)
                    val localJson = File(Tools.DIR_HOME_VERSION, "$normalized/$normalized.json")
                    if (listed == null && !localJson.canRead()) {
                        AsyncVersionList.ensureReleaseTableLoadedSync()
                        normalized = AsyncMinecraftDownloader.normalizeVersionId(payload.versionId)
                        listed = AsyncMinecraftDownloader.getListedVersion(normalized)
                    }
                    normalized to listed
                }
                if (!InstanceManager.isValidVersionId(normalizedVersionId)) {
                    releaseLockAsync()
                    stateViewModel.controller.reportStatus(
                        "Unable to resolve Minecraft version '${payload.versionId}' for '${payload.instanceName}'."
                    )
                    return@launch
                }
                val verifyStart = com.star.launcher.diagnostics.StartupProfiler.beginStage()
                val verification = withContext(Dispatchers.IO) {
                    container.versionInstallCoordinator.verifyInstalledVersion(normalizedVersionId)
                }
                com.star.launcher.diagnostics.StartupProfiler.endStage("launch_verify_version", verifyStart, normalizedVersionId)
                if (!verification.isValid) {
                    releaseLockAsync()
                    stateViewModel.controller.reportVersionVerificationFailure(normalizedVersionId, verification)
                    return@launch
                }
                val currentInstance = withContext(Dispatchers.IO) {
                    instanceManager.getInstance(payload.instanceId)
                }
                if (currentInstance == null) {
                    releaseLockAsync()
                    val fallback = withContext(Dispatchers.IO) {
                        instanceManager.findFallbackInstance(excludedInstanceId = payload.instanceId)
                    }
                    if (fallback != null) {
                        stateViewModel.controller.selectFeaturedInstance(fallback.instanceId)
                        stateViewModel.controller.prepareInstanceLaunch(fallback.instanceId) { _, nextPayload ->
                            launchGameWithPayload(nextPayload)
                        }
                    } else {
                        stateViewModel.controller.reportStatus(
                            "The selected profile no longer exists. Please create or select a profile before launching."
                        )
                    }
                    return@launch
                }
                val resolvedPayload = payload.copy(
                    versionId = normalizedVersionId,
                    instanceName = currentInstance.name
                )
                val launchExtras = PojavLaunchBridge.toBundle(resolvedPayload)
                val aetherAwareListener = object : AsyncMinecraftDownloader.DoneListener {
                    private val delegate = ContextAwareDoneListener(
                        this@AetherDashboardActivity,
                        normalizedVersionId,
                        launchExtras
                    )

                    override fun onDownloadDone() {
                        releaseLockAsync()
                        delegate.onDownloadDone()
                    }

                    override fun onDownloadFailed(throwable: Throwable) {
                        releaseLockAsync()
                        runOnUiThread {
                            stateViewModel.controller.reportLaunchError(normalizedVersionId, throwable)
                        }
                    }
                }
                val downloader = MinecraftDownloader()
                downloader.setExplicitRuntimeOverride(resolvedPayload.runtimeId)
                downloader.start(
                    this@AetherDashboardActivity,
                    mcVersion,
                    normalizedVersionId,
                    aetherAwareListener
                )
            } catch (t: Throwable) {
                releaseLockAsync()
                stateViewModel.controller.reportLaunchError(payload.versionId, t)
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_ROUTE = "aether_initial_route"

        @Volatile
        private var firstLaunchGraphicsChecked = false

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, AetherDashboardActivity::class.java)

        @JvmStatic
        fun createRouteIntent(context: Context, routeKey: String): Intent =
            Intent(context, AetherDashboardActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_ROUTE, routeKey)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}

@Composable
fun AetherDashboardScreen(
    state: AetherDashboardUiState,
    modsState: ModsUiState = ModsUiState(),
    modsController: ModsController? = null,
    currentRoute: AetherRoute = AetherRoute.Tab(state.selectedTab),
    accountRepository: AccountRepository? = null,
    onSelectTab: (DashboardTab) -> Unit,
    onNavigateRoute: (AetherRoute) -> Unit = { route ->
        if (route is AetherRoute.Tab) onSelectTab(route.tab)
    },
    onNavigateBack: () -> Unit = {
        if (state.selectedTab != DashboardTab.HOME) onSelectTab(DashboardTab.HOME)
    },
    onRefresh: () -> Unit,
    onOpenCreateInstance: () -> Unit,
    onCloseCreateInstance: () -> Unit,
    onCreateInstance: (String, String?, String?, String?, Int, String?) -> Unit,
    onImportLegacy: () -> Unit,
    onSelectFeatured: (String) -> Unit,
    onOpenInstanceDetail: (GameInstanceEntity) -> Unit = {},
    onOpenInstanceSettings: (GameInstanceEntity) -> Unit,
    onCloseInstanceSettings: () -> Unit,
    onSaveInstanceSettings: (String, String, String?, String?, String?, Int, String?, String?) -> Unit,
    onDeleteInstance: (String) -> Unit,
    onEvaluateCompat: (GameInstanceEntity) -> Unit,
    onLaunchInstance: (String) -> Unit,
    onCreateInstanceFromVersion: (String) -> Unit,
    onOpenVersionInstallProgress: (String?, String?) -> Unit = { _, _ -> },
    onSelectVersion: (String) -> Unit = {},
    onInstallVersion: (String) -> Unit = {},
    onCancelVersionInstall: (String) -> Unit = {},
    onRetryVersionInstall: (String) -> Unit = {},
    onPlayVersion: (String) -> Unit = {},
    onVerifyVersion: (String) -> Unit = {},
    onDeleteVersion: (String) -> Unit = {},
    onVersionSearchChange: (String) -> Unit = {},
    onVersionFilterChange: (VersionFilter) -> Unit = {},
    onVersionSortChange: (VersionSort) -> Unit = {},
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSyncRemoteCatalog: (Boolean) -> Unit,
    onInstallFabric: (String) -> Unit,
    onToggleInstanceMod: (GameInstanceEntity, String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onMirrorAccountVault: (String) -> Unit,
    onCreateOfflineAccount: (String) -> Unit,
    onOpenMicrosoftLogin: () -> Unit,
    onMicrosoftLoginSuccess: (String) -> Unit = {},
    onDownloadRuntime: (Int) -> Unit,
    onDeleteRuntime: (String) -> Unit,
    onSetDefaultRuntime: (String) -> Unit = {},
    onImportRuntimeFile: () -> Unit,
    onOpenRuntimeManagement: () -> Unit = { onNavigateRoute(AetherRoute.RuntimeManagement) },
    onOpenRuntimeDetail: (String) -> Unit = { name -> onNavigateRoute(AetherRoute.RuntimeDetail(name)) },
    onSetGlobalRam: (Int) -> Unit,
    onOpenCustomControls: () -> Unit,
    onOpenGamepadMapper: () -> Unit,
    onOpenModInstaller: () -> Unit,
    onOpenModpackImport: () -> Unit,
    onOpenGameDirectory: () -> Unit,
    onShareLogs: () -> Unit,
    onSaveDiscordInviteUrl: (String) -> Unit,
    onSetReducedMotion: (Boolean) -> Unit,
    onOpenEnginePreferences: () -> Unit = { onNavigateRoute(AetherRoute.EnginePreferences()) },
    onOpenHudCustomization: () -> Unit = { onNavigateRoute(AetherRoute.HudCustomization) },
    onOpenLiveLyrics: () -> Unit = { onNavigateRoute(AetherRoute.LiveLyricsSettings) },
    onStatusMessage: (String) -> Unit,
    onDismissBanner: () -> Unit
) {
    if (state.showCreateInstanceDialog) {
        CreateInstanceDialog(
            versions = state.versions,
            runtimes = state.runtimes,
            rendererProfiles = state.rendererProfiles,
            onDismiss = onCloseCreateInstance,
            onCreate = onCreateInstance
        )
    }

    state.editingInstance?.let { editing ->
        InstanceSettingsDialog(
            instance = editing,
            versions = state.versions,
            runtimes = state.runtimes,
            rendererProfiles = state.rendererProfiles,
            contentSummary = state.editingInstanceContent,
            onToggleMod = { modFileName -> onToggleInstanceMod(editing, modFileName) },
            onDismiss = onCloseInstanceSettings,
            onSave = onSaveInstanceSettings,
            onDelete = { onDeleteInstance(editing.instanceId) }
        )
    }

    val destinations = remember {
        listOf(
            AetherNavDestination(DashboardTab.HOME, DashboardTab.HOME.title, AetherIcons.Home),
            AetherNavDestination(DashboardTab.LIBRARY, DashboardTab.LIBRARY.title, AetherIcons.Library),
            AetherNavDestination(DashboardTab.VERSIONS, DashboardTab.VERSIONS.title, AetherIcons.Versions),
            AetherNavDestination(DashboardTab.MODS, DashboardTab.MODS.title, AetherIcons.Mods),
            AetherNavDestination(DashboardTab.COMMUNITY, DashboardTab.COMMUNITY.title, AetherIcons.Community),
            AetherNavDestination(DashboardTab.SETTINGS, DashboardTab.SETTINGS.title, AetherIcons.Settings)
        )
    }
    val reducedMotion = rememberReducedMotionEnabled(state.reducedMotionEnabled)
    val activeAccount = state.accounts.firstOrNull { it.isSelected }?.username
    val runtimeHealthy = state.runtimes.any { it.isVerified }
    val highlightedTab = currentRoute.ownerTab

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.BackgroundDark)
    ) {
        val useRail = maxWidth >= 720.dp

        Scaffold(
            containerColor = AetherColors.BackgroundDark,
            topBar = {
                AetherTopAppBar(
                    activeAccountLabel = activeAccount,
                    runtimeHealthy = runtimeHealthy,
                    onAccountClick = { onSelectTab(DashboardTab.SETTINGS) },
                    onRuntimeClick = onOpenRuntimeManagement
                )
            },
            bottomBar = {
                if (!useRail) {
                    AetherNavigationBar(
                        destinations = destinations,
                        selected = highlightedTab,
                        onSelect = onSelectTab
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    AetherNavigationRail(
                        destinations = destinations,
                        selected = highlightedTab,
                        onSelect = onSelectTab
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (state.isBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AetherColors.AccentCyan,
                            trackColor = AetherColors.SurfaceVariantDark
                        )
                    }
                    state.statusBanner?.let { banner ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AetherSpacing.Md, vertical = AetherSpacing.Xs),
                            shape = MaterialTheme.shapes.medium,
                            color = AetherColors.SurfaceElevatedDark,
                            border = BorderStroke(1.dp, AetherColors.AccentBlue.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = banner,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AetherColors.OnSurfaceDark,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissBanner) {
                                    Text("Hide", color = AetherColors.AccentCyan)
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        LaunchedEffect(currentRoute) {
                            com.star.launcher.diagnostics.PerformanceTelemetryTracker.onNavigationSettled(currentRoute.ownerTab.name.lowercase())
                        }
                        when (currentRoute) {
                            is AetherRoute.Tab -> when (currentRoute.tab) {
                                DashboardTab.HOME -> HomeScreen(
                                    state = state,
                                    isExpandedWidth = useRail,
                                    reducedMotion = reducedMotion,
                                    onSelectFeatured = onSelectFeatured,
                                    onLaunchInstance = onLaunchInstance,
                                    onPlayVersion = onPlayVersion,
                                    onInstallVersion = onInstallVersion,
                                    onCancelVersionInstall = onCancelVersionInstall,
                                    onRetryVersionInstall = onRetryVersionInstall,
                                    onOpenVersionSelector = { onSelectTab(DashboardTab.VERSIONS) },
                                    onOpenCreateInstance = onOpenCreateInstance,
                                    onNavigateTab = onSelectTab,
                                    onInstallRecommendedRuntime = onDownloadRuntime,
                                    onOpenDiscord = { onSelectTab(DashboardTab.COMMUNITY) },
                                    onOpenAccountSettings = { onSelectTab(DashboardTab.SETTINGS) },
                                    onOpenRuntimeManagement = onOpenRuntimeManagement,
                                    onOpenInstanceDetail = { id ->
                                        state.instances.firstOrNull { it.instanceId == id }
                                            ?.let(onOpenInstanceDetail)
                                    }
                                )
                                DashboardTab.LIBRARY -> LibraryScreen(
                                    state = state,
                                    onOpenCreateInstance = onOpenCreateInstance,
                                    onImportLegacy = onImportLegacy,
                                    onSelectFeatured = onSelectFeatured,
                                    onLaunchInstance = onLaunchInstance,
                                    onOpenInstanceSettings = onOpenInstanceSettings,
                                    onEvaluateCompat = onEvaluateCompat,
                                    onOpenModInstaller = onOpenModInstaller,
                                    onOpenModpackImport = onOpenModpackImport,
                                    onOpenInstanceDetail = onOpenInstanceDetail
                                )
                                DashboardTab.VERSIONS -> VersionsScreen(
                                    state = state,
                                    isExpandedWidth = useRail,
                                    reducedMotion = reducedMotion,
                                    onSyncRemoteCatalog = onSyncRemoteCatalog,
                                    onSelectVersion = onSelectVersion,
                                    onInstallVersion = onInstallVersion,
                                    onCancelVersionInstall = onCancelVersionInstall,
                                    onRetryVersionInstall = onRetryVersionInstall,
                                    onPlayVersion = onPlayVersion,
                                    onVerifyVersion = onVerifyVersion,
                                    onDeleteVersion = onDeleteVersion,
                                    onSearchQueryChange = onVersionSearchChange,
                                    onFilterChange = onVersionFilterChange,
                                    onSortChange = onVersionSortChange,
                                    onInstallFabric = onInstallFabric,
                                    onCreateInstanceFromVersion = onCreateInstanceFromVersion,
                                    onRetryDownload = onRetryDownload,
                                    onCancelDownload = onCancelDownload,
                                    onOpenInstallProgress = onOpenVersionInstallProgress
                                )
                                DashboardTab.MODS -> ModsScreen(
                                    state = modsState,
                                    onSelectSubTab = { modsController?.selectSubTab(it) },
                                    onSelectInstance = { onSelectFeatured(it) },
                                    onOpenCreateInstance = onOpenCreateInstance,
                                    onUpdateSearchQuery = { modsController?.updateSearchQuery(it) },
                                    onSubmitSearch = { modsController?.submitSearch() },
                                    onToggleFiltersExpanded = { modsController?.toggleFiltersExpanded() },
                                    onSelectSortField = { modsController?.selectSortField(it) },
                                    onSelectCategory = { modsController?.selectCategory(it) },
                                    onSelectMcVersionFilter = { modsController?.selectMcVersionFilter(it) },
                                    onSelectLoaderFilter = { modsController?.selectLoaderFilter(it) },
                                    onSelectReleaseTypeFilter = { modsController?.selectReleaseTypeFilter(it) },
                                    onMatchFiltersToInstance = { modsController?.matchFiltersToSelectedInstance() },
                                    onLoadNextPage = { modsController?.loadNextPage() },
                                    onOpenModDetail = { modId ->
                                        modsController?.openModDetail(modId)
                                        onNavigateRoute(AetherRoute.ModDetail(modId))
                                    },
                                    onCancelDownload = { modsController?.cancelDownload(it) },
                                    onRetryLastInstall = { modsController?.retryLastInstall() },
                                    onDismissProgressItem = { modsController?.dismissProgressItem(it) },
                                    onConfirmPlanInstall = { modsController?.confirmInstallPlan() },
                                    onDismissPlanDialog = { modsController?.dismissPlanDialog() },
                                    onDismissRestrictedNotice = { modsController?.dismissRestrictedNotice() },
                                    onClearBanners = { modsController?.clearBanners() },
                                    onRefreshInventory = { modsController?.refreshInstalledInventory() },
                                    onUpdateInventorySearch = { modsController?.updateInventorySearchQuery(it) },
                                    onToggleModEnabled = { modsController?.toggleModEnabled(it) },
                                    onRequestDeleteMod = { modsController?.requestDeleteMod(it) },
                                    onConfirmDeleteMod = { modsController?.confirmDeleteMod() },
                                    onDismissDeleteMod = { modsController?.dismissDeleteMod() },
                                    onCheckUpdates = { modsController?.checkInstalledUpdates() },
                                    onRequestUpdateMod = { modsController?.requestUpdateMod(it) },
                                    onConfirmUpdateMod = { modsController?.confirmUpdateMod() },
                                    onDismissUpdateMod = { modsController?.dismissUpdateMod() },
                                    onOpenCredentialConfig = { modsController?.openCredentialConfigDialog() },
                                    onDismissCredentialConfig = { modsController?.dismissCredentialConfigDialog() },
                                    onSaveDeveloperKey = { modsController?.saveDeveloperKey(it) },
                                    onSaveProxyConfig = { url, token -> modsController?.saveProxyConfig(url, token) },
                                    onClearCredentials = { modsController?.clearCredentials() },
                                    onTestApiConnection = { modsController?.testApiConnection() },
                                    onOpenInstallLoader = { preferred -> modsController?.openInstallLoaderDialog(preferred) },
                                    onDismissInstallLoader = { modsController?.dismissInstallLoaderDialog() },
                                    onSelectInstallLoaderType = { modsController?.selectInstallLoaderType(it) },
                                    onSelectInstallLoaderCandidate = { modsController?.selectInstallLoaderCandidate(it) },
                                    onRefreshInstallLoaderCandidates = { modsController?.refreshInstallLoaderCandidates() },
                                    onStartModLoaderInstall = {
                                        modsController?.startModLoaderInstall()
                                        onRefresh()
                                    },
                                    onCancelModLoaderInstall = { modsController?.cancelModLoaderInstall() }
                                )
                                DashboardTab.COMMUNITY -> CommunityScreen(
                                    state = state,
                                    onSaveDiscordInviteUrl = onSaveDiscordInviteUrl,
                                    onStatusMessage = onStatusMessage,
                                    onOpenModInstaller = onOpenModInstaller,
                                    onOpenModpackImport = onOpenModpackImport,
                                    onOpenGameDirectory = onOpenGameDirectory,
                                    onShareLogs = onShareLogs
                                )
                                DashboardTab.SETTINGS -> SettingsScreen(
                                    state = state,
                                    onSelectAccount = onSelectAccount,
                                    onMirrorAccountVault = onMirrorAccountVault,
                                    onCreateOfflineAccount = onCreateOfflineAccount,
                                    onOpenMicrosoftLogin = onOpenMicrosoftLogin,
                                    onDownloadRuntime = onDownloadRuntime,
                                    onDeleteRuntime = onDeleteRuntime,
                                    onImportRuntimeFile = onImportRuntimeFile,
                                    onOpenRuntimeManagement = onOpenRuntimeManagement,
                                    onOpenRuntimeDetail = onOpenRuntimeDetail,
                                    onSetGlobalRam = onSetGlobalRam,
                                    onOpenCustomControls = onOpenCustomControls,
                                    onOpenGamepadMapper = onOpenGamepadMapper,
                                    onSetReducedMotion = onSetReducedMotion,
                                    onExportDiagnostics = onExportDiagnostics,
                                    onShareLogs = onShareLogs,
                                    onRefreshEnvironment = onRefresh,
                                    onOpenEnginePreferences = onOpenEnginePreferences,
                                    onOpenHudCustomization = onOpenHudCustomization,
                                    onOpenLiveLyrics = onOpenLiveLyrics
                                )
                            }

                            is AetherRoute.InstanceDetail -> InstanceDetailScreen(
                                instanceId = currentRoute.instanceId,
                                state = state,
                                contentSummary = state.detailInstanceContent,
                                onBack = onNavigateBack,
                                onLaunchInstance = onLaunchInstance,
                                onEditSettings = onOpenInstanceSettings,
                                onEvaluateCompat = onEvaluateCompat,
                                onSelectFeatured = onSelectFeatured,
                                onToggleMod = onToggleInstanceMod,
                                onDeleteInstance = onDeleteInstance
                            )

                            is AetherRoute.VersionInstallProgress -> VersionInstallScreen(
                                versionId = currentRoute.versionId,
                                displayLabel = currentRoute.displayLabel,
                                state = state,
                                reducedMotion = reducedMotion,
                                onBack = onNavigateBack,
                                onInstallVersion = onInstallVersion,
                                onCancelVersionInstall = onCancelVersionInstall,
                                onRetryVersionInstall = onRetryVersionInstall,
                                onPlayVersion = onPlayVersion,
                                onRetryDownload = onRetryDownload,
                                onCancelDownload = onCancelDownload,
                                onCreateInstanceFromVersion = onCreateInstanceFromVersion,
                                onInstallFabric = onInstallFabric
                            )

                            AetherRoute.RuntimeManagement -> RuntimeManagementScreen(
                                state = state,
                                onBack = onNavigateBack,
                                onOpenRuntimeDetail = onOpenRuntimeDetail,
                                onDownloadRuntime = onDownloadRuntime,
                                onImportRuntimeFile = onImportRuntimeFile,
                                onSetDefaultRuntime = onSetDefaultRuntime,
                                onDeleteRuntime = onDeleteRuntime,
                                onRefresh = onRefresh
                            )

                            is AetherRoute.RuntimeDetail -> RuntimeDetailScreen(
                                runtimeName = currentRoute.runtimeName,
                                state = state,
                                onBack = onNavigateBack,
                                onSetDefaultRuntime = onSetDefaultRuntime,
                                onDeleteRuntime = onDeleteRuntime
                            )

                            AetherRoute.MicrosoftAuth -> MicrosoftAuthScreen(
                                accountRepository = accountRepository,
                                onBack = onNavigateBack,
                                onLoginSuccess = onMicrosoftLoginSuccess,
                                onLoginError = { msg -> onStatusMessage("Microsoft login failed: $msg") }
                            )

                            is AetherRoute.EnginePreferences -> EnginePreferencesScreen(
                                initialCategory = currentRoute.initialCategory,
                                onBack = onNavigateBack,
                                onOpenRuntimeManagement = onOpenRuntimeManagement,
                                onOpenCustomControls = onOpenCustomControls,
                                onOpenGamepadMapper = onOpenGamepadMapper,
                                onStatusMessage = onStatusMessage,
                                onPreferencesUpdated = onRefresh,
                                onOpenHudCustomization = onOpenHudCustomization
                            )

                            AetherRoute.GamepadMapper -> GamepadMapperScreen(
                                onBack = onNavigateBack
                            )

                            AetherRoute.HudCustomization -> HudCustomizationScreen(
                                onBack = onNavigateBack,
                                onOpenLegacyControls = onOpenCustomControls,
                                onStatusMessage = onStatusMessage
                            )

                            AetherRoute.LiveLyricsSettings -> LiveLyricsSettingsScreen(
                                onBack = onNavigateBack,
                                onStatusMessage = onStatusMessage
                            )

                            is AetherRoute.ModDetail -> {
                                LaunchedEffect(currentRoute.modId) {
                                    if (modsState.detailState?.modId != currentRoute.modId) {
                                        modsController?.openModDetail(currentRoute.modId)
                                    }
                                }
                                ModDetailScreen(
                                    modId = currentRoute.modId,
                                    state = modsState,
                                    onBack = onNavigateBack,
                                    onRetryLoad = { modsController?.openModDetail(currentRoute.modId) },
                                    onUpdateDetailFilters = { mc, loader, rel ->
                                        modsController?.updateDetailFilters(mc, loader, rel)
                                    },
                                    onPrepareInstall = { mod, file -> modsController?.prepareInstall(mod, file) },
                                    onConfirmPlanInstall = { modsController?.confirmInstallPlan() },
                                    onDismissPlanDialog = { modsController?.dismissPlanDialog() },
                                    onDismissRestrictedNotice = { modsController?.dismissRestrictedNotice() },
                                    onCancelDownload = { modsController?.cancelDownload(it) },
                                    onRetryLastInstall = { modsController?.retryLastInstall() },
                                    onDismissProgressItem = { modsController?.dismissProgressItem(it) },
                                    onClearBanners = { modsController?.clearBanners() },
                                    onOpenInstallLoader = { preferred -> modsController?.openInstallLoaderDialog(preferred) },
                                    onDismissInstallLoader = { modsController?.dismissInstallLoaderDialog() },
                                    onSelectInstallLoaderType = { modsController?.selectInstallLoaderType(it) },
                                    onSelectInstallLoaderCandidate = { modsController?.selectInstallLoaderCandidate(it) },
                                    onRefreshInstallLoaderCandidates = { modsController?.refreshInstallLoaderCandidates() },
                                    onStartModLoaderInstall = {
                                        modsController?.startModLoaderInstall()
                                        onRefresh()
                                    },
                                    onCancelModLoaderInstall = { modsController?.cancelModLoaderInstall() }
                                )
                            }
                        }
                        com.star.launcher.diagnostics.PerformanceMonitorOverlay(
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
            }
        }
    }
}
