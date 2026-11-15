package com.star.launcher.ui.navigation

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.star.launcher.core.AetherContainer
import com.star.launcher.mods.ui.ModsController
import com.star.launcher.ui.dashboard.AetherDashboardController
import com.star.launcher.ui.dashboard.DashboardTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PreferenceCategory(val title: String) {
    ALL("All"),
    VIDEO("Video & Renderer"),
    CONTROLS("Controls & Input"),
    JAVA("Java & Runtime"),
    MISC("General & Downloads"),
    EXPERIMENTAL("Experimental")
}

/**
 * Closed set of every user-facing destination inside STAR Launcher's unified navigation architecture.
 */
sealed interface AetherRoute {
    data class Tab(val tab: DashboardTab) : AetherRoute
    data class InstanceDetail(val instanceId: String) : AetherRoute
    data class VersionInstallProgress(
        val versionId: String? = null,
        val displayLabel: String? = null
    ) : AetherRoute
    data object RuntimeManagement : AetherRoute
    data class RuntimeDetail(val runtimeName: String) : AetherRoute
    data object MicrosoftAuth : AetherRoute
    data class EnginePreferences(
        val initialCategory: PreferenceCategory = PreferenceCategory.ALL
    ) : AetherRoute
    data object GamepadMapper : AetherRoute
    data object HudCustomization : AetherRoute
    data object LiveLyricsSettings : AetherRoute
    data class ModDetail(val modId: Long) : AetherRoute

    fun encode(): String = when (this) {
        is Tab -> "tab:${tab.name}"
        is InstanceDetail -> "instance_detail:$instanceId"
        is VersionInstallProgress -> "version_install:${versionId.orEmpty()}|${displayLabel.orEmpty()}"
        RuntimeManagement -> "runtime_management"
        is RuntimeDetail -> "runtime_detail:$runtimeName"
        MicrosoftAuth -> "microsoft_auth"
        is EnginePreferences -> "engine_prefs:${initialCategory.name}"
        GamepadMapper -> "gamepad_mapper"
        HudCustomization -> "hud_customization"
        LiveLyricsSettings -> "live_lyrics_settings"
        is ModDetail -> "mod_detail:$modId"
    }

    val ownerTab: DashboardTab
        get() = when (this) {
            is Tab -> tab
            is InstanceDetail -> DashboardTab.LIBRARY
            is VersionInstallProgress -> DashboardTab.VERSIONS
            is ModDetail -> DashboardTab.MODS
            RuntimeManagement, is RuntimeDetail, MicrosoftAuth, is EnginePreferences, GamepadMapper, HudCustomization, LiveLyricsSettings ->
                DashboardTab.SETTINGS
        }

