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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.star.launcher.lyrics.config.LiveLyricsConfigStore
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.dashboard.mapStatusToBadgeTier
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.CompatibilityBadgeTier
import com.star.launcher.ui.theme.CompatibilityTierBadge
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: AetherDashboardUiState,
    onSelectAccount: (String) -> Unit,
    onMirrorAccountVault: (String) -> Unit,
    onCreateOfflineAccount: (String) -> Unit,
    onOpenMicrosoftLogin: () -> Unit,
    onDownloadRuntime: (Int) -> Unit,
    onDeleteRuntime: (String) -> Unit,
    onImportRuntimeFile: () -> Unit,
    onOpenRuntimeManagement: () -> Unit,
    onOpenRuntimeDetail: (String) -> Unit = { _ -> onOpenRuntimeManagement() },
    onSetGlobalRam: (Int) -> Unit,
    onOpenCustomControls: () -> Unit,
    onOpenGamepadMapper: () -> Unit,
    onSetReducedMotion: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    onShareLogs: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnginePreferences: () -> Unit,
    onOpenHudCustomization: () -> Unit = {},
    onOpenLiveLyrics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var offlineUsername by remember { mutableStateOf("") }
    val totalRamMb = (state.deviceCapabilities?.totalRamMb?.toInt() ?: 4096).coerceAtLeast(1024)
    val maxSliderRam = (totalRamMb - 512).coerceIn(512, 12288)
    var sliderRam by remember(state.ramAllocationMb, maxSliderRam) {
        mutableFloatStateOf(state.ramAllocationMb.coerceIn(256, maxSliderRam).toFloat())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSectionHeader(
                title = "Settings & Engine",
                subtitle = "Runtimes, accounts, performance, and diagnostics"
            ) {
                AetherOutlinedButton(
                    text = "Refresh",
                    icon = AetherIcons.Refresh,
                    onClick = onRefreshEnvironment
                )
            }
        }

        // 1. Java Runtimes Section
        item {
            AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                Text(
                    text = "Java Runtimes (${state.deviceCapabilities?.cpuAbiPrimary ?: "Device ABI"})",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnBackgroundDark
                )
                Text(
                    text = "Validates directory integrity, libjvm.so presence, CPU ABI match, and Java version requirements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                if (state.isDownloadingRuntimeJava != null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AetherColors.AccentCyan
                    )
                    Text(
                        text = "Downloading & unpacking External-${state.isDownloadingRuntimeJava}...",
                        style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                    )
                }

                if (state.runtimeInspections.isEmpty()) {
                    Text(
                        text = "No Java runtimes installed in ${Tools.MULTIRT_HOME ?: "runtimes/"}. Download one below to launch Minecraft.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.TierBlockedFg
                    )
                } else {
                    state.runtimeInspections.forEach { rt ->
                        val isValid = rt.status == MultiRTUtils.RuntimeValidationStatus.VALID
                        val badgeColor = if (isValid) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg
                        Surface(
                            onClick = { onOpenRuntimeDetail(rt.name) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = AetherColors.SurfaceDark,
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${rt.name} · Java ${rt.javaVersion} (${rt.arch})",
                                        style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark)
                                    )
                                    Text(
                                        text = rt.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = badgeColor
                                    )
                                }
                                AetherOutlinedButton(
                                    text = "Remove",
                                    onClick = { onDeleteRuntime(rt.name) }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Download OpenJDK Runtimes",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
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
                    AetherOutlinedButton(
                        text = "Import .tar.xz",
                        onClick = onImportRuntimeFile,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Runtime Manager",
                        onClick = onOpenRuntimeManagement,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. Accounts & Vault Section
        item {
            AetherSurfaceCard {
                Text(
                    text = "Accounts & Authentication",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "Microsoft Login",
                        icon = AetherIcons.Account,
                        onClick = onOpenMicrosoftLogin,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (state.accounts.isEmpty()) {
                    Text(
                        text = "No accounts added yet. Sign in with Microsoft or create a local offline profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                } else {
                    state.accounts.forEach { acct ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = AetherColors.SurfaceElevatedDark,
                            border = BorderStroke(
                                1.dp,
                                if (acct.isSelected) AetherColors.AccentCyan.copy(alpha = 0.5f) else AetherColors.OutlineVariantDark
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                            ) {
                                Text(
                                    text = if (acct.isSelected) "${acct.username} (Active)" else acct.username,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AetherColors.OnSurfaceDark
                                )
                                Text(
                                    text = "${if (acct.isMicrosoft) "Microsoft" else "Offline"} · Vault=${if (acct.hasVaultBackup) "Backed up" else "Local"}",
                                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                                    if (!acct.isSelected) {
                                        AetherTonalButton(
                                            text = "Select",
                                            onClick = { onSelectAccount(acct.username) }
                                        )
                                    }
                                    if (acct.isMicrosoft) {
                                        AetherOutlinedButton(
                                            text = "Encrypt Vault",
                                            onClick = { onMirrorAccountVault(acct.username) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = offlineUsername,
                        onValueChange = { offlineUsername = it },
                        label = { Text("Offline username (3–16 chars)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "Add",
                        onClick = {
                            onCreateOfflineAccount(offlineUsername)
                            offlineUsername = ""
                        }
                    )
                }
            }
        }

        // 3. Memory, Renderers & Controls
        item {
            AetherSurfaceCard {
                Text(
                    text = "Memory, Renderers & Controls",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Global Default JVM Memory: ${sliderRam.roundToInt()} MB (Device total: $totalRamMb MB)",
                    style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                )
                Slider(
                    value = sliderRam,
                    onValueChange = { sliderRam = it },
                    valueRange = 256f..maxSliderRam.toFloat(),
                    onValueChangeFinished = { onSetGlobalRam(sliderRam.roundToInt()) }
                )

                Text(
                    text = "Available Renderers: ${state.rendererProfiles.joinToString { it.displayName }.ifEmpty { "Default" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                AetherPrimaryButton(
                    text = "STAR Touch HUD Studio",
                    icon = AetherIcons.Controls,
                    onClick = onOpenHudCustomization,
                    modifier = Modifier.fillMaxWidth()
                )
                val context = LocalContext.current
                var liveLyricsEnabled by remember {
                    mutableStateOf(LiveLyricsConfigStore.load(context).featureEnabled)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = AetherColors.SurfaceDark,
                    border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "STAR Live Lyrics (Dynamic Island)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AetherColors.OnSurfaceDark
                                )
                                Text(
                                    text = if (liveLyricsEnabled) "Active during gameplay" else "Disabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (liveLyricsEnabled) AetherColors.AccentCyan else AetherColors.OnSurfaceVariantDark
                                )
                            }
                            Switch(
                                checked = liveLyricsEnabled,
                                onCheckedChange = { enabled ->
                                    liveLyricsEnabled = enabled
                                    LiveLyricsConfigStore.update(context) { it.copy(featureEnabled = enabled) }
                                }
                            )
                        }
                        AetherTonalButton(
                            text = "Configure Live Lyrics & Music Player",
                            onClick = onOpenLiveLyrics,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Legacy Controls",
                        icon = AetherIcons.Controls,
                        onClick = onOpenCustomControls,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Gamepad Map",
                        onClick = onOpenGamepadMapper,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Compatibility & Hardware Matrix
        item {
            AetherSurfaceCard {
                Text(
                    text = "Hardware & Compatibility",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                val caps = state.deviceCapabilities
                if (caps != null) {
                    Text(
                        text = "ABI: ${caps.cpuAbiPrimary} (${if (caps.is64Bit) "64-bit" else "32-bit"}) · GPU: ${caps.gpuRenderer} · GLES ${caps.glesMajorVersion} · Vulkan: ${caps.vulkanVersion ?: "No"}",
                        style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                    )
                }
                val report = state.selectedCompatibilityReport
                if (report != null) {
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
                Text(
                    text = "Native libs missing: ${state.missingNativeLibraries.ifEmpty { listOf("None") }.joinToString()}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Export Diagnostics",
                        onClick = onExportDiagnostics,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Share Latest Log",
                        onClick = onShareLogs,
                        modifier = Modifier.weight(1f)
                    )
                }
                state.lastExportedReportPath?.let { path ->
                    Text(
                        text = "Saved report: $path",
                        style = AetherMonoStyle.copy(color = AetherColors.TierVerifiedFg)
                    )
                }
            }
        }

        // 5. Appearance, Storage & Upstream Engine Preferences
        item {
            val context = LocalContext.current
            var perfSpec by remember {
                mutableStateOf(com.star.launcher.compat.UiPerformanceProfileManager.ensureInitialized(context))
            }
            var devOverlayEnabled by remember {
                mutableStateOf(com.star.launcher.diagnostics.PerformanceTelemetryTracker.enabledFlow.value)
            }
            AetherSurfaceCard {
                Text(
                    text = "Appearance, Storage & Engine Preferences",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Adaptive UI Performance Mode: ${perfSpec.mode.displayName} (Resolved: ${perfSpec.profile.label})",
                    style = MaterialTheme.typography.titleSmall,
                    color = AetherColors.AccentCyan
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    com.star.launcher.compat.UiPerformanceProfileMode.values().forEach { modeOption ->
                        val selected = perfSpec.mode == modeOption
                        AetherTonalButton(
                            text = modeOption.name,
                            onClick = {
                                perfSpec = com.star.launcher.compat.UiPerformanceProfileManager.setMode(context, modeOption)
                            },
                            modifier = Modifier.weight(1f),
                            containerColor = if (selected) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark,
                            contentColor = if (selected) AetherColors.TierVerifiedFg else AetherColors.OnSurfaceVariantDark
                        )
                    }
                }
                if (com.star.launcher.diagnostics.PerformanceTelemetryTracker.isAvailableInBuild) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Developer Performance Monitor (Debug Only)",
                                style = MaterialTheme.typography.titleMedium,
                                color = AetherColors.OnSurfaceDark
                            )
                            Text(
                                text = "Shows live FPS, P95 frame time, heap/native RAM, GC count, startup & nav latency",
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherColors.OnSurfaceVariantDark
                            )
                        }
                        Switch(
                            checked = devOverlayEnabled,
                            onCheckedChange = { enabled ->
                                devOverlayEnabled = enabled
                                com.star.launcher.diagnostics.PerformanceTelemetryTracker.setOverlayEnabled(context, enabled)
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reduced Motion",
                            style = MaterialTheme.typography.titleMedium,
                            color = AetherColors.OnSurfaceDark
                        )
                        Text(
                            text = "Disables decorative star-trail motion and stage transitions",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                    Switch(
                        checked = state.reducedMotionEnabled,
                        onCheckedChange = onSetReducedMotion
                    )
                }
                Text(
                    text = "Game Home: ${Tools.DIR_GAME_HOME ?: "uninitialized"}\nRuntimes: ${Tools.MULTIRT_HOME ?: "uninitialized"}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
                AetherOutlinedButton(
                    text = "Open Engine Preferences",
                    onClick = onOpenEnginePreferences,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}
