package com.star.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherMetricChip
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import java.io.File

@Composable
fun RuntimeDetailScreen(
    runtimeName: String,
    state: AetherDashboardUiState,
    onBack: () -> Unit,
    onSetDefaultRuntime: (String) -> Unit,
    onDeleteRuntime: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val inspection = state.runtimeInspections.firstOrNull { it.name == runtimeName }
    val registry = state.runtimes.firstOrNull { it.runtimeId == runtimeName || it.displayName == runtimeName }
    val defaultRuntimeName = LauncherPreferences.DEFAULT_PREF?.getString("defaultRuntime", "") ?: ""
    val isDefault = runtimeName == defaultRuntimeName

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = runtimeName,
                subtitle = "Java Runtime Diagnostics",
                backLabel = "Runtimes",
                onBack = onBack
            )
        }

        if (inspection == null && registry == null) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Runtime,
                    title = "Runtime Not Found",
                    description = "The runtime '$runtimeName' is no longer present on disk.",
                    actionLabel = "Back to Runtimes",
                    onAction = onBack
                )
            }
            return@LazyColumn
        }

        val javaMajor = inspection?.javaVersion ?: registry?.majorVersion ?: 0
        val arch = inspection?.arch ?: registry?.arch ?: "unknown"
        val isValid = inspection?.status == MultiRTUtils.RuntimeValidationStatus.VALID ||
            registry?.isVerified == true
        val statusReason = inspection?.reason ?: if (isValid) "Verified" else "Validation pending"
        val runtimeDirPath = registry?.installPath ?: File(Tools.MULTIRT_HOME ?: "", runtimeName).absolutePath

        item {
            AetherAccentCard(
                accentColor = if (isValid) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg
            ) {
                Text(
                    text = if (isDefault) "$runtimeName (Default)" else runtimeName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AetherColors.OnBackgroundDark
                )
                Text(
                    text = statusReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isValid) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherMetricChip(
                        label = "Java Major",
                        value = if (javaMajor > 0) "JRE $javaMajor" else "Unknown",
                        accentColor = AetherColors.AccentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    AetherMetricChip(
                        label = "ABI",
                        value = arch,
                        accentColor = AetherColors.AccentViolet,
                        modifier = Modifier.weight(1f)
                    )
                    AetherMetricChip(
                        label = "Health",
                        value = if (isValid) "Valid" else "Issue",
                        accentColor = if (isValid) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            AetherSurfaceCard {
                Text(
                    text = "Storage & Compatibility",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Directory: $runtimeDirPath\nDevice ABI: ${state.deviceCapabilities?.cpuAbiPrimary ?: "Unknown"}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = if (isDefault) "Default Runtime" else "Set as Default",
                        enabled = !isDefault && isValid,
                        onClick = { onSetDefaultRuntime(runtimeName) },
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Remove Runtime",
                        onClick = {
                            onDeleteRuntime(runtimeName)
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}
