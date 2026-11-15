package com.star.launcher.core

import android.content.Context
import com.star.launcher.account.AccountRepository
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

    companion object {
        @Volatile
        private var instance: AetherContainer? = null

        fun get(context: Context): AetherContainer =
            instance ?: synchronized(this) {
                instance ?: AetherContainer(context).also { instance = it }
            }

        @JvmStatic
        fun flushWalIfInitialized() {
            val current = instance ?: return
            CrossProcessSpoolBridge.flushDatabaseWal(current.database)
        }
    }
}
