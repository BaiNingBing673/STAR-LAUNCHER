package com.star.launcher.core

import android.content.Context
import com.star.launcher.account.AccountRepository
import com.star.launcher.bridge.BridgeController
import com.star.launcher.bridge.auth.BridgeAuthenticationManager
import com.star.launcher.bridge.auth.FloodgateManager
import com.star.launcher.bridge.compat.BridgeCompatibilityEngine
import com.star.launcher.bridge.config.GeyserConfigManager
import com.star.launcher.bridge.config.LocalServerConfigManager
import com.star.launcher.bridge.diagnostics.BridgeDiagnostics
import com.star.launcher.bridge.discovery.ServerDiscoveryManager
import com.star.launcher.bridge.discovery.ServerProfileManager
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.network.BridgeNetworkManager
import com.star.launcher.bridge.network.BridgePortManager
import com.star.launcher.bridge.process.AndroidBridgeProcessManager
import com.star.launcher.bridge.process.BridgeConnectionMonitor
import com.star.launcher.bridge.process.BridgeHealthMonitor
import com.star.launcher.bridge.runtime.AndroidBridgeRuntimeEnvironment
import com.star.launcher.bridge.runtime.BridgeRuntimeManager
import com.star.launcher.bridge.runtime.BridgeUpdateManager
import com.star.launcher.bridge.runtime.BridgeVersionManager
import com.star.launcher.bridge.runtime.GeyserRuntimeManager
import com.star.launcher.bridge.runtime.RuntimeValidator
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.compat.AndroidDeviceCapabilityProbe
import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.security.AndroidKeystoreTokenVault
import com.star.launcher.data.security.TokenVault
import com.star.launcher.diagnostics.CrossProcessSpoolBridge
import com.star.launcher.diagnostics.DiagnosticExporter
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.download.DownloadManager
import com.star.launcher.engine.adapter.InputManager
import com.star.launcher.engine.adapter.NativeEngineAdapter
import com.star.launcher.engine.adapter.PojavInputManager
import com.star.launcher.engine.adapter.PojavNativeEngineAdapter
import com.star.launcher.engine.adapter.PojavRendererFacade
import com.star.launcher.engine.adapter.PojavRuntimeFacade
import com.star.launcher.engine.adapter.PojavSessionBridge
import com.star.launcher.engine.adapter.RendererFacade
import com.star.launcher.engine.adapter.RuntimeFacade
import com.star.launcher.engine.adapter.SessionBridge
import com.star.launcher.instance.InstanceContentManager
import com.star.launcher.instance.InstanceManager
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.compat.InstanceEnvironmentResolver
import com.star.launcher.mods.compat.ModCompatibilityEngine
import com.star.launcher.mods.deps.ModDependencyResolver
import com.star.launcher.mods.download.ModDownloadInstaller
import com.star.launcher.mods.inventory.InstalledModManager
import com.star.launcher.mods.loader.ModLoaderInstaller
import com.star.launcher.mods.modpack.ModpackImportManager
import com.star.launcher.mods.shaders.ShaderPackManager
import com.star.launcher.version.PojavMinecraftInstallBackend
import com.star.launcher.version.VersionCatalogService
import com.star.launcher.version.VersionInstallCoordinator
import com.star.launcher.version.VersionManager

/**
 * Explicit application-scoped dependency container for STAR Launcher Phase 1 services.
 * Avoids reflection/codegen overhead while preserving `:launcher` startup latency.
 */
class AetherContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AetherDatabase by lazy { AetherDatabase.getInstance(appContext) }
    val tokenVault: TokenVault by lazy { AndroidKeystoreTokenVault(appContext) }
    val storageManager: AetherStorageManager by lazy { AetherStorageManager() }
    val settingsRepository: AetherSettingsRepository by lazy {
        AetherSettingsRepository(database.settingsDao())
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(appContext, tokenVault, settingsRepository)
    }

    val capabilityProbe: DeviceCapabilityProbe by lazy {
        AndroidDeviceCapabilityProbe(appContext)
    }
    val compatibilityEvaluator: CompatibilityEvaluator by lazy {
        CompatibilityEvaluator()
    }
    val diagnosticsManager: DiagnosticsManager by lazy {
        DiagnosticsManager(database.diagnosticSessionDao(), database.compatibilityResultDao())
    }

    val runtimeFacade: RuntimeFacade by lazy { PojavRuntimeFacade() }
    val rendererFacade: RendererFacade by lazy { PojavRendererFacade(appContext) }
    val inputManager: InputManager by lazy { PojavInputManager() }
    val sessionBridge: SessionBridge by lazy { PojavSessionBridge() }
    val nativeEngineAdapter: NativeEngineAdapter by lazy { PojavNativeEngineAdapter() }

    val versionManager: VersionManager by lazy {
        VersionManager(database.gameVersionDao(), storageManager)
    }
    val downloadManager: DownloadManager by lazy {
        DownloadManager(database.downloadTaskDao(), storageManager)
    }
    val versionCatalogService: VersionCatalogService by lazy {
        VersionCatalogService(database.gameVersionDao(), storageManager, downloadManager, versionManager)
    }
    val versionInstallCoordinator: VersionInstallCoordinator by lazy {
        VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settingsRepository,
            backend = PojavMinecraftInstallBackend(appContext, storageManager)
        )
    }
    val instanceContentManager: InstanceContentManager by lazy {
        InstanceContentManager()
    }
    val diagnosticExporter: DiagnosticExporter by lazy {
        DiagnosticExporter(storageManager, database.deviceInfoDao(), database.diagnosticSessionDao())
    }
    val spoolBridge: CrossProcessSpoolBridge by lazy {
        CrossProcessSpoolBridge(storageManager, diagnosticsManager)
    }
    val instanceManager: InstanceManager by lazy {
        InstanceManager(
            gameInstanceDao = database.gameInstanceDao(),
            gameVersionDao = database.gameVersionDao(),
            runtimeRegistryDao = database.runtimeRegistryDao(),
            rendererProfileDao = database.rendererProfileDao(),
            deviceInfoDao = database.deviceInfoDao(),
            storageManager = storageManager,
            capabilityProbe = capabilityProbe,
            compatibilityEvaluator = compatibilityEvaluator,
            nativeEngineAdapter = nativeEngineAdapter,
            diagnosticsManager = diagnosticsManager
        )
    }

    val curseForgeCredentialProvider: CurseForgeCredentialProvider by lazy {
        CurseForgeCredentialProvider(
            tokenVault = tokenVault,
            settingsRepository = settingsRepository,
            bundledApiKeyProvider = {
                runCatching { appContext.getString(net.kdt.pojavlaunch.R.string.curseforge_api_key) }.getOrNull()
            },
            bundledProxyUrlProvider = {
                runCatching { appContext.getString(net.kdt.pojavlaunch.R.string.curseforge_default_proxy_url) }.getOrNull()
            }
        )
    }
    val curseForgeRepository: CurseForgeRepository by lazy {
        CurseForgeRepository(
            credentialProvider = curseForgeCredentialProvider,
            enablePublicFallback = true
        )
    }
    val instanceEnvironmentResolver: InstanceEnvironmentResolver by lazy {
        InstanceEnvironmentResolver(
            gameVersionDao = database.gameVersionDao(),
            storageManager = storageManager
        )
    }
    val modCompatibilityEngine: ModCompatibilityEngine by lazy {
        ModCompatibilityEngine()
    }
    val modDependencyResolver: ModDependencyResolver by lazy {
        ModDependencyResolver(
            repository = curseForgeRepository,
            compatibilityEngine = modCompatibilityEngine
        )
    }
    val modDownloadInstaller: ModDownloadInstaller by lazy {
        ModDownloadInstaller(
            repository = curseForgeRepository,
            downloadTaskDao = database.downloadTaskDao(),
            installedModDao = database.installedModDao(),
            storageManager = storageManager
        )
    }
    val installedModManager: InstalledModManager by lazy {
        InstalledModManager(
            installedModDao = database.installedModDao(),
            repository = curseForgeRepository,
            compatibilityEngine = modCompatibilityEngine
        )
    }
    val modLoaderInstaller: ModLoaderInstaller by lazy {
        ModLoaderInstaller(
            storageManager = storageManager,
            gameVersionDao = database.gameVersionDao(),
            gameInstanceDao = database.gameInstanceDao(),
            versionManager = versionManager,
            environmentResolver = instanceEnvironmentResolver
        )
    }
    val shaderPackManager: ShaderPackManager by lazy {
        ShaderPackManager()
    }
    val modpackImportManager: ModpackImportManager by lazy {
        ModpackImportManager()
    }

    // STAR Bedrock Bridge modular graph
    val bridgeStoragePaths: BridgeStoragePaths by lazy {
        BridgeStoragePaths()
    }
    val bridgeLogManager: BridgeLogManager by lazy {
        BridgeLogManager(bridgeStoragePaths)
    }
    val bridgeRuntimeValidator: RuntimeValidator by lazy {
        RuntimeValidator(bridgeStoragePaths, AndroidBridgeRuntimeEnvironment(appContext))
    }
    val bridgeVersionManager: BridgeVersionManager by lazy {
        BridgeVersionManager()
    }
    val bridgeUpdateManager: BridgeUpdateManager by lazy {
        BridgeUpdateManager(bridgeStoragePaths, bridgeLogManager)
    }
    val geyserRuntimeManager: GeyserRuntimeManager by lazy {
        GeyserRuntimeManager(bridgeStoragePaths, bridgeVersionManager, bridgeUpdateManager)
    }
    val bridgeRuntimeManager: BridgeRuntimeManager by lazy {
        BridgeRuntimeManager(bridgeStoragePaths, bridgeRuntimeValidator, bridgeVersionManager, bridgeUpdateManager)
    }
    val geyserConfigManager: GeyserConfigManager by lazy {
        GeyserConfigManager(bridgeStoragePaths)
    }
    val localServerConfigManager: LocalServerConfigManager by lazy {
        LocalServerConfigManager(bridgeStoragePaths)
    }
    val bridgePortManager: BridgePortManager by lazy {
        BridgePortManager()
    }
    val bridgeNetworkManager: BridgeNetworkManager by lazy {
        BridgeNetworkManager(bridgePortManager)
    }
    val floodgateManager: FloodgateManager by lazy {
        FloodgateManager(bridgeStoragePaths, bridgeVersionManager, bridgeUpdateManager, tokenVault, bridgeLogManager)
    }
    val bridgeAuthenticationManager: BridgeAuthenticationManager by lazy {
        BridgeAuthenticationManager(floodgateManager, accountRepository)
    }
    val bridgeProcessManager: AndroidBridgeProcessManager by lazy {
        AndroidBridgeProcessManager(appContext, bridgeStoragePaths, bridgeLogManager)
    }
    val bridgeHealthMonitor: BridgeHealthMonitor by lazy {
        BridgeHealthMonitor(bridgeProcessManager)
    }
    val bridgeConnectionMonitor: BridgeConnectionMonitor by lazy {
        BridgeConnectionMonitor(bridgeNetworkManager)
    }
    val serverProfileManager: ServerProfileManager by lazy {
        ServerProfileManager(database.serverProfileDao())
    }
    val serverDiscoveryManager: ServerDiscoveryManager by lazy {
        ServerDiscoveryManager(serverProfileManager, bridgeNetworkManager)
    }
    val bridgeCompatibilityEngine: BridgeCompatibilityEngine by lazy {
        BridgeCompatibilityEngine()
    }
    val bridgeDiagnostics: BridgeDiagnostics by lazy {
        BridgeDiagnostics(bridgeStoragePaths, bridgeLogManager)
    }
    val bridgeController: BridgeController by lazy {
        BridgeController(
            paths = bridgeStoragePaths,
            settingsRepository = settingsRepository,
            runtimeManager = bridgeRuntimeManager,
            geyserRuntimeManager = geyserRuntimeManager,
            versionManager = bridgeVersionManager,
            geyserConfigManager = geyserConfigManager,
            localServerConfigManager = localServerConfigManager,
            networkManager = bridgeNetworkManager,
            portManager = bridgePortManager,
            floodgateManager = floodgateManager,
            authManager = bridgeAuthenticationManager,
            processController = bridgeProcessManager,
            healthMonitor = bridgeHealthMonitor,
            connectionMonitor = bridgeConnectionMonitor,
            profileManager = serverProfileManager,
            discoveryManager = serverDiscoveryManager,
            compatibilityEngine = bridgeCompatibilityEngine,
            logManager = bridgeLogManager,
            diagnostics = bridgeDiagnostics
        )
    }

    companion object {
        @Volatile
        private var instance: AetherContainer? = null

        @Volatile
        private var instanceContext: Context? = null

        fun get(context: Context): AetherContainer {
            val appContext = context.applicationContext ?: context
            val existing = instance
            if (existing != null && instanceContext === appContext) {
                return existing
            }
            return synchronized(this) {
                val synced = instance
                if (synced != null && instanceContext === appContext) {
                    synced
                } else {
                    AetherContainer(appContext).also {
                        instance = it
                        instanceContext = appContext
                    }
                }
            }
        }

        @JvmStatic
        fun flushWalIfInitialized() {
            val current = instance ?: return
            CrossProcessSpoolBridge.flushDatabaseWal(current.database)
        }

        @JvmStatic
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            synchronized(this) {
                instance = null
                instanceContext = null
            }
            com.star.launcher.data.db.AetherDatabase.closeAndResetForTesting()
        }
    }
}
