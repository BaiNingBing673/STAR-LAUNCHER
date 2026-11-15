package com.star.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherPrimaryPlayButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.VersionErrorDiagnosticsCard
import com.star.launcher.ui.components.VersionInstallProgressCard
import com.star.launcher.ui.components.VersionStateBadge
import com.star.launcher.ui.components.VersionStatusPill
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.version.InstallationStage
import com.star.launcher.version.VersionState

@Composable
fun VersionInstallScreen(
    versionId: String?,
    displayLabel: String?,
    state: AetherDashboardUiState,
    reducedMotion: Boolean = false,
    onBack: () -> Unit,
    onInstallVersion: (String) -> Unit,
    onCancelVersionInstall: (String) -> Unit,
    onRetryVersionInstall: (String) -> Unit,
    onPlayVersion: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onCreateInstanceFromVersion: (String) -> Unit,
    onInstallFabric: (String) -> Unit
) {
    val targetVersionId = versionId ?: state.selectedVersionItem?.versionId
    val catalogItem = targetVersionId?.let { id ->
        state.versionCatalogItems.firstOrNull { it.versionId == id }
    }
    val liveProgress = targetVersionId?.let { state.versionInstallProgress[it] } ?: catalogItem?.progress

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AetherSpacing.Md),
        contentPadding = PaddingValues(top = AetherSpacing.Sm, bottom = AetherSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = displayLabel ?: (targetVersionId?.let { "Install Minecraft $it" } ?: "Version Installer"),
                subtitle = "Live download pipeline & integrity verification",
                backLabel = "Versions",
                onBack = onBack
            )
        }

        if (targetVersionId != null) {
            item {
                AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Minecraft $targetVersionId",
                                style = MaterialTheme.typography.titleLarge,
                                color = AetherColors.OnSurfaceDark
                            )
                            Text(
                                text = catalogItem?.loaderLabel ?: "Standard Client",
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherColors.OnSurfaceVariantDark
                            )
                        }
                        if (catalogItem != null) {
                            VersionStateBadge(state = catalogItem.state)
                        }
                    }

                    when (catalogItem?.state) {
                        VersionState.INSTALLED -> {
                            AetherPrimaryPlayButton(
                                label = "Play $targetVersionId",
                                subtitle = "Installation verified and ready",
                                enabled = true,
                                onClick = { onPlayVersion(targetVersionId) }
                            )
                        }
                        VersionState.NOT_INSTALLED -> {
                            AetherPrimaryButton(
                                text = "Install $targetVersionId",
                                onClick = { onInstallVersion(targetVersionId) },
                                icon = AetherIcons.Download,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        VersionState.UNSUPPORTED -> {
                            VersionErrorDiagnosticsCard(
                                versionId = targetVersionId,
                                category = null,
                                message = catalogItem.unsupportedReason ?: "Unsupported version.",
                                diagnostics = null
                            )
                        }
                        else -> Unit
                    }
                }
            }

            if (liveProgress != null && liveProgress.errorCategory == null && catalogItem?.state != VersionState.INSTALLED) {
                item {
                    VersionInstallProgressCard(
                        progress = liveProgress,
                        onCancel = { onCancelVersionInstall(targetVersionId) },
                        reducedMotion = reducedMotion
                    )
                }

                item {
                    AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Installation stages",
                            style = MaterialTheme.typography.titleMedium,
                            color = AetherColors.OnSurfaceDark
                        )
                        InstallationStage.values().forEach { stage ->
                            val isCurrent = stage == liveProgress.stage
                            val isDone = stage.stepOrder < liveProgress.stage.stepOrder || liveProgress.stage == InstallationStage.COMPLETED
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${stage.stepOrder}. ${stage.displayTitle}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = when {
                                        isCurrent -> AetherColors.AccentCyan
                                        isDone -> AetherColors.TierVerifiedFg
                                        else -> AetherColors.OnSurfaceVariantDark
                                    }
                                )
                                VersionStatusPill(
                                    text = when {
                                        isDone -> "Done"
                                        isCurrent -> "Active"
                                        else -> "Pending"
                                    },
                                    backgroundColor = when {
                                        isDone -> AetherColors.TierVerifiedBg
                                        isCurrent -> AetherColors.PrimaryContainerDark
                                        else -> AetherColors.SurfaceVariantDark
                                    },
                                    contentColor = when {
                                        isDone -> AetherColors.TierVerifiedFg
                                        isCurrent -> AetherColors.AccentCyan
                                        else -> AetherColors.OnSurfaceVariantDark
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (catalogItem?.state == VersionState.FAILED || liveProgress?.errorCategory != null) {
                val errMsg = liveProgress?.errorMessage ?: catalogItem?.errorMessage ?: "Installation failed."
                val errCat = liveProgress?.errorCategory ?: catalogItem?.errorCategory
                val diag = liveProgress?.diagnosticDetails ?: catalogItem?.diagnosticDetails
                item {
                    VersionErrorDiagnosticsCard(
                        versionId = targetVersionId,
                        category = errCat,
                        message = errMsg,
                        diagnostics = diag,
                        onRetry = { onRetryVersionInstall(targetVersionId) }
                    )
                }
            }
        }

        if (state.activeDownloads.isNotEmpty()) {
            item {
                Text(
                    text = "Background Catalog Tasks",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
            }
            items(state.activeDownloads, key = { it.taskId }) { task ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = AetherColors.SurfaceDark,
                    border = BorderStroke(1.dp, AetherColors.OutlineVariantDark)
                ) {
                    Row(
                        modifier = Modifier.padding(AetherSpacing.Md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.taskType.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = AetherColors.OnSurfaceDark
                            )
                            Text(
                                text = task.targetPath,
                                style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                            )
                        }
                        AetherOutlinedButton(
                            text = "Retry",
                            onClick = { onRetryDownload(task.taskId) }
                        )
                    }
                }
            }
        } else if (liveProgress == null && targetVersionId == null) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Versions,
                    title = "No active installation",
                    description = "Select any Minecraft version in the Version Manager to install or manage its files.",
                    actionLabel = "Back to Versions",
                    onAction = onBack
                )
            }
        }
    }
}