    companion object {
        fun decode(encoded: String?): AetherRoute? {
            if (encoded.isNullOrBlank()) return null
            return when {
                encoded.startsWith("tab:") -> {
                    val tabName = encoded.removePrefix("tab:")
                    val tab = DashboardTab.values().firstOrNull { it.name == tabName } ?: DashboardTab.HOME
                    Tab(tab)
                }
                encoded.startsWith("instance_detail:") -> {
                    val id = encoded.removePrefix("instance_detail:")
                    if (id.isBlank()) null else InstanceDetail(id)
                }
                encoded.startsWith("version_install:") -> {
                    val payload = encoded.removePrefix("version_install:")
                    val parts = payload.split("|", limit = 2)
                    val verId = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                    val label = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    VersionInstallProgress(verId, label)
                }
                encoded == "runtime_management" -> RuntimeManagement
                encoded.startsWith("runtime_detail:") -> {
                    val name = encoded.removePrefix("runtime_detail:")
                    if (name.isBlank()) null else RuntimeDetail(name)
                }
                encoded == "microsoft_auth" -> MicrosoftAuth
                encoded.startsWith("engine_prefs:") -> {
                    val catName = encoded.removePrefix("engine_prefs:")
                    val cat = PreferenceCategory.values().firstOrNull { it.name == catName }
                        ?: PreferenceCategory.ALL
                    EnginePreferences(cat)
                }
                encoded == "gamepad_mapper" -> GamepadMapper
                encoded == "hud_customization" -> HudCustomization
                encoded == "live_lyrics_settings" -> LiveLyricsSettings
                encoded.startsWith("mod_detail:") -> {
                    val modId = encoded.removePrefix("mod_detail:").toLongOrNull()
                    if (modId != null && modId > 0L) ModDetail(modId) else null
                }
                // Friendly short aliases for Intent routing (e.g. notifications / deep links)
                encoded.equals("HOME", ignoreCase = true) -> Tab(DashboardTab.HOME)
                encoded.equals("LIBRARY", ignoreCase = true) -> Tab(DashboardTab.LIBRARY)
                encoded.equals("VERSIONS", ignoreCase = true) -> Tab(DashboardTab.VERSIONS)
                encoded.equals("MODS", ignoreCase = true) -> Tab(DashboardTab.MODS)
                encoded.equals("COMMUNITY", ignoreCase = true) -> Tab(DashboardTab.COMMUNITY)
                encoded.equals("SETTINGS", ignoreCase = true) -> Tab(DashboardTab.SETTINGS)
                encoded.equals("AUTH", ignoreCase = true) -> MicrosoftAuth
                encoded.equals("GAMEPAD", ignoreCase = true) -> GamepadMapper
                encoded.equals("HUD", ignoreCase = true) || encoded.equals("HUD_CUSTOMIZE", ignoreCase = true) -> HudCustomization
                encoded.equals("LIVE_LYRICS", ignoreCase = true) -> LiveLyricsSettings
                encoded.equals("RUNTIME_MANAGEMENT", ignoreCase = true) -> RuntimeManagement
                encoded.equals("ENGINE_PREFS", ignoreCase = true) -> EnginePreferences(PreferenceCategory.ALL)
                else -> null
            }
        }
    }
}

/**
 * Single authoritative back-stack coordinator persisted via [SavedStateHandle] so configuration
 * changes (rotation) and Android process recreation restore the exact active screen & stack.
 */
class AetherNavigationCoordinator(
    private val savedStateHandle: SavedStateHandle
) {
    private val _backStack = MutableStateFlow(readStackFromState())
    val backStack: StateFlow<List<AetherRoute>> = _backStack.asStateFlow()

    val currentRoute: AetherRoute
        get() = _backStack.value.lastOrNull() ?: AetherRoute.Tab(DashboardTab.HOME)

    val canNavigateBack: Boolean
        get() = _backStack.value.size > 1

    fun applyInitialIntentRouteIfFresh(routeKey: String?) {
        val alreadyInitialized = savedStateHandle.get<Boolean>(KEY_INITIALIZED) == true
        if (alreadyInitialized) return
        savedStateHandle[KEY_INITIALIZED] = true
        if (routeKey != null) {
            navigateFromExternalRouteKey(routeKey)
        }
    }

    fun navigateFromExternalRouteKey(routeKey: String?) {
        val decoded = AetherRoute.decode(routeKey) ?: return
        when (decoded) {
            is AetherRoute.Tab -> selectTab(decoded.tab)
            else -> {
                val baseStack = if (decoded.ownerTab == DashboardTab.HOME) {
                    listOf(AetherRoute.Tab(DashboardTab.HOME))
                } else {
                    listOf(AetherRoute.Tab(DashboardTab.HOME), AetherRoute.Tab(decoded.ownerTab))
                }
                commitStack(baseStack + decoded)
            }
        }
    }

    fun selectTab(tab: DashboardTab) {
        val newStack = if (tab == DashboardTab.HOME) {
            listOf(AetherRoute.Tab(DashboardTab.HOME))
        } else {
            // Keep Home as predictable root tab so Back from any top tab returns cleanly to Home
            listOf(AetherRoute.Tab(DashboardTab.HOME), AetherRoute.Tab(tab))
        }
        commitStack(newStack)
    }

    fun push(route: AetherRoute) {
        if (route is AetherRoute.Tab) {
            selectTab(route.tab)
            return
        }
        val current = _backStack.value
        if (current.lastOrNull() == route) return
        commitStack(current + route)
    }

    fun popBack(): Boolean {
        val current = _backStack.value
        if (current.size <= 1) return false
        commitStack(current.dropLast(1))
        return true
    }

    fun popToTab(tab: DashboardTab) {
        selectTab(tab)
    }

    private fun readStackFromState(): List<AetherRoute> {
        val encodedList = savedStateHandle.get<ArrayList<String>>(KEY_BACK_STACK)
        val decoded = encodedList?.mapNotNull { AetherRoute.decode(it) }.orEmpty()
        return if (decoded.isEmpty()) {
            listOf(AetherRoute.Tab(DashboardTab.HOME))
        } else {
            decoded
        }
    }

    private fun commitStack(stack: List<AetherRoute>) {
        val normalized = if (stack.isEmpty()) listOf(AetherRoute.Tab(DashboardTab.HOME)) else stack
        _backStack.value = normalized
        savedStateHandle[KEY_BACK_STACK] = ArrayList(normalized.map { it.encode() })
    }

    companion object {
        const val KEY_BACK_STACK = "aether_nav_back_stack"
        const val KEY_INITIALIZED = "aether_nav_initialized"
    }
}

