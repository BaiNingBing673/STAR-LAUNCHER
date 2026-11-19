package com.star.launcher.bridge.process

import com.star.launcher.bridge.model.BridgeHealthSnapshot
import com.star.launcher.bridge.network.BridgeNetworkManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Monitors live Java TCP and Bedrock RakNet UDP endpoint reachability and player counts.
 */
class BridgeConnectionMonitor(
    private val networkManager: BridgeNetworkManager
) {
    suspend fun probeLocalEndpoints(
        javaPort: Int,
        bedrockPort: Int,
        current: BridgeHealthSnapshot
    ): BridgeHealthSnapshot = coroutineScope {
        val javaProbe = async { networkManager.pingJavaServer("127.0.0.1", javaPort, timeoutMs = 1800) }
        val bedrockProbe = async { networkManager.pingBedrockServer("127.0.0.1", bedrockPort, timeoutMs = 1800) }
        val javaRes = javaProbe.await()
        val bedrockRes = bedrockProbe.await()

        val worldTotalPlayers = when {
            javaRes.reachable -> javaRes.onlinePlayers
            bedrockRes.reachable -> bedrockRes.onlinePlayers
            else -> 0
        }

        current.copy(
            javaEndpointReachable = javaRes.reachable,
            bedrockEndpointReachable = bedrockRes.reachable,
            javaLatencyMs = javaRes.latencyMs,
            bedrockLatencyMs = bedrockRes.latencyMs,
            activeJavaPlayers = worldTotalPlayers,
            activeBedrockPlayers = 0,
            maxPlayers = javaRes.maxPlayers.takeIf { it > 0 } ?: current.maxPlayers
        )
    }
}
