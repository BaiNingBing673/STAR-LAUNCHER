package com.star.launcher.bridge.compat

import com.star.launcher.bridge.auth.BridgeAuthenticationManager
import com.star.launcher.bridge.model.BridgeCompatibilityReport
import com.star.launcher.bridge.model.BridgeMode
import com.star.launcher.bridge.model.BridgeServerProfile
import com.star.launcher.bridge.model.CompatibilityCheckItem
import com.star.launcher.bridge.model.CompatibilityItemStatus
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.InstalledArtifactMetadata
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.model.NetworkEndpointsSnapshot
import com.star.launcher.bridge.model.RuntimeCheckDetails
import com.star.launcher.bridge.network.BedrockPingResult
import com.star.launcher.bridge.network.JavaPingResult

/**
 * Modular compatibility evaluation engine for STAR Bedrock Bridge.
 * Uses runtime checks, active config, live protocol probes, and official Geyser metadata
 * instead of hardcoded permanent Minecraft version allowlists.
 */
class BridgeCompatibilityEngine {

    class RuntimeVersionChecker {
        fun check(runtimeDetails: RuntimeCheckDetails?): CompatibilityCheckItem {
            if (runtimeDetails == null) {
                return CompatibilityCheckItem(
                    component = "Java Runtime",
                    status = CompatibilityItemStatus.UNVERIFIED,
                    summary = "Not validated yet",
                    detail = "Run environment validation to inspect installed OpenJDK runtimes."
                )
            }
            return if (runtimeDetails.isCompatible) {
                CompatibilityCheckItem(
                    component = "Java Runtime",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = "Java ${runtimeDetails.selectedJavaVersion} (${runtimeDetails.runtimeArch ?: runtimeDetails.deviceArch})",
                    detail = runtimeDetails.summary
                )
            } else {
                CompatibilityCheckItem(
                    component = "Java Runtime",
                    status = CompatibilityItemStatus.INCOMPATIBLE,
                    summary = "Requires Java ${runtimeDetails.requiredJavaVersion}+",
                    detail = runtimeDetails.issues.joinToString("; ").ifBlank { runtimeDetails.summary }
                )
            }
        }
    }

    class GeyserVersionChecker {
        fun check(
            installedGeyser: InstalledArtifactMetadata?,
            latestBuildNumber: Int? = null
        ): CompatibilityCheckItem {
            if (installedGeyser == null) {
                return CompatibilityCheckItem(
                    component = "Geyser Runtime",
                    status = CompatibilityItemStatus.INCOMPATIBLE,
                    summary = "Not installed",
                    detail = "Download Geyser-Standalone.jar from official GeyserMC Downloads API v2."
                )
            }
            if (latestBuildNumber != null && installedGeyser.buildNumber > 0 && installedGeyser.buildNumber < latestBuildNumber) {
                return CompatibilityCheckItem(
                    component = "Geyser Runtime",
                    status = CompatibilityItemStatus.WARNING,
                    summary = "${installedGeyser.version} (#${installedGeyser.buildNumber}, update #$latestBuildNumber available)",
                    detail = "A newer verified Geyser build is available for better Bedrock protocol coverage."
                )
            }
            return CompatibilityCheckItem(
                component = "Geyser Runtime",
                status = CompatibilityItemStatus.COMPATIBLE,
                summary = "${installedGeyser.version} (#${installedGeyser.buildNumber})",
                detail = "Verified Geyser Standalone artifact ready."
            )
        }
    }

    class JavaVersionChecker {
        fun checkLocal(config: LocalServerConfig, javaPing: JavaPingResult?): CompatibilityCheckItem {
            return if (javaPing != null && javaPing.reachable) {
                CompatibilityCheckItem(
                    component = "Java Server",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = javaPing.versionName ?: config.minecraftVersion,
                    detail = "Local ${config.software.displayName} server responded on TCP ${config.javaPort} (${javaPing.latencyMs ?: 0}ms)."
                )
            } else {
                CompatibilityCheckItem(
                    component = "Java Server",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = "${config.software.displayName} ${config.minecraftVersion} (Port ${config.javaPort})",
                    detail = "Configured local Java server ${config.minecraftVersion} on TCP port ${config.javaPort}."
                )
            }
        }

