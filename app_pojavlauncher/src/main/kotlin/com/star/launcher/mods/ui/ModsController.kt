package com.star.launcher.mods.ui

import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.mods.api.CurseForgeApiException
import com.star.launcher.mods.api.CurseForgeCategory
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeCredentialStatus
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgePagination
import com.star.launcher.mods.api.CurseForgeReleaseType
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.api.CurseForgeSearchQuery
import com.star.launcher.mods.api.CurseForgeSortField
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.compat.InstanceEnvironmentResolver
import com.star.launcher.mods.compat.ModCompatibilityAssessment
import com.star.launcher.mods.compat.ModCompatibilityEngine
import com.star.launcher.mods.deps.ModDependencyResolver
import com.star.launcher.mods.deps.ModInstallPlan
import com.star.launcher.mods.download.ModDownloadInstaller
import com.star.launcher.mods.download.ModDownloadProgressItem
import com.star.launcher.mods.download.ModInstallOutcome
import com.star.launcher.mods.inventory.InstalledModInventoryItem
import com.star.launcher.mods.inventory.InstalledModInventoryReport
import com.star.launcher.mods.inventory.InstalledModManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ModsSubTab(val label: String) {
    DISCOVER("Discover"),
    MY_MODS("My Mods")
}

enum class ModsErrorKind {
    UNCONFIGURED,
    OFFLINE,
    RATE_LIMITED,
    UNAUTHORIZED,
    HTTP_ERROR
}

data class ModsUiError(
    val kind: ModsErrorKind,
    val title: String,
    val message: String,
    val retryAfterSeconds: Int? = null
)

data class ModDetailUiState(
    val modId: Long,
    val mod: CurseForgeMod? = null,
    val descriptionText: String? = null,
    val overviewAssessment: ModCompatibilityAssessment? = null,
    val allFiles: List<CurseForgeFile> = emptyList(),
    val filteredFiles: List<CurseForgeFile> = emptyList(),
    val fileAssessments: Map<Long, ModCompatibilityAssessment> = emptyMap(),
    val filterMcVersion: String? = null,
    val filterLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
    val filterReleaseType: CurseForgeReleaseType? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class PendingPlanDialogState(
    val isBuilding: Boolean = false,
    val rootMod: CurseForgeMod,
    val rootFile: CurseForgeFile,
    val plan: ModInstallPlan? = null,
    val buildError: String? = null,
    val requiresOverwriteConfirmation: Boolean = false,
    val replacingOldFileName: String? = null
)

data class RestrictedDownloadNotice(
    val modName: String,
    val reason: String,
    val websiteUrl: String?
)

data class ModsUiState(
    val activeSubTab: ModsSubTab = ModsSubTab.DISCOVER,
    val selectedInstance: GameInstanceEntity? = null,
    val availableInstances: List<GameInstanceEntity> = emptyList(),
    val instanceEnvironment: InstanceEnvironment? = null,
    val credentialStatus: CurseForgeCredentialStatus = CurseForgeCredentialStatus.UNCONFIGURED,

    // Discover filters & query
    val searchQuery: String = "",
    val sortField: CurseForgeSortField = CurseForgeSortField.POPULARITY,
    val selectedCategoryId: Int? = null,
    val filterMcVersion: String? = null,
    val filterLoader: CurseForgeModLoader = CurseForgeModLoader.ANY,
    val filterReleaseType: CurseForgeReleaseType? = null,
    val showFiltersExpanded: Boolean = false,
    val categories: List<CurseForgeCategory> = emptyList(),
    val mcVersions: List<String> = emptyList(),
    val verifiedLoadersForVersion: Set<CurseForgeModLoader> = emptySet(),

    // Discover results
    val featuredMods: List<CurseForgeMod> = emptyList(),
    val isLoadingFeatured: Boolean = false,
    val searchResults: List<CurseForgeMod> = emptyList(),
    val modAssessments: Map<Long, ModCompatibilityAssessment> = emptyMap(),
    val pagination: CurseForgePagination? = null,
    val isLoadingSearch: Boolean = false,
    val isLoadingMore: Boolean = false,
    val discoverError: ModsUiError? = null,

    // Details & Install plan
    val detailState: ModDetailUiState? = null,
    val pendingPlanDialog: PendingPlanDialogState? = null,
    val restrictedNotice: RestrictedDownloadNotice? = null,
    val activeDownloads: Map<Long, ModDownloadProgressItem> = emptyMap(),
    val installStatusBanner: String? = null,
    val installErrorBanner: String? = null,

    // My Mods
    val inventoryReport: InstalledModInventoryReport? = null,
    val inventorySearchQuery: String = "",
    val isLoadingInventory: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val pendingDeleteMod: InstalledModInventoryItem? = null,
    val pendingUpdateMod: InstalledModInventoryItem? = null,

    // Credential config sheet
    val showCredentialConfigDialog: Boolean = false,
    val credentialConfigError: String? = null
) {
    @delegate:Transient
    val filteredInventoryMods: List<InstalledModInventoryItem> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val all = inventoryReport?.mods.orEmpty()
        val query = inventorySearchQuery.trim()
        if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.fileName.contains(query, ignoreCase = true) ||
                    (it.modIdInJar?.contains(query, ignoreCase = true) == true)
            }
        }
    }
}

