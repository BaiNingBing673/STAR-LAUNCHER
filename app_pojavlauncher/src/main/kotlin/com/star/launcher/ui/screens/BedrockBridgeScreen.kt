package com.star.launcher.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.star.launcher.bridge.BridgeController
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.BridgeCompatibilityReport
import com.star.launcher.bridge.model.BridgeConnectionStatus
import com.star.launcher.bridge.model.BridgeLifecycleState
import com.star.launcher.bridge.model.BridgeMode
import com.star.launcher.bridge.model.BridgeServerProfile
import com.star.launcher.bridge.model.BridgeStatusSnapshot
import com.star.launcher.bridge.model.BridgeTransportType
import com.star.launcher.bridge.model.CompatibilityItemStatus
import com.star.launcher.bridge.model.ExternalServerSoftware
import com.star.launcher.bridge.model.GeyserAvailability
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.runtime.BridgeUpdateManager
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.dashboard.RuntimeInspectionItem
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.liquid.StarLiquidGlassSurface
import com.star.launcher.ui.theme.studio.StarComponentTarget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class BridgeDashboardSection(val title: String) {
    OVERVIEW("Overview"),
    LOCAL_HOSTING("Local Hosting"),
    EXTERNAL_SERVERS("External Servers"),
    AUTHENTICATION("Authentication"),
    NETWORK("Network"),
    CONSOLE("Console"),
    ADVANCED("Advanced")
}