        fun checkExternal(profile: BridgeServerProfile, javaPing: JavaPingResult?): CompatibilityCheckItem {
            if (javaPing == null) {
                return CompatibilityCheckItem(
                    component = "Java Server",
                    status = CompatibilityItemStatus.UNVERIFIED,
                    summary = "${profile.javaAddress}:${profile.javaPort}",
                    detail = "Run compatibility probe to test TCP Server List Ping."
                )
            }
            return if (javaPing.reachable) {
                CompatibilityCheckItem(
                    component = "Java Server",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = javaPing.versionName ?: "Reachable",
                    detail = "Responded on ${profile.javaAddress}:${profile.javaPort} (protocol ${javaPing.protocolVersion ?: "?"}, ${javaPing.latencyMs ?: "?"}ms)."
                )
            } else {
                CompatibilityCheckItem(
                    component = "Java Server",
                    status = CompatibilityItemStatus.INCOMPATIBLE,
                    summary = "Unreachable",
                    detail = javaPing.errorMessage ?: "Java endpoint ${profile.javaAddress}:${profile.javaPort} did not respond."
                )
            }
        }
    }

    class BedrockVersionChecker {
        fun checkLocal(geyserConfig: GeyserConfig, bedrockPing: BedrockPingResult?): CompatibilityCheckItem {
            return if (bedrockPing != null && bedrockPing.reachable) {
                CompatibilityCheckItem(
                    component = "Bedrock Version",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = "Supported (${bedrockPing.versionName ?: "RakNet"} · proto ${bedrockPing.protocolVersion ?: "?"})",
                    detail = "Geyser Bedrock listener active on UDP/TCP ${geyserConfig.bedrockPort}."
                )
            } else {
                CompatibilityCheckItem(
                    component = "Bedrock Version",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = "Supported via Geyser Standalone (Port ${geyserConfig.bedrockPort})",
                    detail = "Official GeyserMC build dynamically translates supported Bedrock client versions."
                )
            }
        }

        fun checkExternal(profile: BridgeServerProfile, bedrockPing: BedrockPingResult?): CompatibilityCheckItem {
            if (bedrockPing == null) {
                return CompatibilityCheckItem(
                    component = "Bedrock Version",
                    status = CompatibilityItemStatus.UNVERIFIED,
                    summary = "${profile.bedrockAddress}:${profile.bedrockPort}",
                    detail = "Run compatibility probe to send a RakNet Unconnected Ping."
                )
            }
            return if (bedrockPing.reachable) {
                CompatibilityCheckItem(
                    component = "Bedrock Version",
                    status = CompatibilityItemStatus.COMPATIBLE,
                    summary = "Supported (${bedrockPing.versionName ?: "Bedrock"} · proto ${bedrockPing.protocolVersion ?: "?"})",
                    detail = "Bedrock listener responded on ${profile.bedrockAddress}:${profile.bedrockPort} (${bedrockPing.latencyMs ?: "?"}ms)."
                )
            } else {
                CompatibilityCheckItem(
                    component = "Bedrock Version",
                    status = CompatibilityItemStatus.INCOMPATIBLE,
                    summary = "No Geyser / Bedrock response",
                    detail = bedrockPing.errorMessage ?: "No RakNet pong received from ${profile.bedrockAddress}:${profile.bedrockPort}."
                )
            }
        }
    }

