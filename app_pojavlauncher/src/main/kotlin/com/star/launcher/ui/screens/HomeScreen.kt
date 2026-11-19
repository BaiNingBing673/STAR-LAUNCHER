package com.star.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameSessionEntity
import com.star.launcher.data.db.model.SessionStatus
import com.star.launcher.instance.InstanceManager
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherArtworkPanel
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherMetricChip
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryPlayButton
import com.star.launcher.ui.components.AetherRuntimeAlertCard
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.components.VersionErrorDiagnosticsCard
import com.star.launcher.ui.components.VersionInstallProgressCard
import com.star.launcher.ui.components.VersionReleaseTypeTag
import com.star.launcher.ui.components.VersionStateBadge
import com.star.launcher.ui.components.VersionStatusPill
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.dashboard.DashboardTab
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.studio.StarComponentTarget
import com.star.launcher.version.VersionCatalogItem
import com.star.launcher.version.VersionState
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    state: AetherDashboardUiState,
    isExpandedWidth: Boolean,
    reducedMotion: Boolean,
    onSelectFeatured: (String) -> Unit,
    onLaunchInstance: (String) -> Unit,
    onPlayVersion: (String) -> Unit = {},
    onInstallVersion: (String) -> Unit = {},
    onCancelVersionInstall: (String) -> Unit = {},
    onRetryVersionInstall: (String) -> Unit = {},
    onOpenVersionSelector: () -> Unit = {},
    onOpenCreateInstance: () -> Unit,
    onNavigateTab: (DashboardTab) -> Unit,
    onInstallRecommendedRuntime: (Int) -> Unit,
    onOpenDiscord: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    onOpenRuntimeManagement: () -> Unit = { onNavigateTab(DashboardTab.SETTINGS) },
    onOpenInstanceDetail: (String) -> Unit = { _ -> onNavigateTab(DashboardTab.LIBRARY) },
    modifier: Modifier = Modifier
) {
    val featured = state.featuredInstance
    val selectedVersion = state.selectedVersionItem
    val featuredVersion = selectedVersion?.entity ?: featured?.versionId?.let { vid ->
        state.versions.firstOrNull { it.versionId == vid }
    }
    val requiredJava = featuredVersion?.requiredJavaMajor ?: 21
    val compatibleRuntimes = state.runtimes.filter { it.isVerified && it.majorVersion >= requiredJava }
    val selectedRuntime = featured?.runtimeId?.let { rid ->
        state.runtimes.firstOrNull { it.runtimeId == rid }
    } ?: compatibleRuntimes.minByOrNull { it.majorVersion } ?: state.runtimes.firstOrNull()

    val hasCompatibleRuntime = compatibleRuntimes.isNotEmpty() ||
        (selectedRuntime != null && selectedRuntime.majorVersion >= requiredJava)
    val deviceAbi = state.deviceCapabilities?.cpuAbiPrimary ?: "arm64-v8a"
    val activeAccount = state.accounts.firstOrNull { it.isSelected }
    val activeVersionRendererProfileId = selectedVersion?.versionId?.let { state.versionRendererProfiles[it] }
    val resolvedRendererProfileId = featured
        ?.takeIf { selectedVersion == null || it.versionId == selectedVersion.versionId }
        ?.rendererProfileId
        ?: activeVersionRendererProfileId
        ?: featured?.rendererProfileId
    val rendererName = resolvedRendererProfileId?.let { pid ->
        state.rendererProfiles.firstOrNull { it.profileId == pid }?.displayName
    } ?: state.rendererProfiles.firstOrNull { it.isDefault }?.displayName ?: "MobileGlues"

    if (isExpandedWidth) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = AetherSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1.1f),
                contentPadding = PaddingValues(vertical = AetherSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
            ) {
                item {
                    HomeHeroColumn(
                        state = state,
                        featured = featured,
                        selectedVersion = selectedVersion,
                        requiredJava = requiredJava,
                        selectedRuntimeLabel = selectedRuntime?.displayName ?: "No Java $requiredJava+ JRE",
                        rendererName = rendererName,
                        deviceAbi = deviceAbi,
                        hasCompatibleRuntime = hasCompatibleRuntime,
                        reducedMotion = reducedMotion,
                        onSelectFeatured = onSelectFeatured,
                        onLaunchInstance = onLaunchInstance,
                        onPlayVersion = onPlayVersion,
                        onInstallVersion = onInstallVersion,
                        onCancelVersionInstall = onCancelVersionInstall,
                        onRetryVersionInstall = onRetryVersionInstall,
                        onOpenVersionSelector = onOpenVersionSelector,
                        onOpenCreateInstance = onOpenCreateInstance,
                        onNavigateTab = onNavigateTab,
                        onInstallRecommendedRuntime = onInstallRecommendedRuntime,
                        onOpenRuntimeManagement = onOpenRuntimeManagement
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(0.9f),
                contentPadding = PaddingValues(vertical = AetherSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
            ) {
                item {
                    HomeSecondaryColumn(
                        state = state,
                        activeAccountName = activeAccount?.username,
                        onNavigateTab = onNavigateTab,
                        onOpenDiscord = onOpenDiscord,
                        onOpenAccountSettings = onOpenAccountSettings
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(AetherSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
        ) {
            item {
                HomeHeroColumn(
                    state = state,
                    featured = featured,
                    selectedVersion = selectedVersion,
                    requiredJava = requiredJava,
                    selectedRuntimeLabel = selectedRuntime?.displayName ?: "No Java $requiredJava+ JRE",
                    rendererName = rendererName,
                    deviceAbi = deviceAbi,
                    hasCompatibleRuntime = hasCompatibleRuntime,
                    reducedMotion = reducedMotion,
                    onSelectFeatured = onSelectFeatured,
                    onLaunchInstance = onLaunchInstance,
                    onPlayVersion = onPlayVersion,
                    onInstallVersion = onInstallVersion,
                    onCancelVersionInstall = onCancelVersionInstall,
                    onRetryVersionInstall = onRetryVersionInstall,
                    onOpenVersionSelector = onOpenVersionSelector,
                    onOpenCreateInstance = onOpenCreateInstance,
                    onNavigateTab = onNavigateTab,
                    onInstallRecommendedRuntime = onInstallRecommendedRuntime,
                    onOpenRuntimeManagement = onOpenRuntimeManagement
                )
            }
            item {
                HomeSecondaryColumn(
                    state = state,
                    activeAccountName = activeAccount?.username,
                    onNavigateTab = onNavigateTab,
                    onOpenDiscord = onOpenDiscord,
                    onOpenAccountSettings = onOpenAccountSettings
                )
            }
        }
    }
}

@Composable
private fun HomeHeroColumn(
    state: AetherDashboardUiState,
    featured: GameInstanceEntity?,
    selectedVersion: VersionCatalogItem?,
    requiredJava: Int,
    selectedRuntimeLabel: String,
    rendererName: String,
    deviceAbi: String,
    hasCompatibleRuntime: Boolean,
    reducedMotion: Boolean,
    onSelectFeatured: (String) -> Unit,
    onLaunchInstance: (String) -> Unit,
    onPlayVersion: (String) -> Unit,
    onInstallVersion: (String) -> Unit,
    onCancelVersionInstall: (String) -> Unit,
    onRetryVersionInstall: (String) -> Unit,
    onOpenVersionSelector: () -> Unit,
    onOpenCreateInstance: () -> Unit,
    onNavigateTab: (DashboardTab) -> Unit,
    onInstallRecommendedRuntime: (Int) -> Unit,
    onOpenRuntimeManagement: () -> Unit
) {
    val activeVersionId = selectedVersion?.versionId ?: featured?.versionId
    val activeLoaderText = selectedVersion?.loaderLabel ?: featured?.let {
        state.versions.firstOrNull { v -> v.versionId == it.versionId }?.modLoaderType?.name
    } ?: "MINECRAFT JAVA"

    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        AetherArtworkPanel(
            instanceTitle = featured?.name ?: (activeVersionId?.let { "Minecraft $it" } ?: "STAR Launcher"),
            versionLabel = activeVersionId ?: "No Version Selected",
            loaderLabel = activeLoaderText,
            runtimeSummary = "$selectedRuntimeLabel · $rendererName",
            reducedMotion = reducedMotion
        )

        // Selected Version & Version Selector card (Section 5 integration)
        AetherSurfaceCard(
            componentTarget = StarComponentTarget.VERSION_PANELS,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    Text(
                        text = "Selected Minecraft Version",
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                    Text(
                        text = activeVersionId ?: "Choose a version to install or play",
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherColors.OnSurfaceDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedVersion != null) {
                            VersionStateBadge(state = selectedVersion.state)
                            VersionReleaseTypeTag(releaseType = selectedVersion.releaseType)
                            if (!selectedVersion.loaderLabel.isNullOrBlank()) {
                                VersionStatusPill(
                                    text = selectedVersion.loaderLabel,
                                    backgroundColor = AetherColors.PrimaryContainerDark,
                                    contentColor = AetherColors.AccentCyan
                                )
                            }
                        } else {
                            VersionStatusPill(
                                text = "Vanilla",
                                backgroundColor = AetherColors.SurfaceVariantDark,
                                contentColor = AetherColors.OnSurfaceVariantDark
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(AetherSpacing.Sm))
                AetherOutlinedButton(
                    text = "Versions",
                    onClick = onOpenVersionSelector
                )
            }
        }

        if (selectedVersion?.isInstalling == true && selectedVersion.progress != null) {
            VersionInstallProgressCard(
                progress = selectedVersion.progress,
                onCancel = { onCancelVersionInstall(selectedVersion.versionId) },
                reducedMotion = reducedMotion
            )
        } else if (selectedVersion?.isFailed == true) {
            VersionErrorDiagnosticsCard(
                versionId = selectedVersion.versionId,
                category = selectedVersion.errorCategory,
                message = selectedVersion.errorMessage ?: "Installation failed.",
                diagnostics = selectedVersion.diagnosticDetails,
                onRetry = null
            )
        }

        if (state.instances.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                items(state.instances, key = { it.instanceId }) { instance ->
                    val isSelected = instance.instanceId == featured?.instanceId
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectFeatured(instance.instanceId) },
                        label = {
                            Text(
                                text = instance.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AetherColors.AccentBlue.copy(alpha = 0.20f),
                            selectedLabelColor = AetherColors.AccentCyan
                        )
                    )
                }
            }
        }

        when {
            selectedVersion != null -> {
                val verId = selectedVersion.versionId
                when (selectedVersion.state) {
                    VersionState.INSTALLED -> {
                        val ramText = featured?.let { "${it.allocatedRamMb} MB" } ?: "${state.ramAllocationMb} MB"
                        AetherPrimaryPlayButton(
                            label = if (!hasCompatibleRuntime) "Runtime Required ($verId)" else "Play $verId",
                            subtitle = "${selectedVersion.loaderLabel ?: "Verified"} · $ramText · $rendererName",
                            enabled = !state.isBusy,
                            onClick = {
                                if (!hasCompatibleRuntime) {
                                    onOpenRuntimeManagement()
                                } else if (featured != null && featured.versionId == verId) {
                                    onLaunchInstance(featured.instanceId)
                                } else {
                                    onPlayVersion(verId)
                                }
                            }
                        )
                    }
                    VersionState.NOT_INSTALLED -> {
                        AetherPrimaryPlayButton(
                            label = "Install $verId",
                            subtitle = "Not installed locally · Tap to download required game files",
                            enabled = !state.isBusy,
                            onClick = { onInstallVersion(verId) }
                        )
                    }
                    VersionState.INSTALLING -> {
                        val pctText = selectedVersion.progress?.progressPercent?.toInt()?.let { "$it%" }
                            ?: selectedVersion.progress?.stage?.displayTitle
                            ?: "Installing..."
                        AetherPrimaryPlayButton(
                            label = "Installing $verId",
                            subtitle = pctText,
                            enabled = false,
                            onClick = {}
                        )
                    }
                    VersionState.FAILED -> {
                        AetherPrimaryPlayButton(
                            label = "Retry Install ($verId)",
                            subtitle = selectedVersion.errorCategory?.title ?: "Tap to retry download",
                            enabled = !state.isBusy,
                            onClick = { onRetryVersionInstall(verId) }
                        )
                    }
                    VersionState.UNSUPPORTED -> {
                        AetherPrimaryPlayButton(
                            label = "Unsupported Version",
                            subtitle = selectedVersion.unsupportedReason ?: "Incompatible with this device",
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            }
            featured != null -> {
                val hasValidVersion = InstanceManager.isValidVersionId(featured.versionId)
                AetherPrimaryPlayButton(
                    label = when {
                        !hasValidVersion -> "Select Version to Launch"
                        !hasCompatibleRuntime -> "Runtime Required to Launch"
                        else -> "Play ${featured.name}"
                    },
                    subtitle = "${featured.versionId?.takeIf { InstanceManager.isValidVersionId(it) } ?: "No version set"} · ${featured.allocatedRamMb} MB · $rendererName",
                    enabled = !state.isBusy,
                    onClick = {
                        when {
                            !hasValidVersion -> onOpenVersionSelector()
                            !hasCompatibleRuntime -> onOpenRuntimeManagement()
                            else -> onLaunchInstance(featured.instanceId)
                        }
                    }
                )
            }
            else -> {
                AetherPrimaryPlayButton(
                    label = "Choose Minecraft Version",
                    subtitle = "Browse and install Minecraft Java versions in Version Manager",
                    enabled = true,
                    onClick = onOpenVersionSelector
                )
            }
        }

        if (!hasCompatibleRuntime) {
            AetherRuntimeAlertCard(
                requiredJava = requiredJava,
                deviceAbi = deviceAbi,
                runtimeCount = state.runtimes.size,
                detailReason = state.runtimeInspections.firstOrNull()?.reason,
                onInstallRuntime = { onInstallRecommendedRuntime(requiredJava) },
                onOpenSettings = onOpenRuntimeManagement
            )
        }

        // Runtime & Performance telemetry strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
        ) {
            AetherMetricChip(
                label = "Java Runtime",
                value = if (hasCompatibleRuntime) selectedRuntimeLabel else "Missing JRE $requiredJava+",
                accentColor = if (hasCompatibleRuntime) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg,
                modifier = Modifier.weight(1f)
            )
            AetherMetricChip(
                label = "Renderer",
                value = rendererName,
                accentColor = AetherColors.AccentCyan,
                modifier = Modifier.weight(1f)
            )
            AetherMetricChip(
                label = "ABI",
                value = deviceAbi,
                accentColor = AetherColors.AccentViolet,
                modifier = Modifier.weight(0.8f)
            )
        }
    }
}

@Composable
private fun HomeSecondaryColumn(
    state: AetherDashboardUiState,
    activeAccountName: String?,
    onNavigateTab: (DashboardTab) -> Unit,
    onOpenDiscord: () -> Unit,
    onOpenAccountSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        // Recent game sessions
        AetherSectionHeader(
            title = "Recent Sessions",
            subtitle = "Recorded launches and exit status"
        ) {
            AetherOutlinedButton(
                text = "Library",
                onClick = { onNavigateTab(DashboardTab.LIBRARY) }
            )
        }

        if (state.recentSessions.isEmpty()) {
            AetherEmptyState(
                icon = AetherIcons.Library,
                title = "No game sessions yet",
                description = "Your recent Minecraft launches and runtime telemetry will appear here once you play."
            )
        } else {
            state.recentSessions.take(4).forEach { session ->
                RecentSessionRow(session = session)
            }
        }

        // News & Announcements preview
        AetherSectionHeader(
            title = "News & Updates",
            subtitle = "STAR release notes and runtime advisories"
        )
        AetherSurfaceCard {
            Text(
                text = "STAR Runtime Safety & Modular Core",
                style = MaterialTheme.typography.titleMedium,
                color = AetherColors.OnSurfaceDark
            )
            Text(
                text = "Runtimes are validated for CPU ABI and library integrity before launch, " +
                    "and OpenJDK External-8, 17, 21, and 25 packages can be provisioned cleanly in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.OnSurfaceVariantDark
            )
        }

        // Community & Discord card
        AetherAccentCard(accentColor = AetherColors.AccentBlue) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                Icon(
                    imageVector = AetherIcons.Community,
                    contentDescription = null,
                    tint = AetherColors.AccentCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "STAR Community & Discord",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
            }
            Text(
                text = if (state.discordInviteUrl.isNotBlank()) {
                    "Discord invite configured: ${state.discordInviteUrl}"
                } else {
                    "No Discord server invite configured yet. Set a verified invite link in the Community tab."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.OnSurfaceVariantDark
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                if (state.discordInviteUrl.isNotBlank()) {
                    AetherTonalButton(
                        text = "Open Discord",
                        icon = AetherIcons.ExternalLink,
                        onClick = onOpenDiscord,
                        containerColor = AetherColors.SecondaryContainerDark,
                        contentColor = AetherColors.OnSecondaryContainerDark
                    )
                }
                AetherOutlinedButton(
                    text = "Community Hub",
                    onClick = { onNavigateTab(DashboardTab.COMMUNITY) }
                )
            }
        }

        // Quick Account & Settings row
        AetherSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Account & Preferences",
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    Text(
                        text = if (activeAccountName != null) {
                            "Active profile: $activeAccountName"
                        } else {
                            "No account active — sign in with Microsoft or add an offline profile"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                }
                Spacer(modifier = Modifier.width(AetherSpacing.Sm))
                AetherTonalButton(
                    text = "Settings",
                    icon = AetherIcons.Settings,
                    onClick = onOpenAccountSettings
                )
            }
        }
        Spacer(modifier = Modifier.height(AetherSpacing.Md))
    }
}

@Composable
private fun RecentSessionRow(session: GameSessionEntity) {
    val dateLabel = remember(session.startedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(session.startedAt))
    }
    val statusColor = when (session.status) {
        SessionStatus.EXITED_OK -> AetherColors.TierVerifiedFg
        SessionStatus.CRASHED -> AetherColors.TierBlockedFg
        SessionStatus.RUNNING -> AetherColors.AccentCyan
        else -> AetherColors.TierTestedFg
    }
    AetherSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${session.versionId ?: "Minecraft"} · ${session.runtimeId ?: "Auto JRE"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = statusColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
            ) {
                Text(
                    text = session.status.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        val detailLine = buildString {
            session.exitCode?.let { append("Exit code $it") }
            if (!session.crashReportPath.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("Crash log: ${session.crashReportPath}")
            }
        }
        if (detailLine.isNotEmpty()) {
            Text(
                text = detailLine,
                style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