@Composable
fun BedrockBridgeScreen(
    bridgeController: BridgeController,
    availableRuntimes: List<RuntimeInspectionItem>,
    onBack: () -> Unit,
    onOpenRuntimeManagement: () -> Unit,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by bridgeController.status.collectAsStateWithLifecycle()
    val profiles by bridgeController.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val logs by bridgeController.logs.collectAsStateWithLifecycle()
    val compatReport by bridgeController.compatibilityReport.collectAsStateWithLifecycle()
    val latestRelease by bridgeController.latestGeyserRelease.collectAsStateWithLifecycle()
    val downloadProgress by bridgeController.downloadProgress.collectAsStateWithLifecycle()

    var activeSection by rememberSaveable { mutableStateOf(BridgeDashboardSection.OVERVIEW) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<BridgeServerProfile?>(null) }
    var showImportJsonDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    val keyFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bridgeController.importFloodgateKey(bytes)) {
                onStatusMessage("Imported Floodgate key.pem securely")
            } else {
                onStatusMessage("Failed importing Floodgate key.pem")
            }
        }
    }

    LaunchedEffect(bridgeController) {
        bridgeController.initialize(scope)
    }

    if (showProfileDialog) {
        ExternalServerProfileDialog(
            initial = editingProfile,
            onDismiss = {
                showProfileDialog = false
                editingProfile = null
            },
            onSave = { name, desc, javaAddr, javaPort, bedrockAddr, bedrockPort, software, auth ->
                scope.launch {
                    val result = if (editingProfile == null) {
                        bridgeController.addExternalProfile(
                            name = name,
                            description = desc,
                            javaAddress = javaAddr,
                            javaPort = javaPort,
                            bedrockAddress = bedrockAddr,
                            bedrockPort = bedrockPort,
                            software = software,
                            authMode = auth
                        )
                    } else {
                        bridgeController.updateExternalProfile(
                            editingProfile!!.copy(
                                name = name,
                                description = desc,
                                javaAddress = javaAddr,
                                javaPort = javaPort,
                                bedrockAddress = bedrockAddr,
                                bedrockPort = bedrockPort,
                                serverSoftware = software,
                                authMode = auth
                            )
                        )
                    }
                    result.onSuccess {
                        onStatusMessage("Saved server profile '${it.name}'")
                        showProfileDialog = false
                        editingProfile = null
                    }.onFailure { err ->
                        onStatusMessage(err.message ?: "Invalid server profile")
                    }
                }
            }
        )
    }

    if (showImportJsonDialog) {
        ImportProfilesJsonDialog(
            onDismiss = { showImportJsonDialog = false },
            onImport = { raw ->
                scope.launch {
                    bridgeController.importProfilesJson(raw)
                        .onSuccess { count ->
                            onStatusMessage("Imported $count server profile(s)")
                            showImportJsonDialog = false
                        }
                        .onFailure { err ->
                            onStatusMessage("Import failed: ${err.message}")
                        }
                }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("Upstream Licenses & Attribution") },
            text = {
                Text(
                    text = BridgeUpdateManager.UPSTREAM_ATTRIBUTION_TEXT,
                    style = AetherMonoStyle
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    val starTheme = LocalStarTheme.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AetherSpacing.Md, vertical = AetherSpacing.Sm)
    ) {
        AetherSubscreenHeader(
            title = "Bedrock Bridge",
            subtitle = "STAR Settings → Multiplayer → Bedrock Bridge (GeyserMC)",
            onBack = onBack
        )

        Spacer(modifier = Modifier.height(AetherSpacing.Sm))

        // Segmented section bar
        StarLiquidGlassSurface(
            componentTarget = StarComponentTarget.SETTINGS_PANELS,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BridgeDashboardSection.values().forEach { section ->
                    val selected = activeSection == section
                    if (selected) {
                        StarLiquidGlassSurface(
                            componentTarget = StarComponentTarget.BUTTONS,
                            shape = RoundedCornerShape(10.dp),
                            containerColorOverride = starTheme.primaryAccent.copy(alpha = 0.28f),
                            borderColorOverride = starTheme.secondaryAccent.copy(alpha = 0.85f),
                            onClick = { activeSection = section }
                        ) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = starTheme.textPrimary,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { activeSection = section }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = starTheme.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (downloadProgress != null) {
            val (dl, total) = downloadProgress!!
            Spacer(modifier = Modifier.height(AetherSpacing.Xs))
            if (total > 0L) {
                LinearProgressIndicator(
                    progress = { (dl.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AetherColors.AccentCyan
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = AetherColors.AccentCyan
                )
            }
            Text(
                text = "Downloading verified artifact: ${dl / 1024} KB" + if (total > 0L) " / ${total / 1024} KB" else "",
                style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
            )
        }

        Spacer(modifier = Modifier.height(AetherSpacing.Sm))

        when (activeSection) {
            BridgeDashboardSection.OVERVIEW -> OverviewSection(
                status = status,
                compatReport = compatReport,
                latestReleaseText = latestRelease?.let { "${it.version} (#${it.buildNumber})" },
                onRefresh = { scope.launch { bridgeController.refreshEnvironmentSnapshot(probeNetworkLatency = true) } },
                onCheckUpdates = {
                    scope.launch {
                        bridgeController.checkForGeyserUpdates()
                            .onSuccess { onStatusMessage("Latest GeyserMC: ${it.version} (#${it.buildNumber})") }
                            .onFailure { onStatusMessage("Update check failed: ${it.message}") }
                    }
                },
                onInstallOrUpdateGeyser = {
                    scope.launch {
                        bridgeController.downloadOrUpdateGeyser()
                            .onSuccess(onStatusMessage)
                            .onFailure { onStatusMessage(it.message ?: "Geyser install failed") }
                    }
                },
                onStartBridge = {
                    scope.launch {
                        bridgeController.startLocalHostingBridge()
                            .onFailure { onStatusMessage(it.message ?: "Failed starting bridge") }
                    }
                },
                onStopBridge = {
                    scope.launch { bridgeController.stopAllBridgeProcesses(graceful = true) }
                },
                onSelectMode = { mode -> scope.launch { bridgeController.setMode(mode) } },
                onCopyError = { reportText ->
                    copyToClipboard(context, "STAR Bridge Error", reportText)
                    onStatusMessage("Copied error diagnostics to clipboard")
                },
                onDismissError = { bridgeController.dismissLastError() }
            )

            BridgeDashboardSection.LOCAL_HOSTING -> LocalHostingSection(
                status = status,
                onUpdateLocalConfig = { transform -> scope.launch { bridgeController.updateLocalServerConfig(transform) } },
                onUpdateGeyserConfig = { transform -> scope.launch { bridgeController.updateGeyserConfig(transform) } },
                onInstallServer = {
                    scope.launch {
                        bridgeController.downloadOrUpdateLocalServer()
                            .onSuccess(onStatusMessage)
                            .onFailure { onStatusMessage(it.message ?: "Server download failed") }
                    }
                },
                onInstallGeyser = {
                    scope.launch {
                        bridgeController.downloadOrUpdateGeyser()
                            .onSuccess(onStatusMessage)
                            .onFailure { onStatusMessage(it.message ?: "Geyser download failed") }
                    }
                },
                onStartServer = {
                    scope.launch {
                        bridgeController.startLocalJavaServer()
                            .onFailure { onStatusMessage(it.message ?: "Server startup failed") }
                    }
                },
                onStopServer = { scope.launch { bridgeController.stopLocalJavaServer(graceful = true) } },
                onStartGeyser = {
                    scope.launch {
                        bridgeController.startGeyserStandalone()
                            .onFailure { onStatusMessage(it.message ?: "Geyser startup failed") }
                    }
                },
                onStopGeyser = { scope.launch { bridgeController.stopGeyserStandalone(graceful = true) } },
                onStartBoth = {
                    scope.launch {
                        bridgeController.startLocalHostingBridge()
                            .onFailure { onStatusMessage(it.message ?: "Bridge startup failed") }
                    }
                },
                onStopBoth = { scope.launch { bridgeController.stopAllBridgeProcesses(graceful = true) } },
                onRollbackGeyser = {
                    scope.launch {
                        bridgeController.rollbackGeyser()
                            .onSuccess(onStatusMessage)
                            .onFailure { onStatusMessage(it.message ?: "Rollback unavailable") }
                    }
                },
                onRollbackServer = {
                    scope.launch {
                        bridgeController.rollbackLocalServer()
                            .onSuccess(onStatusMessage)
                            .onFailure { onStatusMessage(it.message ?: "Rollback unavailable") }
                    }
                }
            )

            BridgeDashboardSection.EXTERNAL_SERVERS -> ExternalServersSection(
                profiles = profiles,
                compatReport = compatReport,
                onAddProfile = {
                    editingProfile = null
                    showProfileDialog = true
                },
                onEditProfile = { profile ->
                    editingProfile = profile
                    showProfileDialog = true
                },
                onProbeProfile = { profile ->
                    scope.launch {
                        bridgeController.probeExternalProfile(profile.profileId)
                            .onSuccess { summary -> onStatusMessage(summary.guidanceMessage) }
                            .onFailure { err -> onStatusMessage(err.message ?: "Probe failed") }
                    }
                },
                onToggleFavorite = { profile ->
                    scope.launch { bridgeController.toggleFavoriteProfile(profile.profileId) }
                },
                onDuplicateProfile = { profile ->
                    scope.launch { bridgeController.duplicateProfile(profile.profileId) }
                },
                onDeleteProfile = { profile ->
                    scope.launch {
                        bridgeController.deleteExternalProfile(profile.profileId)
                        onStatusMessage("Removed profile '${profile.name}' (server files untouched)")
                    }
                },
                onImportJson = { showImportJsonDialog = true },
                onExportJson = {
                    scope.launch {
                        val json = bridgeController.exportProfilesJson()
                        copyToClipboard(context, "STAR Bridge Profiles", json)
                        onStatusMessage("Copied server profiles JSON to clipboard")
                    }
                },
                onCopyEndpoint = { label, value ->
                    copyToClipboard(context, label, value)
                    onStatusMessage("Copied $label: $value")
                }
            )

            BridgeDashboardSection.AUTHENTICATION -> AuthenticationSection(
                status = status,
                onSelectAuthMode = { mode ->
                    scope.launch {
                        bridgeController.updateGeyserConfig { it.copy(authType = mode) }
                    }
                },
                onToggleOnlineMode = { enabled ->
                    scope.launch {
                        bridgeController.updateLocalServerConfig { it.copy(onlineMode = enabled) }
                    }
                },
                onInstallFloodgate = {
                    scope.launch {
                        bridgeController.downloadOrUpdateFloodgate()
                            .onSuccess(onStatusMessage)
                            .onFailure { onStatusMessage(it.message ?: "Floodgate install failed") }
                    }
                },
                onSyncKeys = {
                    bridgeController.syncOrGenerateFloodgateKeys()
                    onStatusMessage("Synchronized Floodgate key.pem")
                },
                onImportKey = { keyFilePicker.launch(arrayOf("*/*")) },
                onRegenerateKeys = {
                    bridgeController.regenerateFloodgateKeys()
                    onStatusMessage("Regenerated Floodgate key.pem")
                }
            )

            BridgeDashboardSection.NETWORK -> NetworkSection(
                status = status,
                onRefreshNetwork = {
                    scope.launch { bridgeController.refreshEnvironmentSnapshot(probeNetworkLatency = true) }
                },
                onSelectTransport = { transport ->
                    scope.launch {
                        bridgeController.updateGeyserConfig { it.copy(transport = transport) }
                    }
                },
                onCopyAddress = { label, addr ->
                    copyToClipboard(context, label, addr)
                    onStatusMessage("Copied $label: $addr")
                }
            )

            BridgeDashboardSection.CONSOLE -> ConsoleSection(
                logs = logs,
                serverAlive = status.health.serverProcessAlive,
                geyserAlive = status.health.geyserProcessAlive,
                onSendServerCommand = bridgeController::sendServerCommand,
                onSendGeyserCommand = bridgeController::sendGeyserCommand,
                onCopyDiagnostics = {
                    val report = bridgeController.buildDiagnosticReport()
                    copyToClipboard(context, "STAR Bridge Diagnostics", report)
                    onStatusMessage("Copied diagnostic bundle to clipboard")
                },
                onExportDiagnostics = {
                    val file = bridgeController.exportDiagnosticBundle()
                    onStatusMessage("Exported diagnostics: ${file.absolutePath}")
                },
                onClearLogs = bridgeController::clearLogs
            )

            BridgeDashboardSection.ADVANCED -> AdvancedSection(
                status = status,
                availableRuntimes = availableRuntimes,
                onOpenRuntimeManagement = onOpenRuntimeManagement,
                onUpdateGeyserConfig = { transform -> scope.launch { bridgeController.updateGeyserConfig(transform) } },
                onUpdateLocalConfig = { transform -> scope.launch { bridgeController.updateLocalServerConfig(transform) } },
                onUpdateAdvancedConfig = { transform -> scope.launch { bridgeController.updateAdvancedConfig(transform) } },
                onShowLicenses = { showLicensesDialog = true }
            )
        }
    }
}

@Composable
private fun OverviewSection(
    status: BridgeStatusSnapshot,
    compatReport: BridgeCompatibilityReport?,
    latestReleaseText: String?,
    onRefresh: () -> Unit,
    onCheckUpdates: () -> Unit,
    onInstallOrUpdateGeyser: () -> Unit,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onSelectMode: (BridgeMode) -> Unit,
    onCopyError: (String) -> Unit,
    onDismissError: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            val stateColor = when (status.lifecycleState) {
                BridgeLifecycleState.RUNNING -> AetherColors.TierVerifiedFg
                BridgeLifecycleState.ERROR -> AetherColors.TierBlockedFg
                BridgeLifecycleState.DEGRADED, BridgeLifecycleState.STARTING_SERVER, BridgeLifecycleState.STARTING_GEYSER -> AetherColors.TierTestedFg
                else -> AetherColors.AccentCyan
            }
            AetherAccentCard(
                accentColor = stateColor,
                componentTarget = StarComponentTarget.SETTINGS_PANELS
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bridge Status: ${status.lifecycleState.name}",
                            style = MaterialTheme.typography.titleLarge,
                            color = stateColor
                        )
                        Text(
                            text = status.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceDark
                        )
                    }
                    AetherOutlinedButton(text = "Refresh", onClick = onRefresh)
                }

                // Mode Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    BridgeMode.values().forEach { mode ->
                        val selected = status.mode == mode
                        AetherTonalButton(
                            text = if (mode == BridgeMode.LOCAL_HOST) "Mode A: Local Host" else "Mode B: External",
                            onClick = { onSelectMode(mode) },
                            modifier = Modifier.weight(1f),
                            containerColor = if (selected) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark,
                            contentColor = if (selected) AetherColors.TierVerifiedFg else AetherColors.OnSurfaceVariantDark
                        )
                    }
                }

                // Telemetry Summary
                val lanIp = status.networkEndpoints.primaryLanIpv4 ?: "127.0.0.1"
                Text(
                    text = "Geyser: ${status.geyserArtifact?.let { "${it.version} (#${it.buildNumber})" } ?: "Not Installed"}" +
                        (latestReleaseText?.let { " · Latest: $it" } ?: "") +
                        "\nServer JAR: ${status.localServerArtifact?.let { "${it.projectId} ${it.version} (#${it.buildNumber})" } ?: "Not Installed (${status.localServerConfig.software.displayName} ${status.localServerConfig.minecraftVersion})"}" +
                        "\nJava Runtime: ${status.runtimeValidation?.summary ?: "Unvalidated"} · EULA: ${if (status.localServerConfig.eulaAccepted) "Accepted" else "Required"}" +
                        "\nJava Server: ${if (status.health.serverProcessAlive) "Running (PID ${status.health.serverPid}, ${status.health.serverRssMb} MB)" else "Stopped"}" +
                        "\nGeyser Bridge: ${if (status.health.geyserProcessAlive) "Running (PID ${status.health.geyserPid}, ${status.health.geyserRssMb} MB)" else "Stopped"}" +
                        "\nPlayers Connected: ${status.health.totalPlayers} / ${status.health.maxPlayers}" +
                        "\nJava Endpoint: 127.0.0.1:${status.localServerConfig.javaPort} (LAN $lanIp:${status.localServerConfig.javaPort})" +
                        "\nBedrock Endpoint: $lanIp:${status.geyserConfig.bedrockPort} (${status.geyserConfig.transport.displayName})",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    val anyAlive = status.health.serverProcessAlive || status.health.geyserProcessAlive
                    AetherPrimaryButton(
                        text = if (anyAlive) "Stop Bridge" else "Start Local Bridge",
                        onClick = if (anyAlive) onStopBridge else onStartBridge,
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = if (status.geyserArtifact == null) "Install Geyser" else "Update Geyser",
                        onClick = onInstallOrUpdateGeyser,
                        enabled = !status.health.geyserProcessAlive,
                        modifier = Modifier.weight(1f)
                    )
                }

                AetherOutlinedButton(
                    text = "Check Official GeyserMC Updates",
                    onClick = onCheckUpdates,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        status.lastError?.let { err ->
            item {
                AetherAccentCard(
                    accentColor = AetherColors.TierBlockedFg,
                    componentTarget = StarComponentTarget.SETTINGS_PANELS
                ) {
                    Text(
                        text = "${err.code.name}: ${err.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.TierBlockedFg
                    )
                    Text(
                        text = err.technicalExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceDark
                    )
                    Text(
                        text = "Possible Cause: ${err.possibleCause}\nResolution: ${err.suggestedResolution}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        AetherTonalButton(
                            text = "Copy Error Report",
                            onClick = { onCopyError(err.formatReport()) },
                            modifier = Modifier.weight(1f)
                        )
                        AetherOutlinedButton(
                            text = "Dismiss",
                            onClick = onDismissError,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (compatReport != null) {
            item {
                CompatibilityReportCard(compatReport = compatReport)
            }
        }
    }
}

@Composable
private fun CompatibilityReportCard(compatReport: BridgeCompatibilityReport) {
    AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
        Text(
            text = "Compatibility Report: ${compatReport.summaryHeadline}",
            style = MaterialTheme.typography.titleMedium,
            color = if (compatReport.overallCompatible) AetherColors.TierVerifiedFg else AetherColors.TierTestedFg
        )
        compatReport.items.forEach { item ->
            val color = when (item.status) {
                CompatibilityItemStatus.COMPATIBLE -> AetherColors.TierVerifiedFg
                CompatibilityItemStatus.WARNING -> AetherColors.TierTestedFg
                CompatibilityItemStatus.INCOMPATIBLE -> AetherColors.TierBlockedFg
                CompatibilityItemStatus.UNVERIFIED -> AetherColors.OnSurfaceVariantDark
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${item.component}: ${item.status.name} — ${item.summary}",
                    style = AetherMonoStyle.copy(color = color)
                )
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
        }
        compatReport.serverSideGeyserRequiredNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.TierBlockedFg
            )
        }
    }
}

@Composable
private fun LocalHostingSection(
    status: BridgeStatusSnapshot,
    onUpdateLocalConfig: ((LocalServerConfig) -> LocalServerConfig) -> Unit,
    onUpdateGeyserConfig: ((GeyserConfig) -> GeyserConfig) -> Unit,
    onInstallServer: () -> Unit,
    onInstallGeyser: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onStartGeyser: () -> Unit,
    onStopGeyser: () -> Unit,
    onStartBoth: () -> Unit,
    onStopBoth: () -> Unit,
    onRollbackGeyser: () -> Unit,
    onRollbackServer: () -> Unit
) {
    val srv = status.localServerConfig
    val geyser = status.geyserConfig
    var mcVersionText by remember(srv.minecraftVersion) { mutableStateOf(srv.minecraftVersion) }
    var javaPortText by remember(srv.javaPort) { mutableStateOf(srv.javaPort.toString()) }
    var bedrockPortText by remember(geyser.bedrockPort) { mutableStateOf(geyser.bedrockPort.toString()) }
    var worldNameText by remember(srv.worldName) { mutableStateOf(srv.worldName) }
    var motdText by remember(srv.motd) { mutableStateOf(srv.motd) }
    var maxPlayersText by remember(srv.maxPlayers) { mutableStateOf(srv.maxPlayers.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Local Cross-Play Hosting",
                            style = MaterialTheme.typography.titleMedium,
                            color = AetherColors.OnSurfaceDark
                        )
                        Text(
                            text = "Hosts a Java server + Geyser Standalone in independent background Android services.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                    Switch(
                        checked = srv.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateLocalConfig { it.copy(enabled = enabled) }
                        }
                    )
                }

                Text(
                    text = "Java Server Software",
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherColors.AccentCyan
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    LocalServerSoftware.values().forEach { sw ->
                        val selected = srv.software == sw
                        AetherTonalButton(
                            text = sw.displayName,
                            onClick = { onUpdateLocalConfig { it.copy(software = sw) } },
                            modifier = Modifier.weight(1f),
                            containerColor = if (selected) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark,
                            contentColor = if (selected) AetherColors.TierVerifiedFg else AetherColors.OnSurfaceVariantDark
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    OutlinedTextField(
                        value = mcVersionText,
                        onValueChange = { mcVersionText = it },
                        label = { Text("Minecraft Version") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxPlayersText,
                        onValueChange = { maxPlayersText = it.filter(Char::isDigit) },
                        label = { Text("Max Players") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    OutlinedTextField(
                        value = javaPortText,
                        onValueChange = { javaPortText = it.filter(Char::isDigit) },
                        label = { Text("Java TCP Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bedrockPortText,
                        onValueChange = { bedrockPortText = it.filter(Char::isDigit) },
                        label = { Text("Bedrock Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = worldNameText,
                    onValueChange = { worldNameText = it },
                    label = { Text("World Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = motdText,
                    onValueChange = { motdText = it },
                    label = { Text("Server MOTD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                AetherTonalButton(
                    text = "Save & Apply Endpoint Settings",
                    onClick = {
                        val jPort = javaPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: srv.javaPort
                        val bPort = bedrockPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: geyser.bedrockPort
                        val maxP = maxPlayersText.toIntOrNull()?.coerceIn(1, 200) ?: srv.maxPlayers
                        onUpdateLocalConfig {
                            it.copy(
                                minecraftVersion = mcVersionText.trim().ifBlank { it.minecraftVersion },
                                javaPort = jPort,
                                worldName = worldNameText.trim().ifBlank { "world" },
                                motd = motdText.trim().ifBlank { it.motd },
                                maxPlayers = maxP
                            )
                        }
                        onUpdateGeyserConfig {
                            it.copy(
                                bedrockPort = bPort,
                                remoteAddress = "127.0.0.1",
                                remotePort = jPort
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accept Mojang Minecraft Server EULA",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (srv.eulaAccepted) AetherColors.TierVerifiedFg else AetherColors.TierTestedFg
                        )
                        Text(
                            text = "Required by Mojang (aka.ms/MinecraftEULA) before launching a local Java server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                    Switch(
                        checked = srv.eulaAccepted,
                        onCheckedChange = { accepted ->
                            onUpdateLocalConfig { it.copy(eulaAccepted = accepted) }
                        }
                    )
                }
            }
        }

        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Artifacts & Independent Process Control",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Server JAR: ${status.localServerArtifact?.let { "${it.projectId} ${it.version}" } ?: "Not installed"}\n" +
                        "Geyser JAR: ${status.geyserArtifact?.let { "${it.version} (#${it.buildNumber})" } ?: "Not installed"}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Download Server",
                        onClick = onInstallServer,
                        enabled = !status.health.serverProcessAlive,
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "Download Geyser",
                        onClick = onInstallGeyser,
                        enabled = !status.health.geyserProcessAlive,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = if (status.health.serverProcessAlive) "Stop Java Server" else "Start Java Server",
                        onClick = if (status.health.serverProcessAlive) onStopServer else onStartServer,
                        modifier = Modifier.weight(1f)
                    )
                    AetherPrimaryButton(
                        text = if (status.health.geyserProcessAlive) "Stop Geyser" else "Start Geyser",
                        onClick = if (status.health.geyserProcessAlive) onStopGeyser else onStartGeyser,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Start Both (Bridge)",
                        onClick = onStartBoth,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Stop Both",
                        onClick = onStopBoth,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (status.geyserArtifact?.previousVersion != null || status.localServerArtifact?.previousVersion != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        if (status.localServerArtifact?.previousVersion != null) {
                            AetherOutlinedButton(
                                text = "Rollback Server",
                                onClick = onRollbackServer,
                                enabled = !status.health.serverProcessAlive,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (status.geyserArtifact?.previousVersion != null) {
                            AetherOutlinedButton(
                                text = "Rollback Geyser",
                                onClick = onRollbackGeyser,
                                enabled = !status.health.geyserProcessAlive,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalServersSection(
    profiles: List<BridgeServerProfile>,
    compatReport: BridgeCompatibilityReport?,
    onAddProfile: () -> Unit,
    onEditProfile: (BridgeServerProfile) -> Unit,
    onProbeProfile: (BridgeServerProfile) -> Unit,
    onToggleFavorite: (BridgeServerProfile) -> Unit,
    onDuplicateProfile: (BridgeServerProfile) -> Unit,
    onDeleteProfile: (BridgeServerProfile) -> Unit,
    onImportJson: () -> Unit,
    onExportJson: () -> Unit,
    onCopyEndpoint: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "External Cross-Play Server Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Stores separate Java and Bedrock endpoints. Java players connect in Minecraft Java's native Multiplayer menu; Bedrock players connect via Bedrock Play → Servers. STAR never fakes Bedrock support on external servers that do not run Geyser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "Add Server",
                        onClick = onAddProfile,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Import",
                        onClick = onImportJson,
                        modifier = Modifier.weight(0.6f)
                    )
                    AetherOutlinedButton(
                        text = "Export",
                        onClick = onExportJson,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }
        }

        if (profiles.isEmpty()) {
            item {
                AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                    Text(
                        text = "No external server profiles added yet. Tap Add Server to configure a Geyser-enabled Java + Bedrock server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                }
            }
        } else {
            items(profiles, key = { it.profileId }) { profile ->
                val geyserColor = when (profile.geyserAvailability) {
                    GeyserAvailability.DETECTED_COMPATIBLE -> AetherColors.TierVerifiedFg
                    GeyserAvailability.NOT_DETECTED -> AetherColors.TierBlockedFg
                    GeyserAvailability.DETECTED_OUTDATED -> AetherColors.TierTestedFg
                    GeyserAvailability.UNVERIFIED -> AetherColors.OnSurfaceVariantDark
                }
                AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = (if (profile.isFavorite) "★ " else "") + profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = AetherColors.OnSurfaceDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (profile.description.isNotBlank()) {
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AetherColors.OnSurfaceVariantDark
                                )
                            }
                        }
                        AetherOutlinedButton(
                            text = if (profile.isFavorite) "Unstar" else "Star",
                            onClick = { onToggleFavorite(profile) }
                        )
                    }

                    Text(
                        text = "Java: ${profile.javaAddress}:${profile.javaPort}\n" +
                            "Bedrock: ${profile.bedrockAddress}:${profile.bedrockPort}\n" +
                            "Software: ${profile.serverSoftware.displayName} · Auth: ${profile.authMode.displayName}\n" +
                            "Status: ${profile.connectionStatus.name} · Geyser: ${profile.geyserAvailability.name}",
                        style = AetherMonoStyle.copy(color = geyserColor)
                    )
                    Text(
                        text = profile.versionCompatibilitySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        AetherTonalButton(
                            text = "Test & Diagnose",
                            onClick = { onProbeProfile(profile) },
                            modifier = Modifier.weight(1f)
                        )
                        AetherOutlinedButton(
                            text = "Copy Java",
                            onClick = { onCopyEndpoint("Java Endpoint", "${profile.javaAddress}:${profile.javaPort}") },
                            modifier = Modifier.weight(0.8f)
                        )
                        AetherOutlinedButton(
                            text = "Copy Bedrock",
                            onClick = { onCopyEndpoint("Bedrock Endpoint", "${profile.bedrockAddress}:${profile.bedrockPort}") },
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        AetherOutlinedButton(
                            text = "Edit",
                            onClick = { onEditProfile(profile) },
                            modifier = Modifier.weight(1f)
                        )
                        AetherOutlinedButton(
                            text = "Duplicate",
                            onClick = { onDuplicateProfile(profile) },
                            modifier = Modifier.weight(1f)
                        )
                        AetherOutlinedButton(
                            text = "Delete",
                            onClick = { onDeleteProfile(profile) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (compatReport != null) {
            item {
                CompatibilityReportCard(compatReport = compatReport)
            }
        }
    }
}

@Composable
private fun AuthenticationSection(
    status: BridgeStatusSnapshot,
    onSelectAuthMode: (BridgeAuthMode) -> Unit,
    onToggleOnlineMode: (Boolean) -> Unit,
    onInstallFloodgate: () -> Unit,
    onSyncKeys: () -> Unit,
    onImportKey: () -> Unit,
    onRegenerateKeys: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Cross-Platform Authentication",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "STAR preserves Microsoft/Mojang Java authentication and never silently disables online-mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local Java Server online-mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = AetherColors.OnSurfaceDark
                        )
                        Text(
                            text = "Keep enabled for legitimate Mojang/Microsoft and Floodgate verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                    Switch(
                        checked = status.localServerConfig.onlineMode,
                        onCheckedChange = onToggleOnlineMode
                    )
                }

                Text(
                    text = "Geyser auth-type",
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherColors.AccentCyan
                )
                BridgeAuthMode.values().forEach { mode ->
                    val selected = status.geyserConfig.authType == mode
                    Surface(
                        onClick = { onSelectAuthMode(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark,
                        border = BorderStroke(
                            1.dp,
                            if (selected) AetherColors.AccentCyan else AetherColors.OutlineVariantDark
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = mode.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) AetherColors.TierVerifiedFg else AetherColors.OnSurfaceDark
                            )
                            Text(
                                text = when (mode) {
                                    BridgeAuthMode.ONLINE -> "Bedrock players sign in with a Java Edition Microsoft account."
                                    BridgeAuthMode.FLOODGATE -> "Bedrock players authenticate via Xbox Live + Floodgate key.pem (PaperMC plugin required)."
                                    BridgeAuthMode.OFFLINE -> "Local development only; requires online-mode=false on backend."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherColors.OnSurfaceVariantDark
                            )
                        }
                    }
                }
            }
        }

        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Floodgate & Private Key Protection",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Floodgate Plugin: ${status.floodgateArtifact?.let { "${it.version} (#${it.buildNumber})" } ?: "Not installed"}\n" +
                        "key.pem Present: ${status.floodgateKeyPresent} · Keystore Vault Backup: ${status.floodgateKeyBackedUpInVault}",
                    style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                )
                Text(
                    text = "Private keys are stored owner-only with Keystore AES-GCM backup and are always redacted from logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "Install Floodgate",
                        onClick = onInstallFloodgate,
                        enabled = !status.health.serverProcessAlive,
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "Sync key.pem",
                        onClick = onSyncKeys,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherOutlinedButton(
                        text = "Import key.pem",
                        onClick = onImportKey,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Regenerate Key",
                        onClick = onRegenerateKeys,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkSection(
    status: BridgeStatusSnapshot,
    onRefreshNetwork: () -> Unit,
    onSelectTransport: (BridgeTransportType) -> Unit,
    onCopyAddress: (String, String) -> Unit
) {
    val net = status.networkEndpoints
    val lanDisplay = net.lanIpv4Addresses.joinToString().ifBlank { "No active Wi-Fi/LAN IPv4" }
    val ipv6Display = net.lanIpv6Addresses.joinToString().ifBlank { "None detected" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Network Addresses & Ports",
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    AetherOutlinedButton(text = "Probe Network", onClick = onRefreshNetwork)
                }

                Text(
                    text = "Localhost (Same Device):\n" +
                        "  Java: 127.0.0.1:${net.javaPort} (TCP)\n" +
                        "  Bedrock: 127.0.0.1:${net.bedrockPort} (${net.bedrockTransport.displayName})\n\n" +
                        "LAN IPv4 (Same Wi-Fi / Hotspot):\n" +
                        "  $lanDisplay\n" +
                        "LAN IPv6:\n" +
                        "  $ipv6Display\n\n" +
                        "Port Checks: Java TCP ${if (net.javaPortAvailable) "OK" else "CONFLICT"} · Bedrock ${if (net.bedrockPortAvailable) "OK" else "CONFLICT"}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark)
                )

                net.portConflictReason?.let { conflict ->
                    Text(
                        text = "Port Conflict: $conflict",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.TierBlockedFg
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Copy Java Localhost",
                        onClick = { onCopyAddress("Java Localhost", "127.0.0.1:${net.javaPort}") },
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "Copy Bedrock LAN",
                        onClick = {
                            val host = net.primaryLanIpv4 ?: "127.0.0.1"
                            onCopyAddress("Bedrock LAN", "$host:${net.bedrockPort}")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Native Connection Workflow (Same World)",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = "• Java Edition: Launch normally → Multiplayer → Add Server / Direct Connection → 127.0.0.1:${net.javaPort} (or LAN IP).\n" +
                        "• Bedrock Edition: Open Bedrock → Play → Servers → Add Server → Address: ${net.primaryLanIpv4 ?: "<LAN IP>"}, Port: ${net.bedrockPort}.\n" +
                        "• Public Internet: ${net.publicAddressGuidance}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                Text(
                    text = "Bedrock Transport",
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    BridgeTransportType.values().forEach { transport ->
                        val selected = status.geyserConfig.transport == transport
                        AetherTonalButton(
                            text = transport.displayName,
                            onClick = { onSelectTransport(transport) },
                            modifier = Modifier.weight(1f),
                            containerColor = if (selected) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark,
                            contentColor = if (selected) AetherColors.TierVerifiedFg else AetherColors.OnSurfaceVariantDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleSection(
    logs: List<com.star.launcher.bridge.model.BridgeLogEntry>,
    serverAlive: Boolean,
    geyserAlive: Boolean,
    onSendServerCommand: (String) -> Unit,
    onSendGeyserCommand: (String) -> Unit,
    onCopyDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearLogs: () -> Unit
) {
    var commandInput by remember { mutableStateOf("") }
    var targetGeyser by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
        ) {
            AetherTonalButton(
                text = "Copy Diagnostics",
                onClick = onCopyDiagnostics,
                modifier = Modifier.weight(1f)
            )
            AetherOutlinedButton(
                text = "Export File",
                onClick = onExportDiagnostics,
                modifier = Modifier.weight(0.8f)
            )
            AetherOutlinedButton(
                text = "Clear",
                onClick = onClearLogs,
                modifier = Modifier.weight(0.6f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AetherTonalButton(
                text = if (targetGeyser) "Target: Geyser" else "Target: Java Server",
                onClick = { targetGeyser = !targetGeyser }
            )
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                label = { Text(if (targetGeyser) "Geyser command" else "Server command") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            AetherPrimaryButton(
                text = "Send",
                enabled = commandInput.isNotBlank() && (if (targetGeyser) geyserAlive else serverAlive),
                onClick = {
                    val cmd = commandInput.trim()
                    if (targetGeyser) onSendGeyserCommand(cmd) else onSendServerCommand(cmd)
                    commandInput = ""
                }
            )
        }

        AetherSurfaceCard(
            componentTarget = StarComponentTarget.SETTINGS_PANELS,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "No bridge logs recorded yet. Start Local Hosting or test an External Server.",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.takeLast(200)) { entry ->
                        val color = when (entry.level) {
                            "ERROR" -> AetherColors.TierBlockedFg
                            "WARN" -> AetherColors.TierTestedFg
                            "DEBUG" -> AetherColors.OnSurfaceVariantDark
                            else -> AetherColors.OnSurfaceDark
                        }
                        Text(
                            text = "[${entry.level}][${entry.source}] ${entry.message}",
                            style = AetherMonoStyle.copy(color = color)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    status: BridgeStatusSnapshot,
    availableRuntimes: List<RuntimeInspectionItem>,
    onOpenRuntimeManagement: () -> Unit,
    onUpdateGeyserConfig: ((GeyserConfig) -> GeyserConfig) -> Unit,
    onUpdateLocalConfig: ((LocalServerConfig) -> LocalServerConfig) -> Unit,
    onUpdateAdvancedConfig: ((com.star.launcher.bridge.model.BridgeAdvancedConfig) -> com.star.launcher.bridge.model.BridgeAdvancedConfig) -> Unit,
    onShowLicenses: () -> Unit
) {
    val geyser = status.geyserConfig
    val srv = status.localServerConfig
    val adv = status.advancedConfig

    var geyserMemSlider by remember(geyser.memoryLimitMb) { mutableFloatStateOf(geyser.memoryLimitMb.toFloat()) }
    var serverMemSlider by remember(srv.memoryLimitMb) { mutableFloatStateOf(srv.memoryLimitMb.toFloat()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AetherSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Dedicated Bridge Runtime & Memory Limits",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Geyser Validation: ${status.runtimeValidation?.summary ?: "Pending"}\n" +
                        "Server Validation: ${status.serverRuntimeValidation?.summary ?: "Pending"}",
                    style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                )
                if (availableRuntimes.isNotEmpty()) {
                    Text(
                        text = "Installed Runtimes (tap to pin Geyser JRE 21+):",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        AetherTonalButton(
                            text = "Auto (Java 21+)",
                            onClick = { onUpdateGeyserConfig { it.copy(preferredRuntimeName = null) } },
                            containerColor = if (geyser.preferredRuntimeName == null) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark
                        )
                        availableRuntimes.filter { it.javaVersion >= 21 }.forEach { rt ->
                            val selected = geyser.preferredRuntimeName == rt.name
                            AetherTonalButton(
                                text = "${rt.name} (J${rt.javaVersion})",
                                onClick = { onUpdateGeyserConfig { it.copy(preferredRuntimeName = rt.name) } },
                                containerColor = if (selected) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark
                            )
                        }
                    }
                }
                AetherOutlinedButton(
                    text = "Open Java Runtime Manager",
                    onClick = onOpenRuntimeManagement,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Geyser Heap Limit: ${geyserMemSlider.roundToInt()} MB",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark)
                )
                Slider(
                    value = geyserMemSlider,
                    onValueChange = { geyserMemSlider = it },
                    valueRange = GeyserConfig.MIN_MEMORY_MB.toFloat()..GeyserConfig.MAX_MEMORY_MB.toFloat(),
                    onValueChangeFinished = {
                        onUpdateGeyserConfig { it.copy(memoryLimitMb = geyserMemSlider.roundToInt()) }
                    }
                )

                Text(
                    text = "Local Java Server Heap Limit: ${serverMemSlider.roundToInt()} MB",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark)
                )
                Slider(
                    value = serverMemSlider,
                    onValueChange = { serverMemSlider = it },
                    valueRange = LocalServerConfig.MIN_MEMORY_MB.toFloat()..LocalServerConfig.MAX_MEMORY_MB.toFloat(),
                    onValueChangeFinished = {
                        onUpdateLocalConfig { it.copy(memoryLimitMb = serverMemSlider.roundToInt()) }
                    }
                )
            }
        }

        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Lifecycle, Updates & Debug",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                SettingToggleRow(
                    title = "Auto-Check Geyser Updates",
                    subtitle = "Checks official GeyserMC Downloads API v2 metadata.",
                    checked = adv.autoCheckUpdates,
                    onCheckedChange = { checked -> onUpdateAdvancedConfig { it.copy(autoCheckUpdates = checked) } }
                )
                SettingToggleRow(
                    title = "Automatic Crash Recovery",
                    subtitle = "Restarts crashed bridge processes up to ${adv.maxCrashRestarts} times.",
                    checked = adv.autoCrashRecovery,
                    onCheckedChange = { checked -> onUpdateAdvancedConfig { it.copy(autoCrashRecovery = checked) } }
                )
                SettingToggleRow(
                    title = "Geyser Debug Mode",
                    subtitle = "Enables verbose packet/protocol diagnostics in Geyser.",
                    checked = adv.debugMode,
                    onCheckedChange = { checked -> onUpdateAdvancedConfig { it.copy(debugMode = checked) } }
                )
                SettingToggleRow(
                    title = "Prefer IPv6 Display",
                    subtitle = "Highlights IPv6 LAN interfaces when available.",
                    checked = adv.preferIpv6,
                    onCheckedChange = { checked -> onUpdateAdvancedConfig { it.copy(preferIpv6 = checked) } }
                )
                AetherOutlinedButton(
                    text = "View GeyserMC & Floodgate Licenses",
                    onClick = onShowLicenses,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = AetherColors.OnSurfaceDark)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceVariantDark)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ExternalServerProfileDialog(
    initial: BridgeServerProfile?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, String, Int, ExternalServerSoftware, BridgeAuthMode) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var javaAddr by remember { mutableStateOf(initial?.javaAddress ?: "") }
    var javaPort by remember { mutableStateOf((initial?.javaPort ?: 25565).toString()) }
    var bedrockAddr by remember { mutableStateOf(initial?.bedrockAddress ?: "") }
    var bedrockPort by remember { mutableStateOf((initial?.bedrockPort ?: 19132).toString()) }
    var software by remember { mutableStateOf(initial?.serverSoftware ?: ExternalServerSoftware.PAPER) }
    var authMode by remember { mutableStateOf(initial?.authMode ?: BridgeAuthMode.FLOODGATE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add External Server Profile" else "Edit Server Profile") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Server Name") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(value = javaAddr, onValueChange = { javaAddr = it }, label = { Text("Java Endpoint Host") }, singleLine = true)
                OutlinedTextField(
                    value = javaPort,
                    onValueChange = { javaPort = it.filter(Char::isDigit) },
                    label = { Text("Java Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = bedrockAddr,
                    onValueChange = { bedrockAddr = it },
                    label = { Text("Bedrock Endpoint Host (blank = same)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = bedrockPort,
                    onValueChange = { bedrockPort = it.filter(Char::isDigit) },
                    label = { Text("Bedrock Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ExternalServerSoftware.PAPER,
                        ExternalServerSoftware.FABRIC,
                        ExternalServerSoftware.VELOCITY,
                        ExternalServerSoftware.VANILLA
                    ).forEach { sw ->
                        AetherTonalButton(
                            text = sw.displayName,
                            onClick = { software = sw },
                            containerColor = if (software == sw) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BridgeAuthMode.values().forEach { mode ->
                        AetherTonalButton(
                            text = mode.name,
                            onClick = { authMode = mode },
                            containerColor = if (authMode == mode) AetherColors.TierVerifiedBg else AetherColors.SurfaceElevatedDark
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val resolvedBedrock = bedrockAddr.trim().ifBlank { javaAddr.trim() }
                    onSave(
                        name.trim(),
                        desc.trim(),
                        javaAddr.trim(),
                        javaPort.toIntOrNull() ?: 25565,
                        resolvedBedrock,
                        bedrockPort.toIntOrNull() ?: 19132,
                        software,
                        authMode
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportProfilesJsonDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var rawJson by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Server Profiles JSON") },
        text = {
            OutlinedTextField(
                value = rawJson,
                onValueChange = { rawJson = it },
                label = { Text("Paste exported profiles JSON") },
                minLines = 4,
                maxLines = 8
            )
        },
        confirmButton = {
            TextButton(onClick = { onImport(rawJson) }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
