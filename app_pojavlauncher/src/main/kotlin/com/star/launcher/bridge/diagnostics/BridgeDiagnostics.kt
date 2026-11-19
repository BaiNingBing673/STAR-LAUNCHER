package com.star.launcher.bridge.diagnostics

import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.BridgeCompatibilityReport
import com.star.launcher.bridge.model.BridgeDiagnosticError
import com.star.launcher.bridge.model.BridgeErrorCatalog
import com.star.launcher.bridge.model.BridgeErrorCode
import com.star.launcher.bridge.model.BridgeStatusSnapshot
import com.star.launcher.bridge.storage.BridgeStoragePaths
import java.io.File

/**
 * Produces structured diagnostic errors with actionable resolutions and copyable/exportable
 * redacted diagnostic reports for STAR Bedrock Bridge.
 */
class BridgeDiagnostics(
    private val paths: BridgeStoragePaths,
    private val logManager: BridgeLogManager
) {
    fun createError(
        code: BridgeErrorCode,
        technicalDetail: String,
        overrideResolution: String? = null
    ): BridgeDiagnosticError {
        val tail = logManager.recentMessages(15)
        return BridgeErrorCatalog.build(
            code = code,
            technicalDetail = technicalDetail,
            relevantLogs = tail,
            overrideResolution = overrideResolution
        )
    }

    fun buildFullReportText(
        status: BridgeStatusSnapshot,
        compatReport: BridgeCompatibilityReport?
    ): String {
        val header = buildString {
            append("=== STAR BEDROCK BRIDGE DIAGNOSTICS ===\n")
            append("Mode: ").append(status.mode.name).append('\n')
            append("Lifecycle State: ").append(status.lifecycleState.name).append('\n')
            append("Status Message: ").append(status.statusMessage).append('\n')
            append("Geyser Artifact: ").append(
                status.geyserArtifact?.let { "${it.version} (#${it.buildNumber})" } ?: "Not installed"
            ).append('\n')
            append("Geyser Java Runtime: ").append(status.runtimeValidation?.summary ?: "Unvalidated").append('\n')
            append("Local Server Software: ").append(status.localServerConfig.software.displayName)
                .append(" ").append(status.localServerConfig.minecraftVersion).append('\n')
            append("Local Server Runtime: ").append(status.serverRuntimeValidation?.summary ?: "Unvalidated").append('\n')
            append("Java Endpoint: 127.0.0.1:").append(status.localServerConfig.javaPort)
                .append(" (alive=").append(status.health.serverProcessAlive)
                .append(", reachable=").append(status.health.javaEndpointReachable).append(")\n")
            append("Bedrock Endpoint: ").append(status.geyserConfig.bedrockAddress)
                .append(':').append(status.geyserConfig.bedrockPort)
                .append(" [").append(status.geyserConfig.transport.displayName).append("]")
                .append(" (alive=").append(status.health.geyserProcessAlive)
                .append(", reachable=").append(status.health.bedrockEndpointReachable).append(")\n")
            append("LAN IPv4: ").append(status.networkEndpoints.lanIpv4Addresses.joinToString().ifBlank { "None" }).append('\n')
            append("LAN IPv6: ").append(status.networkEndpoints.lanIpv6Addresses.joinToString().ifBlank { "None" }).append('\n')
            append("Auth Type: ").append(status.geyserConfig.authType.displayName)
                .append(" | Floodgate Key Present=").append(status.floodgateKeyPresent)
                .append(" | Vault Backup=").append(status.floodgateKeyBackedUpInVault).append('\n')
            if (status.lastError != null) {
                append("\n--- Last Error ---\n").append(status.lastError.formatReport()).append('\n')
            }
            if (compatReport != null) {
                append("\n--- Compatibility Report ---\n").append(compatReport.toReadableText()).append('\n')
            }
        }
        return logManager.exportDiagnosticText(header)
    }

    fun exportDiagnosticBundle(
        status: BridgeStatusSnapshot,
        compatReport: BridgeCompatibilityReport?
    ): File {
        val text = buildFullReportText(status, compatReport)
        val outFile = File(paths.exportsDir(), "bridge-diagnostics-${System.currentTimeMillis()}.txt")
        outFile.parentFile?.mkdirs()
        outFile.writeText(text, Charsets.UTF_8)
        return outFile
    }
}
