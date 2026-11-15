package com.star.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.instance.InstanceContentSummary
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherMetricChip
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryPlayButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.dashboard.mapStatusToBadgeTier
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.CompatibilityBadgeTier
import com.star.launcher.ui.theme.CompatibilityTierBadge

@Composable
fun InstanceDetailScreen(
    instanceId: String,
    state: AetherDashboardUiState,
    contentSummary: InstanceContentSummary?,
    onBack: () -> Unit,
    onLaunchInstance: (String) -> Unit,
    onEditSettings: (GameInstanceEntity) -> Unit,
    onEvaluateCompat: (GameInstanceEntity) -> Unit,
    onSelectFeatured: (String) -> Unit,
    onToggleMod: (GameInstanceEntity, String) -> Unit,
    onDeleteInstance: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val instance = state.instances.firstOrNull { it.instanceId == instanceId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = instance?.name ?: "Instance Details",
                subtitle = instance?.versionId ?: instanceId,
                backLabel = "Library",
                onBack = onBack
            )
        }

        if (instance == null) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Library,
                    title = "Instance Not Found",
                    description = "This profile may have been deleted or renamed.",
                    actionLabel = "Back to Library",
                    onAction = onBack
                )
            }
            return@LazyColumn
        }

        val rendererName = state.rendererProfiles
            .firstOrNull { it.profileId == instance.rendererProfileId }?.displayName
            ?: "Default Renderer"
        val runtimeLabel = state.runtimes
            .firstOrNull { it.runtimeId == instance.runtimeId }?.displayName
            ?: (instance.runtimeId ?: "Auto-pick JRE")
        val isFeatured = state.featuredInstance?.instanceId == instance.instanceId

        item {
            AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AetherColors.OnBackgroundDark
                )
                Text(
                    text = "Profile ID: ${instance.instanceId}\nGame Dir: ${instance.gameDirPath}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherMetricChip(
                        label = "Version",
                        value = instance.versionId ?: "Unset",
                        accentColor = AetherColors.AccentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    AetherMetricChip(
                        label = "Runtime",
                        value = runtimeLabel,
                        accentColor = AetherColors.AccentViolet,
                        modifier = Modifier.weight(1f)
                    )
                    AetherMetricChip(
                        label = "RAM",
                        value = "${instance.allocatedRamMb} MB",
                        accentColor = AetherColors.TierVerifiedFg,
                        modifier = Modifier.weight(0.8f)
                    )
                }

                AetherPrimaryPlayButton(
                    label = "Play ${instance.name}",
                    subtitle = "$rendererName · ${instance.versionId ?: "Select version in settings"}",
                    enabled = !state.isBusy,
                    onClick = { onLaunchInstance(instance.instanceId) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Edit Settings",
                        onClick = { onEditSettings(instance) },
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = if (isFeatured) "Pinned Home" else "Pin to Home",
                        enabled = !isFeatured,
                        onClick = { onSelectFeatured(instance.instanceId) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            AetherSurfaceCard {
                Text(
                    text = "Launch & Compatibility",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Renderer: $rendererName\nJVM Args: ${instance.customJvmArgs?.ifBlank { "(Default)" } ?: "(Default)"}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
                state.selectedCompatibilityReport?.let { report ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                        CompatibilityTierBadge(tier = CompatibilityBadgeTier.DETECTED)
                        CompatibilityTierBadge(
                            tier = mapStatusToBadgeTier(
                                CompatibilityBadgeTier.THEORETICAL,
                                report.theoretical.status
                            )
                        )
                    }
                    Text(
                        text = report.theoretical.summaryReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceDark
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    AetherTonalButton(
                        text = "Evaluate Compatibility",
                        onClick = { onEvaluateCompat(instance) }
                    )
                    AetherOutlinedButton(
                        text = "Delete Profile",
                        onClick = {
                            onDeleteInstance(instance.instanceId)
                            onBack()
                        }
                    )
                }
            }
        }

        item {
            AetherSurfaceCard {
                Text(
                    text = "Installed Content",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                val mods = contentSummary?.mods.orEmpty()
                if (mods.isEmpty()) {
                    Text(
                        text = "No .jar mods detected in ${instance.gameDirPath}/mods.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                        mods.forEach { mod ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = mod.fileName,
                                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark),
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = mod.isEnabled,
                                    onCheckedChange = { onToggleMod(instance, mod.fileName) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}
