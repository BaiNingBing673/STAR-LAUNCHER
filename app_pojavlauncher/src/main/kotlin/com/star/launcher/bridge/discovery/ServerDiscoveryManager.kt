package com.star.launcher.bridge.discovery

import com.star.launcher.bridge.model.BridgeConnectionStatus
import com.star.launcher.bridge.model.BridgeServerProfile
import com.star.launcher.bridge.model.ExternalServerSoftware
import com.star.launcher.bridge.model.GeyserAvailability
import com.star.launcher.bridge.network.BedrockPingResult
import com.star.launcher.bridge.network.BridgeNetworkManager
import com.star.launcher.bridge.network.JavaPingResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ServerProbeSummary(
    val profile: BridgeServerProfile,
    val javaPing: JavaPingResult,
    val bedrockPing: BedrockPingResult,
    val guidanceMessage: String
)

/**
 * Manages edition-specific server endpoints and live dual-protocol (Java SLP + Bedrock RakNet)
 * discovery checks without assuming both editions share the same host or port.
 */
class ServerDiscoveryManager(
    private val profileManager: ServerProfileManager,
    private val networkManager: BridgeNetworkManager,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun formatJavaEndpoint(profile: BridgeServerProfile): String =
        if (profile.javaPort == 25565) profile.javaAddress else "${profile.javaAddress}:${profile.javaPort}"

    fun formatBedrockEndpoint(profile: BridgeServerProfile): String =
        "${profile.bedrockAddress}:${profile.bedrockPort}"

    suspend fun probeAndUpdateProfile(profile: BridgeServerProfile): ServerProbeSummary = coroutineScope {
        val javaDeferred = async {
            networkManager.pingJavaServer(profile.javaAddress, profile.javaPort)
        }
        val bedrockDeferred = async {
            networkManager.pingBedrockServer(profile.bedrockAddress, profile.bedrockPort)
        }
        val javaPing = javaDeferred.await()
        val bedrockPing = bedrockDeferred.await()

        val geyserAvailability = when {
            bedrockPing.reachable && isGeyserSignature(bedrockPing) -> GeyserAvailability.DETECTED_COMPATIBLE
            bedrockPing.reachable -> GeyserAvailability.DETECTED_COMPATIBLE
            javaPing.reachable && !bedrockPing.reachable -> GeyserAvailability.NOT_DETECTED
            else -> GeyserAvailability.UNVERIFIED
        }

        val connectionStatus = when {
            javaPing.reachable || bedrockPing.reachable -> BridgeConnectionStatus.ONLINE
            else -> BridgeConnectionStatus.UNREACHABLE
        }

        val detectedSoftware = inferServerSoftware(javaPing, bedrockPing, profile.serverSoftware)

        val compatSummary = buildString {
            append(if (javaPing.reachable) "Java ${javaPing.versionName ?: "Online"} (${javaPing.latencyMs ?: "?"}ms)" else "Java Unreachable")
            append(" · ")
            append(
                if (bedrockPing.reachable) {
                    "Bedrock ${bedrockPing.versionName ?: "Ready"} (${bedrockPing.latencyMs ?: "?"}ms)"
                } else {
                    "No Bedrock/Geyser response on :${profile.bedrockPort}"
                }
            )
        }

        val guidance = when {
            javaPing.reachable && bedrockPing.reachable ->
                "Both Java (${formatJavaEndpoint(profile)}) and Bedrock (${formatBedrockEndpoint(profile)}) endpoints are online. " +
                    "Java players join via Java Multiplayer; Bedrock players join via Bedrock Play -> Servers."
            javaPing.reachable && !bedrockPing.reachable ->
                "Java server is online (${javaPing.versionName ?: "unknown"}), but no Geyser/Bedrock endpoint responded on UDP ${profile.bedrockAddress}:${profile.bedrockPort}. " +
                    "STAR cannot enable Bedrock play on an external Java server unless Geyser is installed server-side (or a separate Geyser host is configured)."
            !javaPing.reachable && bedrockPing.reachable ->
                "Bedrock endpoint responded (${bedrockPing.versionName ?: "MCPE"}), but Java endpoint ${formatJavaEndpoint(profile)} did not respond to TCP ping."
            else ->
                "Neither Java (${formatJavaEndpoint(profile)}) nor Bedrock (${formatBedrockEndpoint(profile)}) endpoint responded. Check hostnames, ports, and server status."
        }

        val updated = profile.copy(
            iconBase64 = javaPing.faviconBase64 ?: profile.iconBase64,
            serverSoftware = detectedSoftware,
            geyserAvailability = geyserAvailability,
            versionCompatibilitySummary = compatSummary,
            connectionStatus = connectionStatus,
            lastCheckedAtMs = clockMillis()
        )
        profileManager.updateProfile(updated)

        ServerProbeSummary(
            profile = updated,
            javaPing = javaPing,
            bedrockPing = bedrockPing,
            guidanceMessage = guidance
        )
    }

    private fun isGeyserSignature(bedrockPing: BedrockPingResult): Boolean {
        val combined = "${bedrockPing.motdLine1.orEmpty()} ${bedrockPing.motdLine2.orEmpty()} ${bedrockPing.serverSoftwareHint.orEmpty()}"
        return combined.contains("geyser", ignoreCase = true)
    }

    private fun inferServerSoftware(
        javaPing: JavaPingResult,
        bedrockPing: BedrockPingResult,
        existing: ExternalServerSoftware
    ): ExternalServerSoftware {
        if (existing != ExternalServerSoftware.UNKNOWN) return existing
        val versionStr = "${javaPing.versionName.orEmpty()} ${bedrockPing.serverSoftwareHint.orEmpty()}".lowercase()
        return when {
            versionStr.contains("purpur") -> ExternalServerSoftware.PURPUR
            versionStr.contains("paper") -> ExternalServerSoftware.PAPER
            versionStr.contains("spigot") -> ExternalServerSoftware.SPIGOT
            versionStr.contains("fabric") -> ExternalServerSoftware.FABRIC
            versionStr.contains("neoforge") -> ExternalServerSoftware.NEOFORGE
            versionStr.contains("forge") -> ExternalServerSoftware.FORGE
            versionStr.contains("velocity") -> ExternalServerSoftware.VELOCITY
            versionStr.contains("bungee") || versionStr.contains("waterfall") -> ExternalServerSoftware.BUNGEECORD
            else -> existing
        }
    }
}
