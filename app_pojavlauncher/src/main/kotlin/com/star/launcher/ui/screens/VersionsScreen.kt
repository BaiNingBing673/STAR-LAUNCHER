package com.star.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.version.VersionReleaseType
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.components.VersionDetailPanel
import com.star.launcher.ui.components.VersionInstallProgressCard
import com.star.launcher.ui.components.VersionListRow
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.version.VersionCatalogItem
import com.star.launcher.version.VersionFilter
import com.star.launcher.version.VersionSort
import com.star.launcher.version.VersionState

fun filterAndSortVersionCatalog(
    items: List<VersionCatalogItem>,
    query: String,
    filter: VersionFilter,
    sort: VersionSort,
    hasModdedVersions: Boolean,
    hasSessionHistory: Boolean
): List<VersionCatalogItem> {
    val trimmedQuery = query.trim()
    val effectiveFilter = if (filter == VersionFilter.MODDED && !hasModdedVersions) {
        VersionFilter.ALL
    } else filter
    val effectiveSort = if (sort == VersionSort.RECENTLY_PLAYED && !hasSessionHistory) {
        VersionSort.NEWEST
    } else sort

    val filtered = items.filter { item ->
        val matchesFilter = when (effectiveFilter) {
            VersionFilter.ALL -> true
            VersionFilter.INSTALLED -> item.isInstalled
            VersionFilter.NOT_INSTALLED -> !item.isInstalled
            VersionFilter.RELEASE -> item.releaseType == VersionReleaseType.RELEASE
            VersionFilter.SNAPSHOT -> item.releaseType == VersionReleaseType.SNAPSHOT
            VersionFilter.MODDED -> item.isModded
        }
        if (!matchesFilter) return@filter false
        if (trimmedQuery.isEmpty()) return@filter true

        item.versionId.contains(trimmedQuery, ignoreCase = true) ||
            (item.loaderLabel?.contains(trimmedQuery, ignoreCase = true) == true) ||
            item.entity.versionType.contains(trimmedQuery, ignoreCase = true) ||
            (item.entity.inheritsFrom?.contains(trimmedQuery, ignoreCase = true) == true)
    }

    return when (effectiveSort) {
        VersionSort.NEWEST -> filtered.sortedWith(
            compareByDescending<VersionCatalogItem> { it.isInstalled }
                .thenByDescending { it.entity.releaseTimeEpochMs }
                .thenBy { it.versionId }
        )
        VersionSort.OLDEST -> filtered.sortedWith(
            compareBy<VersionCatalogItem> { it.entity.releaseTimeEpochMs }
                .thenBy { it.versionId }
        )
        VersionSort.ALPHABETICAL -> filtered.sortedBy { it.versionId.lowercase() }
        VersionSort.RECENTLY_PLAYED -> filtered.sortedWith(
            compareByDescending<VersionCatalogItem> { it.lastPlayedAt ?: 0L }
                .thenByDescending { it.isInstalled }
                .thenByDescending { it.entity.releaseTimeEpochMs }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VersionsScreen(
    state: AetherDashboardUiState,
    isExpandedWidth: Boolean = false,
    reducedMotion: Boolean = false,
    onSyncRemoteCatalog: (Boolean) -> Unit,
    onSelectVersion: (String) -> Unit,
    onInstallVersion: (String) -> Unit,
    onCancelVersionInstall: (String) -> Unit,
    onRetryVersionInstall: (String) -> Unit,
    onPlayVersion: (String) -> Unit,
    onVerifyVersion: (String) -> Unit,
    onDeleteVersion: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (VersionFilter) -> Unit,
    onSortChange: (VersionSort) -> Unit,
    onInstallFabric: (String) -> Unit,
    onCreateInstanceFromVersion: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenInstallProgress: (String?, String?) -> Unit,
    onSaveVersionRenderer: (String, String?) -> Unit = { _, _ -> }
) {
    val allItems = state.versionCatalogItems
    val filteredItems = remember(
        allItems,
        state.versionSearchQuery,
        state.versionFilter,
        state.versionSort,
        state.hasModdedVersions,
        state.hasSessionHistory
    ) {
        filterAndSortVersionCatalog(
            items = allItems,
            query = state.versionSearchQuery,
            filter = state.versionFilter,
            sort = state.versionSort,
            hasModdedVersions = state.hasModdedVersions,
            hasSessionHistory = state.hasSessionHistory
        )
    }

    val selectedItem = state.selectedVersionItem ?: filteredItems.firstOrNull()
    val selectedVersionId = selectedItem?.versionId
    val savedRendererProfileId = selectedVersionId?.let { state.versionRendererProfiles[it] }
    var draftRendererProfileId by remember(selectedVersionId, savedRendererProfileId) {
        mutableStateOf(savedRendererProfileId)
    }
    val hasUnsavedRendererChanges = selectedVersionId != null && draftRendererProfileId != savedRendererProfileId
    var pendingNavigationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var showDetailSheet by rememberSaveable { mutableStateOf(false) }
    var showFabricDialog by rememberSaveable { mutableStateOf(false) }
    var fabricTargetMcVersion by rememberSaveable { mutableStateOf(selectedItem?.versionId ?: "1.21.4") }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var includeHistoricalSync by rememberSaveable { mutableStateOf(true) }

    fun guardUnsavedRenderer(action: () -> Unit) {
        if (hasUnsavedRendererChanges) {
            pendingNavigationAction = action
        } else {
            action()
        }
    }

    if (pendingNavigationAction != null && selectedVersionId != null) {
        val pending = pendingNavigationAction!!
        AlertDialog(
            onDismissRequest = { pendingNavigationAction = null },
            containerColor = AetherColors.SurfaceElevatedDark,
            title = {
                Text(
                    text = "Unsaved Renderer Changes",
                    color = AetherColors.OnSurfaceDark
                )
            },
            text = {
                Text(
                    text = "You changed the renderer for Minecraft $selectedVersionId without saving. Save before leaving?",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            },
            confirmButton = {
                AetherPrimaryButton(
                    text = "Save",
                    onClick = {
                        onSaveVersionRenderer(selectedVersionId, draftRendererProfileId)
                        pendingNavigationAction = null
                        pending()
                    }
                )
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    TextButton(onClick = { pendingNavigationAction = null }) {
                        Text("Stay", color = AetherColors.OnSurfaceVariantDark)
                    }
                    TextButton(
                        onClick = {
                            draftRendererProfileId = savedRendererProfileId
                            pendingNavigationAction = null
                            pending()
                        }
                    ) {
                        Text("Discard", color = AetherColors.ErrorDark)
                    }
                }
            }
        )
    }

    val availableFilters = remember(state.hasModdedVersions) {
        buildList {
            add(VersionFilter.ALL)
            add(VersionFilter.INSTALLED)
            add(VersionFilter.NOT_INSTALLED)
            add(VersionFilter.RELEASE)
            add(VersionFilter.SNAPSHOT)
            if (state.hasModdedVersions) add(VersionFilter.MODDED)
        }
    }
    val availableSorts = remember(state.hasSessionHistory) {
        buildList {
            add(VersionSort.NEWEST)
            add(VersionSort.OLDEST)
            add(VersionSort.ALPHABETICAL)
            if (state.hasSessionHistory) add(VersionSort.RECENTLY_PLAYED)
        }
    }

    if (showFabricDialog) {
        AlertDialog(
            onDismissRequest = { showFabricDialog = false },
            containerColor = AetherColors.SurfaceElevatedDark,
            title = {
                Text(
                    text = "Install Fabric Loader Profile",
                    color = AetherColors.OnSurfaceDark
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    Text(
                        text = "Enter the target Minecraft version to fetch and register its latest Fabric loader profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                    OutlinedTextField(
                        value = fabricTargetMcVersion,
                        onValueChange = { fabricTargetMcVersion = it },
                        label = { Text("Minecraft version ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                AetherPrimaryButton(
                    text = "Install Fabric",
                    onClick = {
                        showFabricDialog = false
                        onInstallFabric(fabricTargetMcVersion)
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showFabricDialog = false }) {
                    Text("Cancel", color = AetherColors.OnSurfaceVariantDark)
                }
            }
        )
    }

    if (!isExpandedWidth && showDetailSheet && selectedItem != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { guardUnsavedRenderer { showDetailSheet = false } },
            sheetState = sheetState,
            containerColor = AetherColors.SurfaceDark,
            contentColor = AetherColors.OnSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AetherSpacing.Md, vertical = AetherSpacing.Sm)
            ) {
                VersionDetailPanel(
                    item = selectedItem,
                    rendererProfiles = state.rendererProfiles,
                    savedRendererProfileId = savedRendererProfileId,
                    draftRendererProfileId = draftRendererProfileId,
                    onSelectDraftRenderer = { draftRendererProfileId = it },
                    onSaveRenderer = { profileId -> onSaveVersionRenderer(selectedItem.versionId, profileId) },
                    onDiscardRendererChanges = { draftRendererProfileId = savedRendererProfileId },
                    onPlay = {
                        guardUnsavedRenderer {
                            showDetailSheet = false
                            onPlayVersion(selectedItem.versionId)
                        }
                    },
                    onInstall = { onInstallVersion(selectedItem.versionId) },
                    onCancelInstall = { onCancelVersionInstall(selectedItem.versionId) },
                    onRetryInstall = { onRetryVersionInstall(selectedItem.versionId) },
                    onVerifyInstall = { onVerifyVersion(selectedItem.versionId) },
                    onDeleteInstall = {
                        draftRendererProfileId = savedRendererProfileId
                        onDeleteVersion(selectedItem.versionId)
                        showDetailSheet = false
                    },
                    onCreateInstance = {
                        guardUnsavedRenderer {
                            showDetailSheet = false
                            onCreateInstanceFromVersion(selectedItem.versionId)
                        }
                    },
                    onOpenInstallProgressScreen = {
                        guardUnsavedRenderer {
                            showDetailSheet = false
                            onOpenInstallProgress(selectedItem.versionId, "Minecraft ${selectedItem.versionId}")
                        }
                    },
                    reducedMotion = reducedMotion
                )
                Spacer(modifier = Modifier.height(AetherSpacing.Lg))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AetherSpacing.Md, vertical = AetherSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
    ) {
        // Top header bar: Title + Subtitle + Catalog actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Minecraft Versions",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AetherColors.OnBackgroundDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Manage your game installations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AetherOutlinedButton(
                    text = "Fabric",
                    onClick = { showFabricDialog = true }
                )
                AetherTonalButton(
                    text = if (state.isBusy) "Syncing..." else "Refresh",
                    onClick = { onSyncRemoteCatalog(includeHistoricalSync) },
                    enabled = !state.isBusy
                )
            }
        }

        // Search + Sort row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.versionSearchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search version ID or loader...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AetherColors.SurfaceDark,
                    unfocusedContainerColor = AetherColors.SurfaceDark,
                    focusedBorderColor = AetherColors.AccentCyan,
                    unfocusedBorderColor = AetherColors.OutlineVariantDark,
                    focusedTextColor = AetherColors.OnSurfaceDark,
                    unfocusedTextColor = AetherColors.OnSurfaceDark
                ),
                modifier = Modifier.weight(1f)
            )

            Box {
                AetherOutlinedButton(
                    text = state.versionSort.label,
                    onClick = { sortMenuExpanded = true }
                )
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    availableSorts.forEach { sortOption ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sortOption.label,
                                    fontWeight = if (sortOption == state.versionSort) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onSortChange(sortOption)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(availableFilters, key = { it.name }) { filterOption ->
                val selected = state.versionFilter == filterOption
                FilterChip(
                    selected = selected,
                    onClick = { onFilterChange(filterOption) },
                    label = {
                        Text(
                            text = filterOption.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) AetherColors.AccentCyan else AetherColors.OutlineVariantDark
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = AetherColors.SurfaceDark,
                        labelColor = AetherColors.OnSurfaceVariantDark,
                        selectedContainerColor = AetherColors.PrimaryContainerDark,
                        selectedLabelColor = AetherColors.AccentCyan
                    )
                )
            }
        }

        val activeInstalls = remember(allItems) {
            allItems.filter { it.isInstalling && it.progress != null }
        }
        if (activeInstalls.isNotEmpty() && !isExpandedWidth) {
            activeInstalls.forEach { activeItem ->
                activeItem.progress?.let { prog ->
                    VersionInstallProgressCard(
                        progress = prog,
                        onCancel = { onCancelVersionInstall(activeItem.versionId) },
                        reducedMotion = reducedMotion
                    )
                }
            }
        }

        // Main Master-Detail split (expanded) or single-pane list (compact)
        if (isExpandedWidth) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                ) {
                    VersionListColumn(
                        items = filteredItems,
                        selectedVersionId = selectedItem?.versionId,
                        onSelect = { item ->
                            if (item.versionId != selectedVersionId) {
                                guardUnsavedRenderer { onSelectVersion(item.versionId) }
                            }
                        },
                        onPrimaryAction = { item ->
                            when (item.state) {
                                VersionState.INSTALLED -> guardUnsavedRenderer { onPlayVersion(item.versionId) }
                                VersionState.NOT_INSTALLED -> guardUnsavedRenderer {
                                    onSelectVersion(item.versionId)
                                    onInstallVersion(item.versionId)
                                }
                                VersionState.FAILED -> guardUnsavedRenderer {
                                    onSelectVersion(item.versionId)
                                    onRetryVersionInstall(item.versionId)
                                }
                                VersionState.INSTALLING, VersionState.UNSUPPORTED ->
                                    guardUnsavedRenderer { onSelectVersion(item.versionId) }
                            }
                        },
                        onSyncCatalog = { onSyncRemoteCatalog(includeHistoricalSync) }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedItem != null) {
                        VersionDetailPanel(
                            item = selectedItem,
                            rendererProfiles = state.rendererProfiles,
                            savedRendererProfileId = savedRendererProfileId,
                            draftRendererProfileId = draftRendererProfileId,
                            onSelectDraftRenderer = { draftRendererProfileId = it },
                            onSaveRenderer = { profileId -> onSaveVersionRenderer(selectedItem.versionId, profileId) },
                            onDiscardRendererChanges = { draftRendererProfileId = savedRendererProfileId },
                            onPlay = { guardUnsavedRenderer { onPlayVersion(selectedItem.versionId) } },
                            onInstall = { onInstallVersion(selectedItem.versionId) },
                            onCancelInstall = { onCancelVersionInstall(selectedItem.versionId) },
                            onRetryInstall = { onRetryVersionInstall(selectedItem.versionId) },
                            onVerifyInstall = { onVerifyVersion(selectedItem.versionId) },
                            onDeleteInstall = {
                                draftRendererProfileId = savedRendererProfileId
                                onDeleteVersion(selectedItem.versionId)
                            },
                            onCreateInstance = { guardUnsavedRenderer { onCreateInstanceFromVersion(selectedItem.versionId) } },
                            onOpenInstallProgressScreen = {
                                guardUnsavedRenderer {
                                    onOpenInstallProgress(selectedItem.versionId, "Minecraft ${selectedItem.versionId}")
                                }
                            },
                            reducedMotion = reducedMotion
                        )
                    } else {
                        AetherEmptyState(
                            icon = AetherIcons.Versions,
                            title = "Select a version",
                            description = "Pick a Minecraft release or snapshot on the left to inspect requirements, install, or launch.",
                            actionLabel = "Sync Catalog",
                            onAction = { onSyncRemoteCatalog(includeHistoricalSync) }
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                VersionListColumn(
                    items = filteredItems,
                    selectedVersionId = selectedItem?.versionId,
                    onSelect = { item ->
                        onSelectVersion(item.versionId)
                        showDetailSheet = true
                    },
                    onPrimaryAction = { item ->
                        when (item.state) {
                            VersionState.INSTALLED -> onPlayVersion(item.versionId)
                            VersionState.NOT_INSTALLED -> {
                                onSelectVersion(item.versionId)
                                onInstallVersion(item.versionId)
                                showDetailSheet = true
                            }
                            VersionState.FAILED -> {
                                onSelectVersion(item.versionId)
                                onRetryVersionInstall(item.versionId)
                                showDetailSheet = true
                            }
                            VersionState.INSTALLING, VersionState.UNSUPPORTED -> {
                                onSelectVersion(item.versionId)
                                showDetailSheet = true
                            }
                        }
                    },
                    onSyncCatalog = { onSyncRemoteCatalog(includeHistoricalSync) }
                )
            }
        }
    }
}

@Composable
private fun VersionListColumn(
    items: List<VersionCatalogItem>,
    selectedVersionId: String?,
    onSelect: (VersionCatalogItem) -> Unit,
    onPrimaryAction: (VersionCatalogItem) -> Unit,
    onSyncCatalog: () -> Unit
) {
    if (items.isEmpty()) {
        AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            AetherEmptyState(
                icon = AetherIcons.Versions,
                title = "No matching Minecraft versions",
                description = "Sync Mojang's version catalog or adjust your search and filter chips.",
                actionLabel = "Refresh Catalog",
                onAction = onSyncCatalog
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
    ) {
        items(
            items = items,
            key = { it.versionId },
            contentType = { "version_row" }
        ) { item ->
            VersionListRow(
                item = item,
                isSelected = item.versionId == selectedVersionId,
                onSelect = { onSelect(item) },
                onPrimaryAction = { onPrimaryAction(item) }
            )
        }
    }
}
