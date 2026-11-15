package com.star.launcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.api.CurseForgeConnectionState
import com.star.launcher.mods.api.CurseForgeCredentialStatus
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeReleaseType
import com.star.launcher.mods.api.CurseForgeSortField
import com.star.launcher.mods.compat.EnvironmentDetectionSource
import com.star.launcher.mods.compat.ModCompatibilityAssessment
import com.star.launcher.mods.compat.ModCompatibilityLevel
import com.star.launcher.mods.download.ModDownloadPhase
import com.star.launcher.mods.download.ModDownloadProgressItem
import com.star.launcher.mods.inventory.InstalledModInventoryItem
import com.star.launcher.mods.loader.ModLoaderCandidate
import com.star.launcher.mods.loader.ModLoaderInstaller
import com.star.launcher.mods.ui.ModsErrorKind
import com.star.launcher.mods.ui.ModsSubTab
import com.star.launcher.mods.ui.ModsUiState
import com.star.launcher.mods.ui.PendingPlanDialogState
import com.star.launcher.mods.ui.RestrictedDownloadNotice
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherErrorCard
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherLoadingState
import com.star.launcher.ui.components.AetherMetricChip
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherRemoteImage
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModsScreen(
    state: ModsUiState,
    onSelectSubTab: (ModsSubTab) -> Unit,
    onSelectInstance: (String) -> Unit,
    onOpenCreateInstance: () -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleFiltersExpanded: () -> Unit,
    onSelectSortField: (CurseForgeSortField) -> Unit,
    onSelectCategory: (Int?) -> Unit,
    onSelectMcVersionFilter: (String?) -> Unit,
    onSelectLoaderFilter: (CurseForgeModLoader) -> Unit,
    onSelectReleaseTypeFilter: (CurseForgeReleaseType?) -> Unit,
    onMatchFiltersToInstance: () -> Unit,
    onLoadNextPage: () -> Unit,
    onOpenModDetail: (Long) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onRetryLastInstall: () -> Unit,
    onDismissProgressItem: (Long) -> Unit,
    onConfirmPlanInstall: () -> Unit,
    onDismissPlanDialog: () -> Unit,
    onDismissRestrictedNotice: () -> Unit,
    onClearBanners: () -> Unit,
    onRefreshInventory: () -> Unit,
    onUpdateInventorySearch: (String) -> Unit,
    onToggleModEnabled: (InstalledModInventoryItem) -> Unit,
    onRequestDeleteMod: (InstalledModInventoryItem) -> Unit,
    onConfirmDeleteMod: () -> Unit,
    onDismissDeleteMod: () -> Unit,
    onCheckUpdates: () -> Unit,
    onRequestUpdateMod: (InstalledModInventoryItem) -> Unit,
    onConfirmUpdateMod: () -> Unit,
    onDismissUpdateMod: () -> Unit,
    onOpenCredentialConfig: () -> Unit,
    onDismissCredentialConfig: () -> Unit,
    onSaveDeveloperKey: (String) -> Unit,
    onSaveProxyConfig: (String, String?) -> Unit,
    onClearCredentials: () -> Unit,
    onTestApiConnection: () -> Unit = {},
    onOpenInstallLoader: (ModLoaderType?) -> Unit = {},
    onDismissInstallLoader: () -> Unit = {},
    onSelectInstallLoaderType: (ModLoaderType) -> Unit = {},
    onSelectInstallLoaderCandidate: (ModLoaderCandidate) -> Unit = {},
    onRefreshInstallLoaderCandidates: () -> Unit = {},
    onStartModLoaderInstall: () -> Unit = {},
    onCancelModLoaderInstall: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showInstancePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        // Prominent selected instance integration header
        item {
            SelectedInstanceModsBanner(
                state = state,
                onSwitchInstanceClick = { showInstancePicker = true },
                onCreateInstanceClick = onOpenCreateInstance,
                onMatchFilters = onMatchFiltersToInstance,
                onOpenApiConfig = onOpenCredentialConfig,
                onOpenInstallLoader = { onOpenInstallLoader(null) }
            )
        }

        // Sub-tab switcher: Discover | My Mods
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModsSubTab.entries.forEach { tab ->
                    val selected = state.activeSubTab == tab
                    val countSuffix = if (tab == ModsSubTab.MY_MODS && state.inventoryReport != null) {
                        " (${state.inventoryReport.mods.size})"
                    } else {
                        ""
                    }
                    Surface(
                        onClick = { onSelectSubTab(tab) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) AetherColors.PrimaryContainerDark else AetherColors.SurfaceDark,
                        border = BorderStroke(
                            1.dp,
                            if (selected) AetherColors.AccentCyan.copy(alpha = 0.6f) else AetherColors.OutlineVariantDark
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 11.dp, horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${tab.label}$countSuffix",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (selected) AetherColors.AccentCyan else AetherColors.OnSurfaceVariantDark
                            )
                        }
                    }
                }
            }
        }

        // Live download transfers & status banners
        if (state.activeDownloads.isNotEmpty()) {
            items(
                items = state.activeDownloads.values.toList(),
                key = { "dl_${it.fileId}" }
            ) { progress ->
                ModDownloadProgressCard(
                    progress = progress,
                    onCancel = { onCancelDownload(progress.fileId) },
                    onRetry = onRetryLastInstall,
                    onDismiss = { onDismissProgressItem(progress.fileId) }
                )
            }
        }

        if (state.installStatusBanner != null) {
            item {
                AetherSurfaceCard(
                    containerColor = AetherColors.TierVerifiedBg,
                    borderColor = AetherColors.TierVerifiedFg.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.installStatusBanner,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.TierVerifiedFg,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearBanners) {
                            Text("Dismiss", color = AetherColors.TierVerifiedFg)
                        }
                    }
                }
            }
        }

        if (state.installErrorBanner != null) {
            item {
                AetherErrorCard(
                    title = "Mod Operation Notice",
                    message = state.installErrorBanner,
                    recoveryLabel = "Retry",
                    onRecovery = onRetryLastInstall,
                    onDismiss = onClearBanners
                )
            }
        }

        if (state.activeSubTab == ModsSubTab.DISCOVER) {
            // Search & Filter controls
            item {
                DiscoverSearchAndFiltersCard(
                    state = state,
                    onUpdateQuery = onUpdateSearchQuery,
                    onSubmitSearch = onSubmitSearch,
                    onToggleExpanded = onToggleFiltersExpanded,
                    onSelectSortField = onSelectSortField,
                    onSelectCategory = onSelectCategory,
                    onSelectMcVersion = onSelectMcVersionFilter,
                    onSelectLoader = onSelectLoaderFilter,
                    onSelectReleaseType = onSelectReleaseTypeFilter
                )
            }

            // API Credential / Error states
            val err = state.discoverError
            if (err != null) {
                item {
                    when (err.kind) {
                        ModsErrorKind.UNCONFIGURED -> ApiConfigurationRequiredCard(
                            onConfigure = onOpenCredentialConfig,
                            onRefresh = onSubmitSearch
                        )
                        ModsErrorKind.UNAUTHORIZED -> AetherErrorCard(
                            title = err.title,
                            message = err.message,
                            recoveryLabel = "Configure API",
                            onRecovery = onOpenCredentialConfig
                        )
                        ModsErrorKind.OFFLINE,
                        ModsErrorKind.RATE_LIMITED,
                        ModsErrorKind.HTTP_ERROR -> AetherErrorCard(
                            title = err.title,
                            message = err.message,
                            recoveryLabel = "Retry Search",
                            onRecovery = onSubmitSearch
                        )
                    }
                }
            }

            // Featured mods shelf
            if (state.featuredMods.isNotEmpty() &&
                state.searchQuery.isBlank() &&
                state.selectedCategoryId == null &&
                err == null
            ) {
                item {
                    AetherSectionHeader(
                        title = "Featured on CurseForge",
                        subtitle = "Curated API highlights"
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        items(
                            items = state.featuredMods,
                            key = { "feat_${it.id}" },
                            contentType = { "featured_mod_card" }
                        ) { mod ->
                            FeaturedModCard(
                                mod = mod,
                                assessment = state.modAssessments[mod.id],
                                onClick = { onOpenModDetail(mod.id) }
                            )
                        }
                    }
                }
            }

            // Search results section header
            if (err == null) {
                item {
                    val countInfo = state.pagination?.let { p ->
                        "Showing ${state.searchResults.size} of ${formatCompactNumber(p.totalCount)}"
                    }
                    AetherSectionHeader(
                        title = if (state.searchQuery.isBlank()) "Browse Mods" else "Results for \"${state.searchQuery.trim()}\"",
                        subtitle = countInfo
                    )
                }
            }

            if (state.isLoadingSearch) {
                item {
                    AetherLoadingState(message = "Querying CurseForge Core API...")
                }
            } else if (err == null && state.searchResults.isEmpty()) {
                item {
                    AetherEmptyState(
                        icon = AetherIcons.Mods,
                        title = "No Mods Found",
                        description = "Try clearing the category, Minecraft version, or release type filter.",
                        actionLabel = "Reset Filters",
                        onAction = {
                            onUpdateSearchQuery("")
                            onSelectCategory(null)
                            onSelectReleaseTypeFilter(null)
                            onSelectMcVersionFilter(null)
                            onSelectLoaderFilter(CurseForgeModLoader.ANY)
                        }
                    )
                }
            } else if (err == null) {
                items(
                    items = state.searchResults,
                    key = { "mod_${it.id}" },
                    contentType = { "discover_mod_card" }
                ) { mod ->
                    DiscoverModCard(
                        mod = mod,
                        assessment = state.modAssessments[mod.id],
                        onClick = { onOpenModDetail(mod.id) }
                    )
                }

                if (state.pagination?.hasMore == true) {
                    item {
                        AetherSurfaceCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = AetherColors.AccentCyan,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(AetherSpacing.Sm))
                                    Text(
                                        text = "Loading next page...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AetherColors.OnSurfaceVariantDark
                                    )
                                } else {
                                    AetherTonalButton(
                                        text = "Load More Mods",
                                        onClick = onLoadNextPage,
                                        containerColor = AetherColors.PrimaryContainerDark,
                                        contentColor = AetherColors.OnPrimaryContainerDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // MY MODS SUB-TAB
            if (state.selectedInstance == null) {
                item {
                    AetherEmptyState(
                        icon = AetherIcons.Library,
                        title = "No Instance Selected",
                        description = "Mods are stored per Minecraft instance. Select or create an instance to view and manage its mods/ folder.",
                        actionLabel = "Create Instance",
                        onAction = onOpenCreateInstance
                    )
                }
            } else {
                item {
                    MyModsToolbarCard(
                        state = state,
                        onUpdateSearch = onUpdateInventorySearch,
                        onRefresh = onRefreshInventory,
                        onCheckUpdates = onCheckUpdates
                    )
                }

                val suspicious = state.inventoryReport?.suspiciousNonJarFiles.orEmpty()
                if (suspicious.isNotEmpty()) {
                    item {
                        AetherSurfaceCard(
                            containerColor = AetherColors.TierTestedBg,
                            borderColor = AetherColors.TierTestedFg.copy(alpha = 0.45f)
                        ) {
                            Text(
                                text = "Suspicious Non-JAR Files in mods/",
                                style = MaterialTheme.typography.titleSmall,
                                color = AetherColors.TierTestedFg
                            )
                            Text(
                                text = suspicious.joinToString(", "),
                                style = AetherMonoStyle,
                                color = AetherColors.OnSurfaceDark
                            )
                        }
                    }
                }

                if (state.isLoadingInventory) {
                    item {
                        AetherLoadingState(message = "Scanning instance mods/ directory...")
                    }
                } else {
                    val items = state.filteredInventoryMods
                    if (items.isEmpty()) {
                        item {
                            AetherEmptyState(
                                icon = AetherIcons.Mods,
                                title = "No Installed Mods",
                                description = "Install mods from the Discover tab or place compatible .jar mods into ${state.selectedInstance.name}/mods.",
                                actionLabel = "Browse Discover",
                                onAction = { onSelectSubTab(ModsSubTab.DISCOVER) }
                            )
                        }
                    } else {
                        items(items, key = { "inv_${it.fileName}" }) { item ->
                            InstalledModInventoryCard(
                                item = item,
                                onToggleEnabled = { onToggleModEnabled(item) },
                                onRequestDelete = { onRequestDeleteMod(item) },
                                onRequestUpdate = { onRequestUpdateMod(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Instance Picker Modal
    if (showInstancePicker) {
        InstanceSelectorDialog(
            state = state,
            onSelectInstance = {
                onSelectInstance(it)
                showInstancePicker = false
            },
            onCreateInstance = {
                showInstancePicker = false
                onOpenCreateInstance()
            },
            onDismiss = { showInstancePicker = false }
        )
    }

    // Dependency Install Plan Modal
    val pendingPlan = state.pendingPlanDialog
    if (pendingPlan != null) {
        ModInstallPlanDialog(
            dialogState = pendingPlan,
            onConfirm = onConfirmPlanInstall,
            onDismiss = onDismissPlanDialog,
            onInstallModLoader = { preferred ->
                onDismissPlanDialog()
                onOpenInstallLoader(preferred)
            }
        )
    }

    // Restricted Distribution Notice Dialog
    val restricted = state.restrictedNotice
    if (restricted != null) {
        RestrictedDownloadDialog(
            notice = restricted,
            onDismiss = onDismissRestrictedNotice
        )
    }

    // Confirm Delete Installed Mod Dialog
    val deleteMod = state.pendingDeleteMod
    if (deleteMod != null) {
        AlertDialog(
            onDismissRequest = onDismissDeleteMod,
            title = { Text("Remove Mod File?") },
            text = {
                Text(
                    "Remove '${deleteMod.fileName}' from '${state.selectedInstance?.name}'? Config files are preserved, but this JAR will be deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDeleteMod) {
                    Text("Remove", color = AetherColors.TierBlockedFg)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteMod) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm Update Mod Dialog
    val updateMod = state.pendingUpdateMod
    val updateInfo = updateMod?.availableUpdate
    if (updateMod != null && updateInfo != null) {
        AlertDialog(
            onDismissRequest = onDismissUpdateMod,
            title = { Text("Update ${updateMod.displayName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    Text("Current: ${updateMod.fileName}")
                    Text("Available: ${updateInfo.latestCompatibleFile.fileName}")
                    Text(
                        text = "STAR Launcher will check required dependencies and verify the download before replacing the older JAR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmUpdateMod) {
                    Text("Review Install Plan", color = AetherColors.AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdateMod) {
                    Text("Cancel")
                }
            }
        )
    }

    // API Credentials Configuration Dialog
    if (state.showCredentialConfigDialog) {
        CurseForgeCredentialDialog(
            status = state.credentialStatus,
            connectionState = state.connectionState,
            connectionMessage = state.connectionMessage,
            isTestingConnection = state.isTestingConnection,
            errorMessage = state.credentialConfigError,
            onSaveDeveloperKey = onSaveDeveloperKey,
            onSaveProxyConfig = onSaveProxyConfig,
            onClearCredentials = onClearCredentials,
            onTestConnection = onTestApiConnection,
            onDismiss = onDismissCredentialConfig
        )
    }

    // Install Mod Loader Dialog
    if (state.showInstallLoaderDialog) {
        InstallModLoaderDialog(
            state = state,
            onSelectLoaderType = onSelectInstallLoaderType,
            onSelectCandidate = onSelectInstallLoaderCandidate,
            onRefreshCandidates = onRefreshInstallLoaderCandidates,
            onStartInstall = onStartModLoaderInstall,
            onCancelInstall = onCancelModLoaderInstall,
            onDismiss = onDismissInstallLoader
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedInstanceModsBanner(
    state: ModsUiState,
    onSwitchInstanceClick: () -> Unit,
    onCreateInstanceClick: () -> Unit,
    onMatchFilters: () -> Unit,
    onOpenApiConfig: () -> Unit,
    onOpenInstallLoader: () -> Unit = {}
) {
    val instance = state.selectedInstance
    val env = state.instanceEnvironment

    AetherAccentCard(accentColor = AetherColors.AccentCyan) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (instance != null) "TARGET INSTANCE" else "NO INSTANCE SELECTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = instance?.name ?: "Select or Create a Minecraft Instance",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            CredentialStatusChip(
                status = state.credentialStatus,
                connectionState = state.connectionState,
                onClick = onOpenApiConfig
            )
        }

        if (instance == null || env == null) {
            Text(
                text = "Mods install strictly into the selected instance's mods/ folder. Choose an existing instance or create one before downloading mods.",
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.OnSurfaceVariantDark
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                if (state.availableInstances.isNotEmpty()) {
                    AetherPrimaryButton(
                        text = "Select Instance",
                        onClick = onSwitchInstanceClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                AetherTonalButton(
                    text = "Create Instance",
                    onClick = onCreateInstanceClick,
                    icon = AetherIcons.Plus,
                    modifier = Modifier.weight(1f),
                    containerColor = AetherColors.PrimaryContainerDark,
                    contentColor = AetherColors.OnPrimaryContainerDark
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                AetherMetricChip(
                    label = "Minecraft",
                    value = env.baseMinecraftVersion ?: "Unknown",
                    accentColor = AetherColors.AccentCyan
                )
                AetherMetricChip(
                    label = "Loader",
                    value = buildString {
                        append(env.loaderType.name)
                        if (!env.loaderVersion.isNullOrBlank()) {
                            append(" ${env.loaderVersion}")
                        }
                    },
                    accentColor = if (env.isModdedLoader) AetherColors.TierVerifiedFg else AetherColors.TierTestedFg
                )
                val sourceLabel = when (env.detectionSource) {
                    EnvironmentDetectionSource.MANIFEST_VERIFIED -> "Manifest Verified"
                    EnvironmentDetectionSource.VERSION_ID_INFERRED -> "Version ID Inferred"
                    EnvironmentDetectionSource.UNKNOWN -> "Unverified"
                }
                AetherMetricChip(
                    label = "Detection",
                    value = sourceLabel,
                    accentColor = AetherColors.AccentBlue
                )
            }

            if (!env.isModdedLoader) {
                Text(
                    text = "This instance is detected as ${env.loaderType.name}. Install a compatible mod loader (Forge, NeoForge, Fabric, or Quilt) to enable mod installations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.TierTestedFg
                )
            }

            Text(
                text = "${env.gameDirPath}/mods",
                style = AetherMonoStyle,
                color = AetherColors.OnSurfaceVariantDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                if (!env.isModdedLoader) {
                    AetherPrimaryButton(
                        text = "Install Mod Loader",
                        onClick = onOpenInstallLoader,
                        icon = AetherIcons.Download
                    )
                } else {
                    AetherOutlinedButton(
                        text = "Change Mod Loader",
                        onClick = onOpenInstallLoader
                    )
                }
                AetherOutlinedButton(
                    text = "Switch Instance",
                    onClick = onSwitchInstanceClick
                )
                if (state.filterMcVersion != env.baseMinecraftVersion || state.filterLoader != env.curseForgeLoader) {
                    AetherTonalButton(
                        text = "Sync Filters to Instance",
                        onClick = onMatchFilters,
                        containerColor = AetherColors.PrimaryContainerDark,
                        contentColor = AetherColors.OnPrimaryContainerDark
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialStatusChip(
    status: CurseForgeCredentialStatus,
    connectionState: CurseForgeConnectionState = when (status) {
        CurseForgeCredentialStatus.UNCONFIGURED -> CurseForgeConnectionState.CONFIGURATION_REQUIRED
        else -> CurseForgeConnectionState.CONNECTED
    },
    onClick: () -> Unit
) {
    val (label, fg, bg) = when (connectionState) {
        CurseForgeConnectionState.CONNECTED ->
            Triple("Connected", AetherColors.TierVerifiedFg, AetherColors.TierVerifiedBg)
        CurseForgeConnectionState.CONFIGURATION_REQUIRED ->
            Triple("Configuration Required", AetherColors.TierTestedFg, AetherColors.TierTestedBg)
        CurseForgeConnectionState.CONNECTION_FAILED ->
            Triple("Connection Failed", AetherColors.TierBlockedFg, AetherColors.TierBlockedBg)
        CurseForgeConnectionState.CHECKING ->
            Triple("Checking...", AetherColors.AccentCyan, AetherColors.PrimaryContainerDark)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverSearchAndFiltersCard(
    state: ModsUiState,
    onUpdateQuery: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleExpanded: () -> Unit,
    onSelectSortField: (CurseForgeSortField) -> Unit,
    onSelectCategory: (Int?) -> Unit,
    onSelectMcVersion: (String?) -> Unit,
    onSelectLoader: (CurseForgeModLoader) -> Unit,
    onSelectReleaseType: (CurseForgeReleaseType?) -> Unit
) {
    var showVersionMenu by remember { mutableStateOf(false) }

    AetherSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onUpdateQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search CurseForge mods (e.g. Sodium, JEI)...") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() })
            )
            AetherPrimaryButton(
                text = "Search",
                onClick = onSubmitSearch
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                Text(
                    text = "Loader: ${state.filterLoader.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.AccentCyan
                )
                Text("•", style = MaterialTheme.typography.labelSmall, color = AetherColors.OnSurfaceVariantDark)
                Text(
                    text = "MC: ${state.filterMcVersion ?: "Any"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.AccentBlue
                )
            }
            TextButton(onClick = onToggleExpanded) {
                Text(
                    text = if (state.showFiltersExpanded) "Hide Filters" else "Filters & Sort",
                    color = AetherColors.AccentCyan
                )
            }
        }

        // Quick Sort chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
            items(CurseForgeSortField.entries) { sort ->
                FilterChip(
                    selected = state.sortField == sort,
                    onClick = { onSelectSortField(sort) },
                    label = { Text(sort.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AetherColors.PrimaryContainerDark,
                        selectedLabelColor = AetherColors.AccentCyan
                    )
                )
            }
        }

        AnimatedVisibility(visible = state.showFiltersExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                HorizontalDivider(color = AetherColors.OutlineVariantDark)

                Text(
                    text = "Mod Loader (CurseForge API modLoaderType)",
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherColors.OnSurfaceDark
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    CurseForgeModLoader.selectableFilters.forEach { loader ->
                        val apiSupportedForVersion = state.verifiedLoadersForVersion.isEmpty() ||
                            loader == CurseForgeModLoader.ANY ||
                            state.verifiedLoadersForVersion.contains(loader)
                        val label = if (!apiSupportedForVersion && !state.filterMcVersion.isNullOrBlank()) {
                            "${loader.displayName} (n/a)"
                        } else {
                            loader.displayName
                        }
                        FilterChip(
                            selected = state.filterLoader == loader,
                            onClick = { onSelectLoader(loader) },
                            label = { Text(label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Minecraft Version",
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    Box {
                        AetherOutlinedButton(
                            text = state.filterMcVersion ?: "Any Minecraft Version",
                            onClick = { showVersionMenu = true }
                        )
                        DropdownMenu(
                            expanded = showVersionMenu,
                            onDismissRequest = { showVersionMenu = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Any Minecraft Version") },
                                onClick = {
                                    showVersionMenu = false
                                    onSelectMcVersion(null)
                                }
                            )
                            val instanceMc = state.instanceEnvironment?.baseMinecraftVersion
                            val allVersionOptions = buildList {
                                if (!instanceMc.isNullOrBlank()) add(instanceMc)
                                addAll(state.mcVersions.take(40))
                            }.distinct()
                            allVersionOptions.forEach { ver ->
                                DropdownMenuItem(
                                    text = { Text(ver) },
                                    onClick = {
                                        showVersionMenu = false
                                        onSelectMcVersion(ver)
                                    }
                                )
                            }
                        }
                    }
                }

                if (state.categories.isNotEmpty()) {
                    Text(
                        text = "Categories",
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                        item {
                            FilterChip(
                                selected = state.selectedCategoryId == null,
                                onClick = { onSelectCategory(null) },
                                label = { Text("All Categories") }
                            )
                        }
                        items(state.categories.take(30), key = { "cat_${it.id}" }) { cat ->
                            FilterChip(
                                selected = state.selectedCategoryId == cat.id,
                                onClick = { onSelectCategory(cat.id) },
                                label = { Text(cat.name) }
                            )
                        }
                    }
                }

                Text(
                    text = "Release Channel (applied to available latest files; /v1/mods/search ignores releaseType)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    FilterChip(
                        selected = state.filterReleaseType == null,
                        onClick = { onSelectReleaseType(null) },
                        label = { Text("All") }
                    )
                    CurseForgeReleaseType.entries.forEach { rel ->
                        FilterChip(
                            selected = state.filterReleaseType == rel,
                            onClick = { onSelectReleaseType(rel) },
                            label = { Text(rel.displayName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiConfigurationRequiredCard(
    onConfigure: () -> Unit,
    onRefresh: () -> Unit
) {
    AetherAccentCard(accentColor = AetherColors.TierTestedFg) {
        Text(
            text = "CurseForge API Credentials Not Configured",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = AetherColors.TierTestedFg
        )
        Text(
            text = "STAR Launcher never embeds a production CurseForge API key in source code or APK resources. Connect a narrow STAR backend proxy (recommended for release builds) or save a personal CurseForge developer key inside Android Keystore Vault.",
            style = MaterialTheme.typography.bodySmall,
            color = AetherColors.OnSurfaceDark
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
            AetherPrimaryButton(
                text = "Configure API Access",
                onClick = onConfigure,
                icon = AetherIcons.Settings,
                modifier = Modifier.weight(1f)
            )
            AetherOutlinedButton(
                text = "Recheck",
                onClick = onRefresh
            )
        }
    }
}

@Composable
private fun FeaturedModCard(
    mod: CurseForgeMod,
    assessment: ModCompatibilityAssessment?,
    onClick: () -> Unit
) {
    AetherSurfaceCard(
        onClick = onClick,
        modifier = Modifier.width(240.dp),
        containerColor = AetherColors.SurfaceElevatedDark
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AetherRemoteImage(
                url = mod.logo?.thumbnailUrl ?: mod.logo?.url,
                contentDescription = mod.name,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mod.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "by ${mod.primaryAuthor}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = mod.summary.ifBlank { "No summary returned by CurseForge API." },
            style = MaterialTheme.typography.bodySmall,
            color = AetherColors.OnSurfaceVariantDark,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (assessment != null) {
                ModCompatibilityPill(assessment.level)
            }
            Text(
                text = "${formatCompactNumber(mod.downloadCount)} dl",
                style = MaterialTheme.typography.labelSmall,
                color = AetherColors.AccentBlue
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverModCard(
    mod: CurseForgeMod,
    assessment: ModCompatibilityAssessment?,
    onClick: () -> Unit
) {
    AetherSurfaceCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md),
            verticalAlignment = Alignment.Top
        ) {
            AetherRemoteImage(
                url = mod.logo?.thumbnailUrl ?: mod.logo?.url,
                contentDescription = mod.name,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = mod.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AetherColors.OnSurfaceDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(AetherSpacing.Xs))
                    Text(
                        text = "${formatCompactNumber(mod.downloadCount)} dl",
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherColors.AccentCyan
                    )
                }

                Text(
                    text = "by ${mod.primaryAuthor}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                Text(
                    text = mod.summary.ifBlank { "No summary provided." },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (assessment != null) {
                        ModCompatibilityPill(assessment.level)
                    }
                    mod.supportedLoadersFromLatest.take(3).forEach { loader ->
                        InfoBadgePill(
                            text = loader.displayName,
                            fg = AetherColors.AccentBlue,
                            bg = AetherColors.PrimaryContainerDark
                        )
                    }
                    mod.supportedMinecraftVersionsFromLatest.take(3).forEach { mc ->
                        InfoBadgePill(
                            text = mc,
                            fg = AetherColors.OnSurfaceVariantDark,
                            bg = AetherColors.SurfaceVariantDark
                        )
                    }
                    if (mod.allowModDistribution == false) {
                        InfoBadgePill(
                            text = "Manual Download Only",
                            fg = AetherColors.TierTestedFg,
                            bg = AetherColors.TierTestedBg
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MyModsToolbarCard(
    state: ModsUiState,
    onUpdateSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    val report = state.inventoryReport
    AetherSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Instance Mod Inventory",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.OnSurfaceDark
                )
                val total = report?.mods?.size ?: 0
                val enabled = report?.mods?.count { it.isEnabled } ?: 0
                val warnings = report?.mods?.count { it.isDuplicateOrSuspicious } ?: 0
                Text(
                    text = "$enabled/$total enabled" + if (warnings > 0) " • $warnings warning(s)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warnings > 0) AetherColors.TierTestedFg else AetherColors.OnSurfaceVariantDark
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                AetherOutlinedButton(
                    text = "Refresh",
                    onClick = onRefresh,
                    icon = AetherIcons.Refresh
                )
                AetherTonalButton(
                    text = if (state.isCheckingUpdates) "Checking..." else "Check Updates",
                    onClick = onCheckUpdates,
                    enabled = !state.isCheckingUpdates &&
                        (state.credentialStatus != CurseForgeCredentialStatus.UNCONFIGURED ||
                            state.connectionState == CurseForgeConnectionState.CONNECTED),
                    containerColor = AetherColors.PrimaryContainerDark,
                    contentColor = AetherColors.OnPrimaryContainerDark
                )
            }
        }

        OutlinedTextField(
            value = state.inventorySearchQuery,
            onValueChange = onUpdateSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Filter installed mods by name, ID, or filename...") }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledModInventoryCard(
    item: InstalledModInventoryItem,
    onToggleEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestUpdate: () -> Unit
) {
    AetherSurfaceCard(
        borderColor = when {
            item.compatibilityLevel == ModCompatibilityLevel.INCOMPATIBLE ->
                AetherColors.TierBlockedFg.copy(alpha = 0.55f)
            item.isDuplicateOrSuspicious ->
                AetherColors.TierTestedFg.copy(alpha = 0.55f)
            else -> AetherColors.OutlineVariantDark
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (item.isEnabled) AetherColors.OnSurfaceDark else AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.fileName,
                    style = AetherMonoStyle,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = item.isEnabled,
                onCheckedChange = { onToggleEnabled() }
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModCompatibilityPill(item.compatibilityLevel)
            if (!item.versionLabel.isNullOrBlank()) {
                InfoBadgePill(
                    text = "v${item.versionLabel}",
                    fg = AetherColors.AccentCyan,
                    bg = AetherColors.PrimaryContainerDark
                )
            }
            if (item.detectedLoader != null) {
                InfoBadgePill(
                    text = item.detectedLoader.name,
                    fg = AetherColors.AccentBlue,
                    bg = AetherColors.SurfaceVariantDark
                )
            }
            InfoBadgePill(
                text = if (item.isEnabled) "Enabled" else "Disabled (.jar.disabled)",
                fg = if (item.isEnabled) AetherColors.TierVerifiedFg else AetherColors.OnSurfaceVariantDark,
                bg = if (item.isEnabled) AetherColors.TierVerifiedBg else AetherColors.SurfaceVariantDark
            )
            InfoBadgePill(
                text = formatBytes(item.sizeBytes),
                fg = AetherColors.OnSurfaceVariantDark,
                bg = AetherColors.SurfaceVariantDark
            )
        }

        Text(
            text = item.compatibilitySummary,
            style = MaterialTheme.typography.bodySmall,
            color = AetherColors.OnSurfaceVariantDark
        )

        item.warnings.forEach { warn ->
            Text(
                text = "Warning: $warn",
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.TierTestedFg
            )
        }

        val update = item.availableUpdate
        if (update != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AetherColors.PrimaryContainerDark,
                border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update Available",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = AetherColors.AccentCyan
                        )
                        Text(
                            text = update.latestCompatibleFile.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnPrimaryContainerDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AetherPrimaryButton(
                        text = "Update",
                        onClick = onRequestUpdate
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            AetherOutlinedButton(
                text = "Remove",
                onClick = onRequestDelete
            )
        }
    }
}

@Composable
fun ModDownloadProgressCard(
    progress: ModDownloadProgressItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AetherSurfaceCard(
        containerColor = AetherColors.SurfaceElevatedDark,
        borderColor = when (progress.phase) {
            ModDownloadPhase.FAILED, ModDownloadPhase.RESTRICTED -> AetherColors.TierBlockedFg.copy(alpha = 0.5f)
            ModDownloadPhase.COMPLETED -> AetherColors.TierVerifiedFg.copy(alpha = 0.5f)
            else -> AetherColors.AccentCyan.copy(alpha = 0.45f)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = progress.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${progress.phase.name.lowercase().replace('_', ' ')} • ${progress.fileName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress.phase in setOf(
                    ModDownloadPhase.QUEUED,
                    ModDownloadPhase.RESOLVING_URL,
                    ModDownloadPhase.DOWNLOADING,
                    ModDownloadPhase.VERIFYING
                )
            ) {
                AetherOutlinedButton(text = "Cancel", onClick = onCancel)
            } else if (progress.phase in setOf(ModDownloadPhase.FAILED, ModDownloadPhase.CANCELLED)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    AetherTonalButton(text = "Retry", onClick = onRetry)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }

        if (progress.phase == ModDownloadPhase.DOWNLOADING || progress.phase == ModDownloadPhase.VERIFYING) {
            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)} (${(progress.progressFraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.AccentCyan
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = "${formatBytes(progress.downloadedBytes)} downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.AccentCyan
                )
            }
        }

        if (!progress.errorMessage.isNullOrBlank()) {
            Text(
                text = progress.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.TierBlockedFg
            )
        }
    }
}

@Composable
fun ModCompatibilityPill(level: ModCompatibilityLevel) {
    val (fg, bg) = when (level) {
        ModCompatibilityLevel.COMPATIBLE -> AetherColors.TierVerifiedFg to AetherColors.TierVerifiedBg
        ModCompatibilityLevel.POTENTIALLY_COMPATIBLE -> AetherColors.TierTestedFg to AetherColors.TierTestedBg
        ModCompatibilityLevel.INCOMPATIBLE -> AetherColors.TierBlockedFg to AetherColors.TierBlockedBg
        ModCompatibilityLevel.UNKNOWN -> AetherColors.TierDetectedFg to AetherColors.TierDetectedBg
    }
    InfoBadgePill(text = level.label, fg = fg, bg = bg)
}

@Composable
fun InfoBadgePill(
    text: String,
    fg: Color,
    bg: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ModInstallPlanDialog(
    dialogState: PendingPlanDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onInstallModLoader: ((ModLoaderType?) -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (dialogState.requiresOverwriteConfirmation) {
                    "Confirm Replace & Install Plan"
                } else {
                    "Mod Installation Plan"
                }
            )
        },
        text = {
            val plan = dialogState.plan
            if (dialogState.isBuilding || plan == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AetherColors.AccentCyan)
                    Text("Resolving CurseForge dependencies and verifying target compatibility...")
                }
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    Text(
                        text = "Target: ${plan.targetInstanceName}/mods",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AetherColors.AccentCyan
                    )

                    Text(
                        text = "Files to download (${plan.filesToDownload.size}, ~${formatBytes(plan.totalDownloadBytes)}):",
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    plan.filesToDownload.forEach { item ->
                        Text(
                            text = "• ${item.mod?.name ?: item.file.displayName} (${item.file.fileName}) — ${item.compatibility.level.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.compatibility.canInstall) AetherColors.OnSurfaceDark else AetherColors.TierBlockedFg
                        )
                        item.compatibility.reasons.forEach { reason ->
                            Text(
                                text = "  ↳ $reason",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.compatibility.canInstall) AetherColors.OnSurfaceVariantDark else AetherColors.TierTestedFg
                            )
                        }
                    }

                    if (plan.alreadyInstalledDependencies.isNotEmpty()) {
                        Text(
                            text = "Already installed dependencies skipped: ${plan.alreadyInstalledDependencies.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.TierVerifiedFg
                        )
                    }

                    if (plan.optionalDependencies.isNotEmpty()) {
                        Text(
                            text = "Optional dependencies (NOT auto-installed): ${plan.optionalDependencies.joinToString { it.modName ?: "Mod #${it.modId}" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }

                    if (plan.detectedCycles.isNotEmpty()) {
                        Text(
                            text = "Cycle safeguard triggered: ${plan.detectedCycles.joinToString("; ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.TierTestedFg
                        )
                    }

                    if (plan.conflicts.isNotEmpty()) {
                        Text(
                            text = "Conflicts: ${plan.conflicts.joinToString(" ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.TierBlockedFg
                        )
                    }

                    if (plan.unresolvedDependencies.isNotEmpty()) {
                        plan.unresolvedDependencies.forEach { unresolved ->
                            Text(
                                text = "Unresolved dependency: ${unresolved.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherColors.TierBlockedFg
                            )
                        }
                    }

                    if (dialogState.requiresOverwriteConfirmation) {
                        Text(
                            text = "A file with this name already exists in ${plan.targetInstanceName}/mods. Confirm to overwrite.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.TierTestedFg
                        )
                    }
                }
            }
        },
        confirmButton = {
            val plan = dialogState.plan
            val rootAssessment = plan?.rootItem?.compatibility
            if (rootAssessment?.requiresModLoaderInstall == true && onInstallModLoader != null) {
                TextButton(
                    onClick = { onInstallModLoader(rootAssessment.suggestedModLoader) }
                ) {
                    Text(
                        text = "Install Mod Loader",
                        color = AetherColors.AccentCyan
                    )
                }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = !dialogState.isBuilding && plan != null && plan.canProceed
                ) {
                    Text(
                        text = if (dialogState.requiresOverwriteConfirmation) "Confirm Replace & Install" else "Download & Install",
                        color = AetherColors.AccentCyan
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RestrictedDownloadDialog(
    notice: RestrictedDownloadNotice,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Author Restricted API Download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                Text(
                    text = notice.modName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = notice.reason,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "STAR Launcher respects author distribution settings and never bypasses CurseForge API download restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
        },
        confirmButton = {
            val url = notice.websiteUrl
            if (!url.isNullOrBlank()) {
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(url) }
                        onDismiss()
                    }
                ) {
                    Text("Open Project Page", color = AetherColors.AccentCyan)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InstanceSelectorDialog(
    state: ModsUiState,
    onSelectInstance: (String) -> Unit,
    onCreateInstance: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Target Instance") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                if (state.availableInstances.isEmpty()) {
                    Text("No Minecraft instances exist yet. Create one first.")
                } else {
                    state.availableInstances.forEach { instance ->
                        val isSelected = instance.instanceId == state.selectedInstance?.instanceId
                        AetherSurfaceCard(
                            onClick = { onSelectInstance(instance.instanceId) },
                            borderColor = if (isSelected) AetherColors.AccentCyan else AetherColors.OutlineVariantDark
                        ) {
                            Text(
                                text = instance.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) AetherColors.AccentCyan else AetherColors.OnSurfaceDark
                            )
                            Text(
                                text = instance.versionId ?: "No version assigned",
                                style = MaterialTheme.typography.labelSmall,
                                color = AetherColors.OnSurfaceVariantDark
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateInstance) {
                Text("Create New Instance", color = AetherColors.AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun CurseForgeCredentialDialog(
    status: CurseForgeCredentialStatus,
    connectionState: CurseForgeConnectionState = CurseForgeConnectionState.CONFIGURATION_REQUIRED,
    connectionMessage: String? = null,
    isTestingConnection: Boolean = false,
    errorMessage: String?,
    onSaveDeveloperKey: (String) -> Unit,
    onSaveProxyConfig: (String, String?) -> Unit,
    onClearCredentials: () -> Unit,
    onTestConnection: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var useProxyMode by remember(status) {
        mutableStateOf(status == CurseForgeCredentialStatus.BACKEND_PROXY_CONFIGURED)
    }
    var proxyUrl by remember { mutableStateOf("https://") }
    var proxyToken by remember { mutableStateOf("") }
    var devApiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CurseForge API Configuration") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status: ${connectionState.displayLabel}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = when (connectionState) {
                            CurseForgeConnectionState.CONNECTED -> AetherColors.TierVerifiedFg
                            CurseForgeConnectionState.CONFIGURATION_REQUIRED -> AetherColors.TierTestedFg
                            CurseForgeConnectionState.CONNECTION_FAILED -> AetherColors.TierBlockedFg
                            CurseForgeConnectionState.CHECKING -> AetherColors.AccentCyan
                        }
                    )
                    TextButton(
                        onClick = onTestConnection,
                        enabled = !isTestingConnection
                    ) {
                        Text(
                            text = if (isTestingConnection) "Testing..." else "Test Connection",
                            color = AetherColors.AccentCyan
                        )
                    }
                }

                if (!connectionMessage.isNullOrBlank()) {
                    Text(
                        text = connectionMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                }

                Text(
                    text = "Secrets are stored exclusively inside Android Keystore Vault (AES-256-GCM) and never logged or bundled into APK assets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    FilterChip(
                        selected = useProxyMode,
                        onClick = { useProxyMode = true },
                        label = { Text("Backend Proxy") }
                    )
                    FilterChip(
                        selected = !useProxyMode,
                        onClick = { useProxyMode = false },
                        label = { Text("Developer Vault Key") }
                    )
                }

                if (useProxyMode) {
                    OutlinedTextField(
                        value = proxyUrl,
                        onValueChange = { proxyUrl = it },
                        singleLine = true,
                        label = { Text("Proxy Base URL (HTTPS)") },
                        placeholder = { Text("https://api.example.com/cf") }
                    )
                    OutlinedTextField(
                        value = proxyToken,
                        onValueChange = { proxyToken = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Client Auth Token (optional)") }
                    )
                } else {
                    OutlinedTextField(
                        value = devApiKey,
                        onValueChange = { devApiKey = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("CurseForge Developer API Key") },
                        placeholder = { Text("\$2a\$10\$...") }
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.TierBlockedFg
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (useProxyMode) {
                        onSaveProxyConfig(proxyUrl, proxyToken.takeIf { it.isNotBlank() })
                    } else {
                        onSaveDeveloperKey(devApiKey)
                    }
                }
            ) {
                Text("Save Securely", color = AetherColors.AccentCyan)
            }
        },
        dismissButton = {
            Row {
                if (status != CurseForgeCredentialStatus.UNCONFIGURED) {
                    TextButton(onClick = onClearCredentials) {
                        Text("Clear", color = AetherColors.TierBlockedFg)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstallModLoaderDialog(
    state: ModsUiState,
    onSelectLoaderType: (ModLoaderType) -> Unit,
    onSelectCandidate: (ModLoaderCandidate) -> Unit,
    onRefreshCandidates: () -> Unit,
    onStartInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val env = state.instanceEnvironment
    val mcVersion = env?.baseMinecraftVersion ?: "Unknown"
    val progress = state.modLoaderInstallProgress

    AlertDialog(
        onDismissRequest = { if (!state.isInstallingModLoader) onDismiss() },
        title = {
            Text("Install Mod Loader")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                Text(
                    text = "Instance: ${env?.instanceName ?: "None"} • Minecraft $mcVersion",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = "Existing worlds, saves, resource packs, and configurations in '${env?.instanceName ?: "instance"}' will be preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                Text(
                    text = "Select Mod Loader",
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherColors.OnSurfaceDark
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    ModLoaderInstaller.SUPPORTED_INSTALL_LOADERS.forEach { loader ->
                        FilterChip(
                            selected = state.installLoaderSelectedType == loader,
                            onClick = { onSelectLoaderType(loader) },
                            enabled = !state.isInstallingModLoader,
                            label = { Text(loader.name) }
                        )
                    }
                }

                if (state.isInstallingModLoader && progress != null) {
                    AetherSurfaceCard(
                        containerColor = AetherColors.SurfaceElevatedDark
                    ) {
                        Text(
                            text = progress.stage.displayTitle,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = AetherColors.AccentCyan
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = AetherColors.AccentCyan
                        )
                        if (!progress.currentItem.isNullOrBlank()) {
                            Text(
                                text = progress.currentItem,
                                style = AetherMonoStyle,
                                color = AetherColors.OnSurfaceVariantDark
                            )
                        }
                    }
                } else if (state.isLoadingLoaderCandidates) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AetherColors.AccentCyan)
                        Text(
                            text = "Fetching compatible ${state.installLoaderSelectedType.name} versions for Minecraft $mcVersion...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else if (!state.loaderCandidatesError.isNullOrBlank()) {
                    Text(
                        text = state.loaderCandidatesError,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.TierBlockedFg
                    )
                    AetherOutlinedButton(
                        text = "Retry Fetching Versions",
                        onClick = onRefreshCandidates
                    )
                } else {
                    Text(
                        text = "Compatible ${state.installLoaderSelectedType.name} Versions (${state.installLoaderCandidates.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    state.installLoaderCandidates.take(15).forEach { candidate ->
                        val isSelected = state.installLoaderSelectedCandidate?.loaderVersion == candidate.loaderVersion
                        AetherSurfaceCard(
                            onClick = { onSelectCandidate(candidate) },
                            borderColor = if (isSelected) AetherColors.AccentCyan else AetherColors.OutlineVariantDark
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = candidate.displayLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) AetherColors.AccentCyan else AetherColors.OnSurfaceDark
                                )
                                if (candidate.isRecommended) {
                                    InfoBadgePill(
                                        text = "Recommended",
                                        fg = AetherColors.TierVerifiedFg,
                                        bg = AetherColors.TierVerifiedBg
                                    )
                                }
                            }
                        }
                    }
                }

                if (!state.modLoaderInstallError.isNullOrBlank()) {
                    Text(
                        text = state.modLoaderInstallError,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.TierBlockedFg
                    )
                }
            }
        },
        confirmButton = {
            if (!state.isInstallingModLoader) {
                TextButton(
                    onClick = onStartInstall,
                    enabled = state.installLoaderSelectedCandidate != null && !state.isLoadingLoaderCandidates
                ) {
                    Text(
                        text = "Install ${state.installLoaderSelectedType.name}",
                        color = AetherColors.AccentCyan
                    )
                }
            }
        },
        dismissButton = {
            if (state.isInstallingModLoader) {
                TextButton(onClick = onCancelInstall) {
                    Text("Cancel Installation", color = AetherColors.TierBlockedFg)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

fun formatCompactNumber(value: Long): String = when {
    value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
    else -> value.toString()
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