/**
 * Process-recreation and rotation-safe ViewModel hosting both [AetherNavigationCoordinator]
 * and [AetherDashboardController].
 */
class AetherDashboardStateViewModel(
    application: Application,
    val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    val navigation = AetherNavigationCoordinator(savedStateHandle)

    val controller: AetherDashboardController by lazy {
        val container = AetherContainer.get(application)
        AetherDashboardController(
            instanceManager = container.instanceManager,
            versionManager = container.versionManager,
            downloadManager = container.downloadManager,
            capabilityProbe = container.capabilityProbe,
            compatibilityEvaluator = container.compatibilityEvaluator,
            nativeEngineAdapter = container.nativeEngineAdapter,
            spoolBridge = container.spoolBridge,
            accountRepository = container.accountRepository,
            runtimeFacade = container.runtimeFacade,
            rendererFacade = container.rendererFacade,
            diagnosticsManager = container.diagnosticsManager,
            versionCatalogService = container.versionCatalogService,
            instanceContentManager = container.instanceContentManager,
            diagnosticExporter = container.diagnosticExporter,
            settingsRepository = container.settingsRepository,
            versionInstallCoordinator = container.versionInstallCoordinator,
            scope = viewModelScope
        )
    }

    val modsController: ModsController by lazy {
        val container = AetherContainer.get(application)
        ModsController(
            credentialProvider = container.curseForgeCredentialProvider,
            repository = container.curseForgeRepository,
            environmentResolver = container.instanceEnvironmentResolver,
            compatibilityEngine = container.modCompatibilityEngine,
            dependencyResolver = container.modDependencyResolver,
            downloadInstaller = container.modDownloadInstaller,
            installedModManager = container.installedModManager,
            scope = viewModelScope,
            modLoaderInstaller = container.modLoaderInstaller
        )
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun onTabSelected(tab: DashboardTab) {
        navigation.selectTab(tab)
        controller.selectTab(tab)
    }

    fun syncControllerWithCurrentRoute() {
        val ownerTab = navigation.currentRoute.ownerTab
        if (controller.uiState.value.selectedTab != ownerTab) {
            controller.selectTab(ownerTab)
        }
    }

    fun saveExtraUiState(outState: Bundle) {
        savedStateHandle.get<ArrayList<String>>(AetherNavigationCoordinator.KEY_BACK_STACK)?.let {
            outState.putStringArrayList(AetherNavigationCoordinator.KEY_BACK_STACK, it)
        }
    }
}
