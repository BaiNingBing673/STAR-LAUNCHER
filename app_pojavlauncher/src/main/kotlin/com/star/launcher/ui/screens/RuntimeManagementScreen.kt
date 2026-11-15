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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.dashboard.RuntimeInspectionItem
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.prefs.LauncherPreferences

@Composable
fun RuntimeManagementScreen(
    state: AetherDashboardUiState,
    onBack: () -> Unit,
    onOpenRuntimeDetail: (String) -> Unit,
    onDownloadRuntime: (Int) -> Unit,
    onImportRuntimeFile: () -> Unit,
    onSetDefaultRuntime: (String) -> Unit,
    onDeleteRuntime: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultRuntimeName = LauncherPreferences.DEFAULT_PREF?.getString("defaultRuntime", "") ?: ""

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = "Runtime Management",
                subtitle = "${state.deviceCapabilities?.cpuAbiPrimary ?: "Device ABI"} · ${Tools.MULTIRT_HOME ?: "runtimes/"}",
                backLabel = "Settings",
                onBack = onBack
            )
        }

        item {
            AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                Text(
                    text = "Install or Import OpenJDK Runtimes",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnBackgroundDark
                )
                Text(
                    text = "Download verified External JRE builds or import a local runtime archive (.tar.xz).",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                if (state.isDownloadingRuntimeJava != null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AetherColors.AccentCyan
                    )
                    Text(
                        text = "Installing External-${state.isDownloadingRuntimeJava}...",
                        style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    state.downloadableRuntimes.forEach { opt ->
                        val isBusyThis = state.isDownloadingRuntimeJava == opt.majorVersion
                        val label = when {
                            isBusyThis -> "..."
                            opt.isInstalled -> "JRE ${opt.majorVersion} ✓"
                            !opt.isSupportedOnDevice -> "JRE ${opt.majorVersion} N/A"
                            else -> "JRE ${opt.majorVersion}"
                        }
                        AetherTonalButton(
                            text = label,
                            enabled = opt.isSupportedOnDevice && state.isDownloadingRuntimeJava == null,
                            onClick = { onDownloadRuntime(opt.majorVersion) },
                            modifier = Modifier.weight(1f),
                            containerColor = if (opt.isInstalled) {
                                AetherColors.TierVerifiedBg
                            } else {
                                AetherColors.PrimaryContainerDark
                            },
                            contentColor = if (opt.isInstalled) {
                                AetherColors.TierVerifiedFg
                            } else {
                                AetherColors.OnPrimaryContainerDark
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "Import .tar.xz",
                        icon = AetherIcons.Download,
                        onClick = onImportRuntimeFile,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Rescan",
                        icon = AetherIcons.Refresh,
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (state.runtimeInspections.isEmpty()) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Runtime,
                    title = "No Runtimes Installed",
                    description = "Download an OpenJDK build above or import a .tar.xz package to launch Minecraft.",
                    actionLabel = "Download JRE 21",
                    onAction = { onDownloadRuntime(21) }
                )
            }
        } else {
            items(state.runtimeInspections, key = { it.name }) { rt ->
                RuntimeCardRow(
                    runtime = rt,
                    isDefault = rt.name == defaultRuntimeName,
                    onInspect = { onOpenRuntimeDetail(rt.name) },
                    onSetDefault = { onSetDefaultRuntime(rt.name) },
                    onDelete = { onDeleteRuntime(rt.name) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}

@Composable
private fun RuntimeCardRow(
    runtime: RuntimeInspectionItem,
    isDefault: Boolean,
    onInspect: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val isValid = runtime.status == MultiRTUtils.RuntimeValidationStatus.VALID
    val badgeColor = if (isValid) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg
    AetherSurfaceCard(
        borderColor = if (isDefault) AetherColors.AccentCyan.copy(alpha = 0.5f) else badgeColor.copy(alpha = 0.3f),
        onClick = onInspect
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = runtime.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    Text(
                        text = "Java ${runtime.javaVersion} · ${runtime.arch}",
                        style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                    )
                }
                if (isDefault) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = AetherColors.AccentCyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "DEFAULT",
                            style = MaterialTheme.typography.labelSmall,
                            color = AetherColors.AccentCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Text(
                text = runtime.reason,
                style = MaterialTheme.typography.bodySmall,
                color = badgeColor
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                AetherTonalButton(text = "Details", onClick = onInspect)
                if (!isDefault && isValid) {
                    AetherOutlinedButton(text = "Set Default", onClick = onSetDefault)
                }
                AetherOutlinedButton(text = "Remove", onClick = onDelete)
            }
        }
    }
}