    class ServerSoftwareChecker {
        fun checkLocal(config: LocalServerConfig, geyserConfig: GeyserConfig): CompatibilityCheckItem {
            if (geyserConfig.authType == com.star.launcher.bridge.model.BridgeAuthMode.FLOODGATE &&
                !config.software.supportsFloodgatePlugin
            ) {
                return CompatibilityCheckItem(
                    component = "Server Software",
                    status = CompatibilityItemStatus.INCOMPATIBLE,
                    summary = "${config.software.displayName} (No Floodgate plugin support)",
                    detail = "Vanilla Mojang server cannot load Floodgate plugins. Use PaperMC for Floodgate or switch Geyser auth-type to Online."
                )
            }
            return CompatibilityCheckItem(
                component = "Server Software",
                status = CompatibilityItemStatus.COMPATIBLE,
                summary = config.software.displayName,
                detail = if (config.software == LocalServerSoftware.PAPER) {
                    "PaperMC supports both Geyser Standalone and optional Floodgate plugin."
                } else {
                    "Vanilla Mojang server works with Geyser Standalone in Online/Offline auth mode."
                }
            )
        }

        fun checkExternal(profile: BridgeServerProfile): CompatibilityCheckItem = CompatibilityCheckItem(
            component = "Server Software",
            status = CompatibilityItemStatus.COMPATIBLE,
            summary = profile.serverSoftware.displayName,
            detail = "External servers require server-side Geyser (or a Geyser proxy) to accept Bedrock clients."
        )
    }

    class AuthenticationChecker {
        fun checkLocal(
            authManager: BridgeAuthenticationManager,
            localConfig: LocalServerConfig,
            geyserConfig: GeyserConfig
        ): CompatibilityCheckItem {
            val result = authManager.validateLocalHostingAuth(localConfig, geyserConfig)
            val status = when {
                result.errors.isNotEmpty() -> CompatibilityItemStatus.INCOMPATIBLE
                result.warnings.isNotEmpty() -> CompatibilityItemStatus.WARNING
                else -> CompatibilityItemStatus.COMPATIBLE
            }
            return CompatibilityCheckItem(
                component = "Authentication",
                status = status,
                summary = if (result.isValid) "Configured (${geyserConfig.authType.displayName})" else "Invalid",
                detail = (result.errors + result.warnings).joinToString("; ").ifBlank { result.summary }
            )
        }

        fun checkExternal(
            authManager: BridgeAuthenticationManager,
            geyserConfig: GeyserConfig
        ): CompatibilityCheckItem {
            val result = authManager.validateExternalServerAuth(geyserConfig)
            val status = when {
                result.errors.isNotEmpty() -> CompatibilityItemStatus.INCOMPATIBLE
                result.warnings.isNotEmpty() -> CompatibilityItemStatus.WARNING
                else -> CompatibilityItemStatus.COMPATIBLE
            }
            return CompatibilityCheckItem(
                component = "Authentication",
                status = status,
                summary = if (result.isValid) "Configured (${geyserConfig.authType.displayName})" else "Invalid",
                detail = (result.errors + result.warnings).joinToString("; ").ifBlank { result.summary }
            )
        }
    }

    class NetworkCompatibilityChecker {
        fun check(endpoints: NetworkEndpointsSnapshot, requireLan: Boolean): CompatibilityCheckItem {
            if (!endpoints.portConflictReason.isNullOrBlank()) {
                return CompatibilityCheckItem(
                    component = "Network",
                    status = CompatibilityItemStatus.INCOMPATIBLE,
                    summary = "Port Conflict",
                    detail = endpoints.portConflictReason
                )
            }
            if (requireLan && !endpoints.isLanConnected) {
                return CompatibilityCheckItem(
                    component = "Network",
                    status = CompatibilityItemStatus.WARNING,
                    summary = "Localhost Ready (No LAN IP)",
                    detail = "No active Wi-Fi/LAN IPv4 or IPv6 interface detected. Same-device localhost works; connect to Wi-Fi for LAN Bedrock play."
                )
            }
            val lanSummary = endpoints.primaryLanIpv4?.let { "LAN $it" } ?: "Localhost 127.0.0.1"
            return CompatibilityCheckItem(
                component = "Network",
                status = CompatibilityItemStatus.COMPATIBLE,
                summary = "Ready ($lanSummary)",
                detail = "Java TCP ${endpoints.javaPort}, Bedrock ${endpoints.bedrockTransport.displayName} ${endpoints.bedrockPort}."
            )
        }
    }