class ModsController(
    private val credentialProvider: CurseForgeCredentialProvider,
    private val repository: CurseForgeRepository,
    private val environmentResolver: InstanceEnvironmentResolver,
    private val compatibilityEngine: ModCompatibilityEngine,
    private val dependencyResolver: ModDependencyResolver,
    private val downloadInstaller: ModDownloadInstaller,
    private val installedModManager: InstalledModManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val _uiState = MutableStateFlow(ModsUiState())
    val uiState: StateFlow<ModsUiState> = _uiState.asStateFlow()

    private var observingStarted = false
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var detailJob: Job? = null
    private var planJob: Job? = null
    private var installJob: Job? = null
    private var lastSyncedInstanceId: String? = null
    private var lastSyncedInstanceVersionId: String? = null
    private var lastSyncedInstanceGameDir: String? = null
    private var lastSubmittedPlan: Triple<ModInstallPlan, Boolean, String?>? = null
    private var filtersMatchedToInstance = true

    fun startObserving() {
        if (observingStarted) return
        observingStarted = true
        scope.launch {
            downloadInstaller.activeProgress.collect { progressMap ->
                _uiState.update { it.copy(activeDownloads = progressMap) }
            }
        }
        refreshCredentialStatusAndMetadata(initialLoad = true)
    }

    fun selectSubTab(tab: ModsSubTab) {
        _uiState.update { it.copy(activeSubTab = tab) }
        if (tab == ModsSubTab.MY_MODS) {
            refreshInstalledInventory()
        }
    }

    fun syncSelectedInstance(
        selectedInstance: GameInstanceEntity?,
        allInstances: List<GameInstanceEntity>
    ) {
        val instanceChanged = selectedInstance?.instanceId != lastSyncedInstanceId
        val envInputsChanged = instanceChanged ||
            selectedInstance?.versionId != lastSyncedInstanceVersionId ||
            selectedInstance?.gameDirPath != lastSyncedInstanceGameDir ||
            _uiState.value.instanceEnvironment == null
        if (!envInputsChanged && _uiState.value.availableInstances === allInstances) {
            return
        }
        lastSyncedInstanceId = selectedInstance?.instanceId
        lastSyncedInstanceVersionId = selectedInstance?.versionId
        lastSyncedInstanceGameDir = selectedInstance?.gameDirPath
        if (!envInputsChanged) {
            _uiState.update { it.copy(selectedInstance = selectedInstance, availableInstances = allInstances) }
            return
        }
        scope.launch {
            val env = runCatching {
                withContext(ioDispatcher) {
                    selectedInstance?.let { environmentResolver.resolve(it) }
                }
            }.getOrNull()
            _uiState.update { state ->
                val newMc = if (instanceChanged && filtersMatchedToInstance) env?.baseMinecraftVersion else state.filterMcVersion
                val newLoader = if (instanceChanged && filtersMatchedToInstance) {
                    env?.curseForgeLoader ?: CurseForgeModLoader.ANY
                } else {
                    state.filterLoader
                }
                state.copy(
                    selectedInstance = selectedInstance,
                    availableInstances = allInstances,
                    instanceEnvironment = env,
                    filterMcVersion = newMc,
                    filterLoader = newLoader
                )
            }
            if (instanceChanged) {
                reEvaluateLoadedCompatibility()
                refreshInstalledInventory()
                if (_uiState.value.credentialStatus != CurseForgeCredentialStatus.UNCONFIGURED || repository.enablePublicFallback) {
                    loadSearchAndFeatured(resetPage = true)
                }
            }
        }
    }

    fun refreshCredentialStatusAndMetadata(initialLoad: Boolean = false) {
        scope.launch {
            val status = runCatching {
                withContext(ioDispatcher) { credentialProvider.getCredentialStatus() }
            }.getOrDefault(CurseForgeCredentialStatus.UNCONFIGURED)
            _uiState.update { it.copy(credentialStatus = status) }
            if (status == CurseForgeCredentialStatus.UNCONFIGURED && !repository.enablePublicFallback) {
                _uiState.update {
                    it.copy(
                        isLoadingSearch = false,
                        isLoadingFeatured = false,
                        discoverError = ModsUiError(
                            kind = ModsErrorKind.UNCONFIGURED,
                            title = "CurseForge API Credentials Required",
                            message = "Configure a STAR backend proxy or store a developer CurseForge API key in Keystore Vault to browse and download mods."
                        )
                    )
                }
                return@launch
            }
            if (initialLoad || _uiState.value.searchResults.isEmpty()) {
                loadSearchAndFeatured(resetPage = true)
            }
            launch {
                runCatching { loadMetadataCatalogs() }
            }
        }
    }

    private suspend fun loadMetadataCatalogs() {
        val catsDeferred = scope.async { repository.getCategories() }
        val versDeferred = scope.async { repository.getMinecraftVersions() }
        val cats = catsDeferred.await().getOrNull().orEmpty()
        val vers = versDeferred.await().getOrNull().orEmpty().map { it.versionString }
        val activeMc = _uiState.value.filterMcVersion ?: _uiState.value.instanceEnvironment?.baseMinecraftVersion
        val loaders = repository.getModLoaders(mcVersion = activeMc).getOrNull().orEmpty()
            .mapNotNull { it.type }
            .toSet()

        _uiState.update {
            it.copy(
                categories = cats,
                mcVersions = vers,
                verifiedLoadersForVersion = loaders
            )
        }
    }

    fun updateSearchQuery(query: String) {
        val prev = _uiState.value.searchQuery
        _uiState.update { it.copy(searchQuery = query) }
        if (query.trim() == prev.trim()) return
        searchDebounceJob?.cancel()
        searchDebounceJob = scope.launch {
            kotlinx.coroutines.delay(300L)
            loadSearchAndFeatured(resetPage = true)
        }
    }

    fun submitSearch() {
        searchDebounceJob?.cancel()
        loadSearchAndFeatured(resetPage = true)
    }

    fun selectSortField(sortField: CurseForgeSortField) {
        _uiState.update { it.copy(sortField = sortField) }
        loadSearchAndFeatured(resetPage = true)
    }

    fun selectCategory(categoryId: Int?) {
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
        loadSearchAndFeatured(resetPage = true)
    }

    fun selectMcVersionFilter(mcVersion: String?) {
        filtersMatchedToInstance = false
        _uiState.update { it.copy(filterMcVersion = mcVersion) }
        scope.launch {
            val loaders = repository.getModLoaders(mcVersion = mcVersion).getOrNull().orEmpty()
                .mapNotNull { it.type }
                .toSet()
            _uiState.update { it.copy(verifiedLoadersForVersion = loaders) }
        }
        loadSearchAndFeatured(resetPage = true)
    }

    fun selectLoaderFilter(loader: CurseForgeModLoader) {
        filtersMatchedToInstance = false
        _uiState.update { it.copy(filterLoader = loader) }
        loadSearchAndFeatured(resetPage = true)
    }

    fun selectReleaseTypeFilter(releaseType: CurseForgeReleaseType?) {
        _uiState.update { it.copy(filterReleaseType = releaseType) }
        loadSearchAndFeatured(resetPage = true)
    }

    fun matchFiltersToSelectedInstance() {
        val env = _uiState.value.instanceEnvironment ?: return
        filtersMatchedToInstance = true
        _uiState.update {
            it.copy(
                filterMcVersion = env.baseMinecraftVersion,
                filterLoader = env.curseForgeLoader
            )
        }
        loadSearchAndFeatured(resetPage = true)
    }

    fun toggleFiltersExpanded() {
        _uiState.update { it.copy(showFiltersExpanded = !it.showFiltersExpanded) }
    }

    fun loadSearchAndFeatured(resetPage: Boolean = true) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val snapshot = _uiState.value
            val status = credentialProvider.getCredentialStatus()
            _uiState.update { it.copy(credentialStatus = status) }
            if (status == CurseForgeCredentialStatus.UNCONFIGURED && !repository.enablePublicFallback) {
                _uiState.update {
                    it.copy(
                        isLoadingSearch = false,
                        isLoadingFeatured = false,
                        discoverError = ModsUiError(
                            kind = ModsErrorKind.UNCONFIGURED,
                            title = "CurseForge API Credentials Required",
                            message = "Configure a STAR backend proxy or developer Vault key to browse CurseForge mods."
                        )
                    )
                }
                return@launch
            }

            if (resetPage) {
                _uiState.update {
                    it.copy(
                        isLoadingSearch = true,
                        isLoadingFeatured = snapshot.featuredMods.isEmpty(),
                        discoverError = null
                    )
                }
            }

            val featuredDeferred = if (resetPage && snapshot.searchQuery.isBlank() && snapshot.selectedCategoryId == null) {
                async {
                    repository.getFeaturedMods(
                        gameVersion = snapshot.filterMcVersion,
                        modLoader = snapshot.filterLoader,
                        pageSize = 8
                    )
                }
            } else {
                null
            }

            val query = CurseForgeSearchQuery(
                searchFilter = snapshot.searchQuery,
                gameVersion = snapshot.filterMcVersion,
                modLoader = snapshot.filterLoader,
                categoryId = snapshot.selectedCategoryId,
                sortField = snapshot.sortField,
                sortOrderDesc = snapshot.sortField != CurseForgeSortField.NAME,
                index = 0,
                pageSize = PAGE_SIZE
            )
            val searchResult = repository.searchMods(query)
            val featuredResult = featuredDeferred?.await()

            val env = _uiState.value.instanceEnvironment
            val installedIds = _uiState.value.inventoryReport?.mods
                ?.mapNotNull { it.trackedRecord?.curseForgeModId }
                ?.toSet()
                .orEmpty()

            searchResult.onSuccess { response ->
                val filteredByRelease = applyClientReleaseTypePreference(response.mods, snapshot.filterReleaseType)
                val assessments = filteredByRelease.associate { mod ->
                    mod.id to compatibilityEngine.evaluateModOverview(env, mod, installedIds)
                }
                val featured = featuredResult?.getOrNull()?.mods ?: _uiState.value.featuredMods
                _uiState.update {
                    it.copy(
                        featuredMods = featured,
                        isLoadingFeatured = false,
                        searchResults = filteredByRelease,
                        modAssessments = it.modAssessments + assessments,
                        pagination = response.pagination,
                        isLoadingSearch = false,
                        isLoadingMore = false,
                        discoverError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingSearch = false,
                        isLoadingFeatured = false,
                        isLoadingMore = false,
                        discoverError = mapApiError(err)
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val current = _uiState.value
        val pagination = current.pagination ?: return
        if (current.isLoadingSearch || current.isLoadingMore || !pagination.hasMore) return

        scope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val nextIndex = pagination.index + pagination.resultCount
            val query = CurseForgeSearchQuery(
                searchFilter = current.searchQuery,
                gameVersion = current.filterMcVersion,
                modLoader = current.filterLoader,
                categoryId = current.selectedCategoryId,
                sortField = current.sortField,
                sortOrderDesc = current.sortField != CurseForgeSortField.NAME,
                index = nextIndex,
                pageSize = PAGE_SIZE
            )
            repository.searchMods(query).onSuccess { response ->
                val env = _uiState.value.instanceEnvironment
                val installedIds = _uiState.value.inventoryReport?.mods
                    ?.mapNotNull { it.trackedRecord?.curseForgeModId }
                    ?.toSet()
                    .orEmpty()
                val newMods = applyClientReleaseTypePreference(response.mods, current.filterReleaseType)
                val merged = (current.searchResults + newMods).distinctBy { it.id }
                val assessments = newMods.associate { mod ->
                    mod.id to compatibilityEngine.evaluateModOverview(env, mod, installedIds)
                }
                _uiState.update {
                    it.copy(
                        searchResults = merged,
                        modAssessments = it.modAssessments + assessments,
                        pagination = response.pagination,
                        isLoadingMore = false,
                        discoverError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        discoverError = mapApiError(err)
                    )
                }
            }
        }
    }

    fun openModDetail(modId: Long) {
        detailJob?.cancel()
        val cachedMod = _uiState.value.searchResults.firstOrNull { it.id == modId }
            ?: _uiState.value.featuredMods.firstOrNull { it.id == modId }
        val env = _uiState.value.instanceEnvironment
        _uiState.update {
            it.copy(
                detailState = ModDetailUiState(
                    modId = modId,
                    mod = cachedMod,
                    filterMcVersion = it.filterMcVersion ?: env?.baseMinecraftVersion,
                    filterLoader = it.filterLoader,
                    filterReleaseType = it.filterReleaseType,
                    isLoading = true
                )
            )
        }

        detailJob = scope.launch {
            val modDeferred = async { repository.getMod(modId) }
            val descDeferred = async { repository.getModDescription(modId) }
            val filesDeferred = async {
                repository.getModFiles(
                    modId = modId,
                    gameVersion = null,
                    modLoader = CurseForgeModLoader.ANY,
                    index = 0,
                    pageSize = 50
                )
            }

            val modRes = modDeferred.await()
            val descRes = descDeferred.await()
            val filesRes = filesDeferred.await()

            val loadedMod = modRes.getOrNull() ?: cachedMod
            if (loadedMod == null && modRes.isFailure) {
                val errMsg = modRes.exceptionOrNull()?.message ?: "Failed to load mod details."
                _uiState.update { state ->
                    state.copy(
                        detailState = state.detailState?.copy(
                            isLoading = false,
                            errorMessage = errMsg
                        )
                    )
                }
                return@launch
            }

            val files = filesRes.getOrNull()?.files ?: loadedMod?.latestFiles.orEmpty()
            val currentEnv = _uiState.value.instanceEnvironment
            val installedIds = _uiState.value.inventoryReport?.mods
                ?.mapNotNull { it.trackedRecord?.curseForgeModId }
                ?.toSet()
                .orEmpty()

            val overviewAssessment = loadedMod?.let {
                compatibilityEngine.evaluateModOverview(currentEnv, it, installedIds)
            }
            val fileAssessments = files.associate { file ->
                file.id to compatibilityEngine.evaluateFile(currentEnv, file, loadedMod, installedIds)
            }

            _uiState.update { state ->
                val detail = state.detailState ?: return@update state
                val filtered = filterDetailFiles(
                    files = files,
                    mcVersion = detail.filterMcVersion,
                    loader = detail.filterLoader,
                    releaseType = detail.filterReleaseType
                )
                state.copy(
                    detailState = detail.copy(
                        mod = loadedMod,
                        descriptionText = descRes.getOrNull(),
                        overviewAssessment = overviewAssessment,
                        allFiles = files,
                        filteredFiles = filtered,
                        fileAssessments = fileAssessments,
                        isLoading = false,
                        errorMessage = filesRes.exceptionOrNull()?.message
                    )
                )
            }
        }
    }

    fun updateDetailFilters(
        mcVersion: String? = _uiState.value.detailState?.filterMcVersion,
        loader: CurseForgeModLoader = _uiState.value.detailState?.filterLoader ?: CurseForgeModLoader.ANY,
        releaseType: CurseForgeReleaseType? = _uiState.value.detailState?.filterReleaseType
    ) {
        _uiState.update { state ->
            val detail = state.detailState ?: return@update state
            val filtered = filterDetailFiles(detail.allFiles, mcVersion, loader, releaseType)
            state.copy(
                detailState = detail.copy(
                    filterMcVersion = mcVersion,
                    filterLoader = loader,
                    filterReleaseType = releaseType,
                    filteredFiles = filtered
                )
            )
        }
    }

    fun prepareInstall(
        mod: CurseForgeMod,
        file: CurseForgeFile,
        replacingOldFileName: String? = null
    ) {
        val env = _uiState.value.instanceEnvironment
        if (env == null) {
            _uiState.update {
                it.copy(installErrorBanner = "Select or create a Minecraft instance before installing mods.")
            }
            return
        }

        planJob?.cancel()
        _uiState.update {
            it.copy(
                installErrorBanner = null,
                installStatusBanner = null,
                pendingPlanDialog = PendingPlanDialogState(
                    isBuilding = true,
                    rootMod = mod,
                    rootFile = file,
                    replacingOldFileName = replacingOldFileName
                )
            )
        }

        planJob = scope.launch {
            val inventoryMods = _uiState.value.inventoryReport?.mods.orEmpty()
            val installedModIds = inventoryMods.mapNotNull { it.trackedRecord?.curseForgeModId }.toSet()
            val installedFileNames = inventoryMods.map { it.fileName }.toSet()

            val plan = dependencyResolver.buildInstallPlan(
                rootMod = mod,
                rootFile = file,
                environment = env,
                installedCurseForgeModIds = installedModIds,
                installedFileNames = installedFileNames
            )
            val alreadyExists = installedFileNames.contains(file.fileName) ||
                installedFileNames.contains("${file.fileName}.disabled")

            _uiState.update { state ->
                val pending = state.pendingPlanDialog ?: return@update state
                state.copy(
                    pendingPlanDialog = pending.copy(
                        isBuilding = false,
                        plan = plan,
                        requiresOverwriteConfirmation = alreadyExists
                    )
                )
            }
        }
    }

    fun dismissPlanDialog() {
        planJob?.cancel()
        _uiState.update { it.copy(pendingPlanDialog = null) }
    }

    fun confirmInstallPlan(allowOverwrite: Boolean = false) {
        val pending = _uiState.value.pendingPlanDialog ?: return
        val plan = pending.plan ?: return
        val env = _uiState.value.instanceEnvironment ?: return
        val oldFileToRemoveAfterUpdate = pending.replacingOldFileName
        val shouldOverwrite = allowOverwrite || pending.requiresOverwriteConfirmation
        lastSubmittedPlan = Triple(plan, shouldOverwrite, oldFileToRemoveAfterUpdate)

        _uiState.update {
            it.copy(
                pendingPlanDialog = null,
                installStatusBanner = "Downloading ${plan.filesToDownload.size} mod file(s) for ${env.instanceName}...",
                installErrorBanner = null
            )
        }

        executeInstallPlan(plan, env, allowOverwrite = shouldOverwrite, oldFileToRemoveAfterUpdate)
    }

    private fun executeInstallPlan(
        plan: ModInstallPlan,
        env: InstanceEnvironment,
        allowOverwrite: Boolean,
        oldFileToRemoveAfterUpdate: String?
    ) {
        installJob?.cancel()
        installJob = scope.launch {
            when (val outcome = downloadInstaller.installPlan(plan, env, allowOverwrite = allowOverwrite)) {
                is ModInstallOutcome.Success -> {
                    if (!oldFileToRemoveAfterUpdate.isNullOrBlank()) {
                        val newlyInstalledNames = outcome.installed.map { it.fileName }.toSet()
                        if (!newlyInstalledNames.contains(oldFileToRemoveAfterUpdate)) {
                            installedModManager.removeMod(env, oldFileToRemoveAfterUpdate)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            installStatusBanner = "Installed ${outcome.installed.size} file(s) to ${env.instanceName}/mods.",
                            installErrorBanner = null
                        )
                    }
                    refreshInstalledInventory()
                    reEvaluateLoadedCompatibility()
                }
                is ModInstallOutcome.Restricted -> {
                    _uiState.update {
                        it.copy(
                            installStatusBanner = null,
                            restrictedNotice = RestrictedDownloadNotice(
                                modName = outcome.modName,
                                reason = outcome.reason,
                                websiteUrl = outcome.websiteUrl
                            )
                        )
                    }
                }
                is ModInstallOutcome.Cancelled -> {
                    _uiState.update {
                        it.copy(
                            installStatusBanner = "Cancelled download of ${outcome.fileName}.",
                            installErrorBanner = null
                        )
                    }
                }
                is ModInstallOutcome.Failed -> {
                    _uiState.update {
                        it.copy(
                            installStatusBanner = null,
                            installErrorBanner = outcome.message
                        )
                    }
                }
            }
        }
    }

    fun retryLastInstall() {
        val (plan, overwrite, oldFile) = lastSubmittedPlan ?: return
        val env = _uiState.value.instanceEnvironment ?: return
        executeInstallPlan(plan, env, allowOverwrite = overwrite, oldFileToRemoveAfterUpdate = oldFile)
    }

    fun cancelDownload(fileId: Long) {
        downloadInstaller.requestCancel(fileId)
        installJob?.cancel()
    }

    fun dismissProgressItem(fileId: Long) {
        downloadInstaller.clearFinishedProgress(fileId)
    }

    fun dismissRestrictedNotice() {
        _uiState.update { it.copy(restrictedNotice = null) }
    }

    fun clearBanners() {
        _uiState.update { it.copy(installStatusBanner = null, installErrorBanner = null) }
    }

    fun refreshInstalledInventory() {
        val env = _uiState.value.instanceEnvironment
        if (env == null) {
            _uiState.update { it.copy(inventoryReport = null, isLoadingInventory = false) }
            return
        }
        scope.launch {
            _uiState.update { it.copy(isLoadingInventory = true) }
            val report = withContext(ioDispatcher) { installedModManager.inspectInventory(env) }
            _uiState.update {
                it.copy(
                    inventoryReport = report,
                    isLoadingInventory = false
                )
            }
        }
    }

    fun updateInventorySearchQuery(query: String) {
        _uiState.update { it.copy(inventorySearchQuery = query) }
    }

    fun toggleModEnabled(item: InstalledModInventoryItem) {
        val env = _uiState.value.instanceEnvironment ?: return
        scope.launch {
            withContext(ioDispatcher) { installedModManager.toggleModEnabled(env, item.fileName) }
                .onSuccess { refreshInstalledInventory() }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(installErrorBanner = err.message ?: "Unable to toggle mod state.")
                    }
                }
        }
    }

    fun requestDeleteMod(item: InstalledModInventoryItem) {
        _uiState.update { it.copy(pendingDeleteMod = item) }
    }

    fun dismissDeleteMod() {
        _uiState.update { it.copy(pendingDeleteMod = null) }
    }

    fun confirmDeleteMod() {
        val item = _uiState.value.pendingDeleteMod ?: return
        val env = _uiState.value.instanceEnvironment ?: return
        _uiState.update { it.copy(pendingDeleteMod = null) }
        scope.launch {
            withContext(ioDispatcher) { installedModManager.removeMod(env, item.fileName) }
                .onSuccess {
                    _uiState.update { it.copy(installStatusBanner = "Removed ${item.fileName}.") }
                    refreshInstalledInventory()
                    reEvaluateLoadedCompatibility()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(installErrorBanner = err.message ?: "Failed to remove ${item.fileName}.")
                    }
                }
        }
    }

    fun checkInstalledUpdates() {
        val env = _uiState.value.instanceEnvironment ?: return
        val report = _uiState.value.inventoryReport ?: return
        if (report.mods.isEmpty()) return
        scope.launch {
            _uiState.update { it.copy(isCheckingUpdates = true) }
            val updatedItems = withContext(ioDispatcher) { installedModManager.checkUpdates(env, report.mods) }
            _uiState.update { state ->
                state.copy(
                    isCheckingUpdates = false,
                    inventoryReport = state.inventoryReport?.copy(mods = updatedItems)
                )
            }
        }
    }

    fun requestUpdateMod(item: InstalledModInventoryItem) {
        _uiState.update { it.copy(pendingUpdateMod = item) }
    }

    fun dismissUpdateMod() {
        _uiState.update { it.copy(pendingUpdateMod = null) }
    }

    fun confirmUpdateMod() {
        val item = _uiState.value.pendingUpdateMod ?: return
        val updateInfo = item.availableUpdate ?: return
        val trackedModId = item.trackedRecord?.curseForgeModId ?: return
        _uiState.update { it.copy(pendingUpdateMod = null) }

        scope.launch {
            val mod = repository.getMod(trackedModId).getOrElse { err ->
                _uiState.update {
                    it.copy(installErrorBanner = err.message ?: "Unable to fetch CurseForge mod info for update.")
                }
                return@launch
            }
            prepareInstall(
                mod = mod,
                file = updateInfo.latestCompatibleFile,
                replacingOldFileName = item.fileName
            )
        }
    }

    fun openCredentialConfigDialog() {
        _uiState.update {
            it.copy(showCredentialConfigDialog = true, credentialConfigError = null)
        }
    }

    fun dismissCredentialConfigDialog() {
        _uiState.update {
            it.copy(showCredentialConfigDialog = false, credentialConfigError = null)
        }
    }

    fun saveDeveloperKey(rawKey: String) {
        scope.launch {
            runCatching { credentialProvider.saveDeveloperApiKey(rawKey) }
                .onSuccess {
                    _uiState.update {
                        it.copy(showCredentialConfigDialog = false, credentialConfigError = null)
                    }
                    refreshCredentialStatusAndMetadata(initialLoad = true)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(credentialConfigError = err.message ?: "Invalid API key.")
                    }
                }
        }
    }

    fun saveProxyConfig(baseUrl: String, clientToken: String?) {
        scope.launch {
            runCatching { credentialProvider.saveProxyConfig(baseUrl, clientToken) }
                .onSuccess {
                    _uiState.update {
                        it.copy(showCredentialConfigDialog = false, credentialConfigError = null)
                    }
                    refreshCredentialStatusAndMetadata(initialLoad = true)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(credentialConfigError = err.message ?: "Invalid proxy URL.")
                    }
                }
        }
    }

    fun clearCredentials() {
        scope.launch {
            credentialProvider.clearCredentials()
            _uiState.update {
                it.copy(
                    showCredentialConfigDialog = false,
                    credentialConfigError = null,
                    searchResults = emptyList(),
                    featuredMods = emptyList()
                )
            }
            refreshCredentialStatusAndMetadata()
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun reEvaluateLoadedCompatibility() {
        val env = _uiState.value.instanceEnvironment
        val installedIds = _uiState.value.inventoryReport?.mods
            ?.mapNotNull { it.trackedRecord?.curseForgeModId }
            ?.toSet()
            .orEmpty()

        _uiState.update { state ->
            val allLoadedMods = (state.featuredMods + state.searchResults).distinctBy { it.id }
            val updatedAssessments = allLoadedMods.associate { mod ->
                mod.id to compatibilityEngine.evaluateModOverview(env, mod, installedIds)
            }
            val detail = state.detailState?.let { d ->
                val overview = d.mod?.let { compatibilityEngine.evaluateModOverview(env, it, installedIds) }
                val fileAssessments = d.allFiles.associate { f ->
                    f.id to compatibilityEngine.evaluateFile(env, f, d.mod, installedIds)
                }
                d.copy(overviewAssessment = overview, fileAssessments = fileAssessments)
            }
            state.copy(
                modAssessments = updatedAssessments,
                detailState = detail
            )
        }
    }

    private fun applyClientReleaseTypePreference(
        mods: List<CurseForgeMod>,
        releaseType: CurseForgeReleaseType?
    ): List<CurseForgeMod> {
        if (releaseType == null) return mods
        return mods.filter { mod ->
            mod.latestFiles.isEmpty() || mod.latestFiles.any { it.releaseType == releaseType }
        }
    }

    private fun filterDetailFiles(
        files: List<CurseForgeFile>,
        mcVersion: String?,
        loader: CurseForgeModLoader,
        releaseType: CurseForgeReleaseType?
    ): List<CurseForgeFile> = files.filter { file ->
        val matchesMc = mcVersion.isNullOrBlank() ||
            file.declaredMinecraftVersions.any { it.equals(mcVersion, ignoreCase = true) }
        val matchesLoader = loader == CurseForgeModLoader.ANY ||
            file.declaredLoaders.contains(loader)
        val matchesRelease = releaseType == null || file.releaseType == releaseType
        matchesMc && matchesLoader && matchesRelease
    }

    private fun mapApiError(err: Throwable): ModsUiError = when (err) {
        is CurseForgeApiException.NotConfigured -> ModsUiError(
            kind = ModsErrorKind.UNCONFIGURED,
            title = "CurseForge API Credentials Required",
            message = err.message
        )
        is CurseForgeApiException.NetworkOffline -> ModsUiError(
            kind = ModsErrorKind.OFFLINE,
            title = "Offline",
            message = err.message
        )
        is CurseForgeApiException.RateLimited -> ModsUiError(
            kind = ModsErrorKind.RATE_LIMITED,
            title = "Rate Limited",
            message = err.message,
            retryAfterSeconds = err.retryAfterSeconds
        )
        is CurseForgeApiException.Unauthorized -> ModsUiError(
            kind = ModsErrorKind.UNAUTHORIZED,
            title = "Authentication Failed",
            message = err.message
        )
        is CurseForgeApiException.Forbidden -> if (!err.isDistributionRestriction) {
            ModsUiError(
                kind = ModsErrorKind.UNAUTHORIZED,
                title = "CurseForge Access Denied (HTTP 403)",
                message = err.message
            )
        } else {
            ModsUiError(
                kind = ModsErrorKind.HTTP_ERROR,
                title = "Download Restricted",
                message = err.message
            )
        }
        else -> ModsUiError(
            kind = ModsErrorKind.HTTP_ERROR,
            title = "CurseForge Request Failed",
            message = err.message ?: "Unable to reach CurseForge."
        )
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
