package com.star.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRelationType
import com.star.launcher.mods.api.CurseForgeReleaseType
import com.star.launcher.mods.compat.ModCompatibilityAssessment
import com.star.launcher.mods.ui.ModsUiState
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
fun ModDetailScreen(
    modId: Long,
    state: ModsUiState,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onUpdateDetailFilters: (mcVersion: String?, loader: CurseForgeModLoader, releaseType: CurseForgeReleaseType?) -> Unit,
    onPrepareInstall: (CurseForgeMod, CurseForgeFile) -> Unit,
    onConfirmPlanInstall: () -> Unit,
    onDismissPlanDialog: () -> Unit,
    onDismissRestrictedNotice: () -> Unit,
    onCancelDownload: (Long) -> Unit,
    onRetryLastInstall: () -> Unit,
    onDismissProgressItem: (Long) -> Unit,
    onClearBanners: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detail = state.detailState
    val mod = detail?.mod
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = mod?.name ?: "Mod Details",
                subtitle = mod?.let { "by ${it.primaryAuthor} • Project #$modId" } ?: "CurseForge Mod #$modId",
                backLabel = "Mods",
                onBack = onBack
            )
        }

        if (state.activeDownloads.isNotEmpty()) {
            items(state.activeDownloads.values.toList(), key = { "det_dl_${it.fileId}" }) { progress ->
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
                    title = "Installation Issue",
                    message = state.installErrorBanner,
                    recoveryLabel = "Retry",
                    onRecovery = onRetryLastInstall,
                    onDismiss = onClearBanners
                )
            }
        }

        if (detail?.isLoading == true && mod == null) {
            item {
                AetherLoadingState(message = "Loading mod metadata, description, and files...")
            }
            return@LazyColumn
        }

        if (detail?.errorMessage != null && mod == null) {
            item {
                AetherErrorCard(
                    title = "Unable to Load Mod",
                    message = detail.errorMessage,
                    recoveryLabel = "Retry",
                    onRecovery = onRetryLoad
                )
            }
            return@LazyColumn
        }

        if (mod == null) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Mods,
                    title = "Mod Unavailable",
                    description = "No metadata loaded for mod #$modId.",
                    actionLabel = "Back to Mods",
                    onAction = onBack
                )
            }
            return@LazyColumn
        }

        // Hero mod card
        item {
            AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AetherRemoteImage(
                        url = mod.logo?.url ?: mod.logo?.thumbnailUrl,
                        contentDescription = mod.name,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = mod.name,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = AetherColors.OnSurfaceDark
                        )
                        Text(
                            text = "Author(s): ${mod.authors.joinToString { it.name }.ifBlank { "Unknown" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AetherMetricChip(
                                label = "Downloads",
                                value = formatCompactNumber(mod.downloadCount),
                                accentColor = AetherColors.AccentCyan
                            )
                            detail.overviewAssessment?.let { overview ->
                                ModCompatibilityPill(overview.level)
                            }
                        }
                    }
                }

                if (mod.summary.isNotBlank()) {
                    Text(
                        text = mod.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                }

                val websiteUrl = mod.links.websiteUrl
                if (!websiteUrl.isNullOrBlank()) {
                    AetherOutlinedButton(
                        text = "Open CurseForge Project Page",
                        onClick = { runCatching { uriHandler.openUri(websiteUrl) } },
                        icon = AetherIcons.ExternalLink
                    )
                }
            }
        }

        // Screenshots gallery
        if (mod.screenshots.isNotEmpty()) {
            item {
                AetherSectionHeader(
                    title = "Screenshots",
                    subtitle = "${mod.screenshots.size} media item(s) from CurseForge"
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    items(mod.screenshots, key = { "ss_${it.id}_${it.url}" }) { shot ->
                        AetherSurfaceCard(modifier = Modifier.width(260.dp)) {
                            AetherRemoteImage(
                                url = shot.url ?: shot.thumbnailUrl,
                                contentDescription = shot.title ?: mod.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(145.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            if (!shot.title.isNullOrBlank()) {
                                Text(
                                    text = shot.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AetherColors.OnSurfaceVariantDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Clearly distinguish API-returned metadata vs STAR Launcher compatibility inference
        item {
            AetherSurfaceCard {
                Text(
                    text = "CurseForge API Metadata",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = "Reported directly by the CurseForge Core API",
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                val apiLoaders = mod.supportedLoadersFromLatest.joinToString { it.displayName }.ifBlank { "None listed on latest files" }
                val apiMcVersions = mod.supportedMinecraftVersionsFromLatest.take(12).joinToString().ifBlank { "None listed on latest files" }
                val cats = mod.categories.joinToString { it.name }.ifBlank { "Uncategorized" }
                val distLabel = when (mod.allowModDistribution) {
                    true -> "Allowed via third-party API"
                    false -> "Disabled by author (manual browser download required)"
                    null -> "Unspecified"
                }

                Text("Categories: $cats", style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceDark)
                Text("API Loaders: $apiLoaders", style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceDark)
                Text("API MC Versions: $apiMcVersions", style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceDark)
                Text("Third-party Distribution: $distLabel", style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceDark)
            }
        }

        item {
            val env = state.instanceEnvironment
            val overview = detail.overviewAssessment
            AetherSurfaceCard(
                borderColor = AetherColors.AccentBlue.copy(alpha = 0.45f)
            ) {
                Text(
                    text = "STAR Launcher Compatibility Analysis",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.AccentBlue
                )
                Text(
                    text = if (env != null) {
                        "Inferred against active instance '${env.instanceName}' (${env.baseMinecraftVersion ?: "Unknown MC"} • ${env.loaderType.name})"
                    } else {
                        "No active instance selected — select an instance before installing."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                if (overview != null) {
                    ModCompatibilityPill(overview.level)
                    Text(
                        text = overview.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceDark
                    )
                    overview.starInferences.forEach { inference ->
                        Text(
                            text = "• $inference",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                }
            }
        }

        // Description
        val descText = detail.descriptionText
        if (!descText.isNullOrBlank()) {
            item {
                AetherSurfaceCard {
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AetherColors.OnSurfaceDark
                    )
                    Text(
                        text = descText,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                }
            }
        }

        // Available File Versions + Filters
        item {
            FileVersionFiltersCard(
                allFiles = detail.allFiles,
                selectedMcVersion = detail.filterMcVersion,
                selectedLoader = detail.filterLoader,
                selectedReleaseType = detail.filterReleaseType,
                onUpdateFilters = onUpdateDetailFilters
            )
        }

        if (detail.isLoading) {
            item {
                AetherLoadingState(message = "Refreshing full CurseForge file list...")
            }
        }

        if (detail.filteredFiles.isEmpty()) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Versions,
                    title = "No Files Match Filters",
                    description = "Clear the Minecraft version, loader, or release channel filter to see all ${detail.allFiles.size} file(s).",
                    actionLabel = "Show All Files",
                    onAction = { onUpdateDetailFilters(null, CurseForgeModLoader.ANY, null) }
                )
            }
        } else {
            items(detail.filteredFiles, key = { "file_${it.id}" }) { file ->
                ModFileVersionCard(
                    mod = mod,
                    file = file,
                    assessment = detail.fileAssessments[file.id],
                    hasSelectedInstance = state.instanceEnvironment != null,
                    onInstall = { onPrepareInstall(mod, file) },
                    onOpenWebsite = {
                        mod.links.websiteUrl?.let { url ->
                            runCatching { uriHandler.openUri(url) }
                        }
                    }
                )
            }
        }
    }

    val pendingPlan = state.pendingPlanDialog
    if (pendingPlan != null) {
        ModInstallPlanDialog(
            dialogState = pendingPlan,
            onConfirm = onConfirmPlanInstall,
            onDismiss = onDismissPlanDialog
        )
    }

    val restricted = state.restrictedNotice
    if (restricted != null) {
        RestrictedDownloadDialog(
            notice = restricted,
            onDismiss = onDismissRestrictedNotice
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileVersionFiltersCard(
    allFiles: List<CurseForgeFile>,
    selectedMcVersion: String?,
    selectedLoader: CurseForgeModLoader,
    selectedReleaseType: CurseForgeReleaseType?,
    onUpdateFilters: (String?, CurseForgeModLoader, CurseForgeReleaseType?) -> Unit
) {
    var showVersionMenu by remember { mutableStateOf(false) }
    val versionChoices = remember(allFiles) {
        allFiles.flatMap { it.declaredMinecraftVersions }.distinct()
    }

    AetherSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Available Files (${allFiles.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AetherColors.OnSurfaceDark
            )
            TextButton(
                onClick = { onUpdateFilters(null, CurseForgeModLoader.ANY, null) }
            ) {
                Text("Show All", color = AetherColors.AccentCyan)
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
                color = AetherColors.OnSurfaceVariantDark
            )
            androidx.compose.foundation.layout.Box {
                AetherOutlinedButton(
                    text = selectedMcVersion ?: "All MC Versions",
                    onClick = { showVersionMenu = true }
                )
                DropdownMenu(
                    expanded = showVersionMenu,
                    onDismissRequest = { showVersionMenu = false },
                    modifier = Modifier.heightIn(max = 280.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("All MC Versions") },
                        onClick = {
                            showVersionMenu = false
                            onUpdateFilters(null, selectedLoader, selectedReleaseType)
                        }
                    )
                    versionChoices.forEach { mc ->
                        DropdownMenuItem(
                            text = { Text(mc) },
                            onClick = {
                                showVersionMenu = false
                                onUpdateFilters(mc, selectedLoader, selectedReleaseType)
                            }
                        )
                    }
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
        ) {
            CurseForgeModLoader.selectableFilters.forEach { loader ->
                FilterChip(
                    selected = selectedLoader == loader,
                    onClick = { onUpdateFilters(selectedMcVersion, loader, selectedReleaseType) },
                    label = { Text(loader.displayName) }
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
        ) {
            FilterChip(
                selected = selectedReleaseType == null,
                onClick = { onUpdateFilters(selectedMcVersion, selectedLoader, null) },
                label = { Text("All Channels") }
            )
            CurseForgeReleaseType.entries.forEach { channel ->
                FilterChip(
                    selected = selectedReleaseType == channel,
                    onClick = { onUpdateFilters(selectedMcVersion, selectedLoader, channel) },
                    label = { Text(channel.displayName) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModFileVersionCard(
    mod: CurseForgeMod,
    file: CurseForgeFile,
    assessment: ModCompatibilityAssessment?,
    hasSelectedInstance: Boolean,
    onInstall: () -> Unit,
    onOpenWebsite: () -> Unit
) {
    val isRestricted = mod.allowModDistribution == false || file.downloadUrl.isNullOrBlank()
    val canInstallFile = (assessment?.canInstall ?: false) && hasSelectedInstance && !isRestricted

    AetherSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = file.fileName,
                    style = AetherMonoStyle,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            InfoBadgePill(
                text = file.releaseType.displayName,
                fg = when (file.releaseType) {
                    CurseForgeReleaseType.RELEASE -> AetherColors.TierVerifiedFg
                    CurseForgeReleaseType.BETA -> AetherColors.TierTestedFg
                    CurseForgeReleaseType.ALPHA -> AetherColors.TierBlockedFg
                },
                bg = when (file.releaseType) {
                    CurseForgeReleaseType.RELEASE -> AetherColors.TierVerifiedBg
                    CurseForgeReleaseType.BETA -> AetherColors.TierTestedBg
                    CurseForgeReleaseType.ALPHA -> AetherColors.TierBlockedBg
                }
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (assessment != null) {
                ModCompatibilityPill(assessment.level)
            }
            file.declaredLoaders.forEach { loader ->
                InfoBadgePill(
                    text = loader.displayName,
                    fg = AetherColors.AccentBlue,
                    bg = AetherColors.PrimaryContainerDark
                )
            }
            file.declaredMinecraftVersions.take(4).forEach { mc ->
                InfoBadgePill(
                    text = mc,
                    fg = AetherColors.OnSurfaceVariantDark,
                    bg = AetherColors.SurfaceVariantDark
                )
            }
            InfoBadgePill(
                text = formatBytes(file.fileLengthBytes),
                fg = AetherColors.OnSurfaceVariantDark,
                bg = AetherColors.SurfaceVariantDark
            )
        }

        if (assessment != null) {
            Text(
                text = assessment.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (assessment.canInstall) AetherColors.OnSurfaceVariantDark else AetherColors.TierBlockedFg
            )
        }

        val reqDeps = file.dependencies.filter { it.relationType == CurseForgeRelationType.REQUIRED_DEPENDENCY }
        val optDeps = file.dependencies.filter { it.relationType == CurseForgeRelationType.OPTIONAL_DEPENDENCY }
        val incDeps = file.dependencies.filter { it.relationType == CurseForgeRelationType.INCOMPATIBLE }
        if (reqDeps.isNotEmpty() || optDeps.isNotEmpty() || incDeps.isNotEmpty()) {
            HorizontalDivider(color = AetherColors.OutlineVariantDark)
            Text(
                text = buildString {
                    append("Dependencies: ")
                    append("${reqDeps.size} required, ${optDeps.size} optional, ${incDeps.size} incompatible")
                },
                style = MaterialTheme.typography.labelSmall,
                color = AetherColors.OnSurfaceVariantDark
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            when {
                isRestricted -> {
                    AetherOutlinedButton(
                        text = "Manual Download Only",
                        onClick = onOpenWebsite,
                        icon = AetherIcons.ExternalLink
                    )
                }
                !hasSelectedInstance -> {
                    AetherOutlinedButton(
                        text = "Select Instance First",
                        onClick = onInstall,
                        enabled = false
                    )
                }
                else -> {
                    AetherPrimaryButton(
                        text = if (canInstallFile) "Install" else "Incompatible",
                        onClick = onInstall,
                        enabled = canInstallFile,
                        icon = AetherIcons.Download
                    )
                }
            }
        }
    }
}