    private val runtimeChecker = RuntimeVersionChecker()
    private val geyserChecker = GeyserVersionChecker()
    private val javaChecker = JavaVersionChecker()
    private val bedrockChecker = BedrockVersionChecker()
    private val softwareChecker = ServerSoftwareChecker()
    private val authChecker = AuthenticationChecker()
    private val networkChecker = NetworkCompatibilityChecker()

    fun evaluateLocalHosting(
        geyserRuntime: RuntimeCheckDetails?,
        serverRuntime: RuntimeCheckDetails?,
        installedGeyser: InstalledArtifactMetadata?,
        latestGeyserBuild: Int?,
        localConfig: LocalServerConfig,
        geyserConfig: GeyserConfig,
        endpoints: NetworkEndpointsSnapshot,
        authManager: BridgeAuthenticationManager,
        javaPing: JavaPingResult? = null,
        bedrockPing: BedrockPingResult? = null
    ): BridgeCompatibilityReport {
        val items = mutableListOf<CompatibilityCheckItem>()
        items += geyserChecker.check(installedGeyser, latestGeyserBuild)
        items += runtimeChecker.check(geyserRuntime)
        if (serverRuntime != null) {
            val srvOk = serverRuntime.isCompatible
            items += CompatibilityCheckItem(
                component = "Server Java Runtime",
                status = if (srvOk) CompatibilityItemStatus.COMPATIBLE else CompatibilityItemStatus.INCOMPATIBLE,
                summary = if (srvOk) "Java ${serverRuntime.selectedJavaVersion}" else "Requires Java ${serverRuntime.requiredJavaVersion}+",
                detail = serverRuntime.summary
            )
        }
        items += softwareChecker.checkLocal(localConfig, geyserConfig)
        items += javaChecker.checkLocal(localConfig, javaPing)
        items += bedrockChecker.checkLocal(geyserConfig, bedrockPing)
        items += authChecker.checkLocal(authManager, localConfig, geyserConfig)
        items += networkChecker.check(endpoints, requireLan = true)

        val anyIncompatible = items.any { it.status == CompatibilityItemStatus.INCOMPATIBLE }
        val headline = if (!anyIncompatible) {
            "Local Server Bridge Ready"
        } else {
            items.first { it.status == CompatibilityItemStatus.INCOMPATIBLE }.let { "${it.component}: ${it.summary}" }
        }
        return BridgeCompatibilityReport(
            items = items,
            overallCompatible = !anyIncompatible,
            summaryHeadline = headline
        )
    }

    fun evaluateExternalServer(
        profile: BridgeServerProfile,
        javaPing: JavaPingResult?,
        bedrockPing: BedrockPingResult?,
        endpoints: NetworkEndpointsSnapshot,
        authManager: BridgeAuthenticationManager,
        geyserConfig: GeyserConfig
    ): BridgeCompatibilityReport {
        val items = listOf(
            javaChecker.checkExternal(profile, javaPing),
            bedrockChecker.checkExternal(profile, bedrockPing),
            softwareChecker.checkExternal(profile),
            authChecker.checkExternal(authManager, geyserConfig),
            networkChecker.check(endpoints, requireLan = false)
        )
        val anyIncompatible = items.any { it.status == CompatibilityItemStatus.INCOMPATIBLE }
        val serverSideNote = if (javaPing?.reachable == true && bedrockPing?.reachable == false) {
            "Server-side Geyser is required for external Java servers. STAR Launcher cannot force Bedrock support onto a remote Java server that does not run Geyser."
        } else {
            null
        }
        val headline = when {
            !anyIncompatible -> "External Cross-Play Compatible"
            serverSideNote != null -> "Java Online · Server-Side Geyser Not Detected"
            else -> items.first { it.status == CompatibilityItemStatus.INCOMPATIBLE }.let { "${it.component}: ${it.summary}" }
        }
        return BridgeCompatibilityReport(
            items = items,
            overallCompatible = !anyIncompatible,
            summaryHeadline = headline,
            serverSideGeyserRequiredNote = serverSideNote
        )
    }
}
