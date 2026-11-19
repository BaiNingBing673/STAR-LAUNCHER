package com.star.launcher.bridge.process

import com.star.launcher.bridge.model.BridgeHealthSnapshot

data class ProcessHealthAssessment(
    val serverAlive: Boolean,
    val geyserAlive: Boolean,
    val serverCrashedDetail: String? = null,
    val geyserCrashedDetail: String? = null,
    val serverRecord: BridgeProcessStatusRecord? = null,
    val geyserRecord: BridgeProcessStatusRecord? = null
)

/**
 * Monitors isolated JVM service heartbeats, PIDs, memory usage (RSS), and unexpected exits.
 */
class BridgeHealthMonitor(
    private val processController: BridgeProcessController
) {
    fun inspectProcessHealth(
        expectServerRunning: Boolean,
        expectGeyserRunning: Boolean
    ): ProcessHealthAssessment {
        val serverRec = processController.readServerStatus()
        val geyserRec = processController.readGeyserStatus()
        val serverAlive = processController.isProcessAlive(serverRec)
        val geyserAlive = processController.isProcessAlive(geyserRec)

        val serverCrash = if (expectServerRunning && !serverAlive) {
            when (serverRec?.state) {
                BridgeProcessState.CRASHED -> serverRec.detail.ifBlank { "Local Java server crashed (exit=${serverRec.exitCode})" }
                BridgeProcessState.EXITED -> if ((serverRec.exitCode ?: 0) != 0) {
                    "Local Java server exited with code ${serverRec.exitCode}"
                } else null
                BridgeProcessState.STOPPED -> null
                null -> "Local Java server heartbeat missing"
                else -> "Local Java server process became unresponsive"
            }
        } else null

        val geyserCrash = if (expectGeyserRunning && !geyserAlive) {
            when (geyserRec?.state) {
                BridgeProcessState.CRASHED -> geyserRec.detail.ifBlank { "Geyser Standalone crashed (exit=${geyserRec.exitCode})" }
                BridgeProcessState.EXITED -> if ((geyserRec.exitCode ?: 0) != 0) {
                    "Geyser Standalone exited with code ${geyserRec.exitCode}"
                } else null
                BridgeProcessState.STOPPED -> null
                null -> "Geyser Standalone heartbeat missing"
                else -> "Geyser Standalone process became unresponsive"
            }
        } else null

        return ProcessHealthAssessment(
            serverAlive = serverAlive,
            geyserAlive = geyserAlive,
            serverCrashedDetail = serverCrash,
            geyserCrashedDetail = geyserCrash,
            serverRecord = serverRec,
            geyserRecord = geyserRec
        )
    }

    fun mergeHealthSnapshot(
        previous: BridgeHealthSnapshot,
        assessment: ProcessHealthAssessment,
        maxPlayers: Int
    ): BridgeHealthSnapshot = previous.copy(
        serverProcessAlive = assessment.serverAlive,
        serverPid = if (assessment.serverAlive) assessment.serverRecord?.pid else null,
        serverRssMb = if (assessment.serverAlive) assessment.serverRecord?.rssMb ?: 0 else 0,
        geyserProcessAlive = assessment.geyserAlive,
        geyserPid = if (assessment.geyserAlive) assessment.geyserRecord?.pid else null,
        geyserRssMb = if (assessment.geyserAlive) assessment.geyserRecord?.rssMb ?: 0 else 0,
        maxPlayers = maxPlayers,
        lastHeartbeatMs = maxOf(
            assessment.serverRecord?.heartbeatAtMs ?: 0L,
            assessment.geyserRecord?.heartbeatAtMs ?: 0L
        ).takeIf { it > 0L } ?: previous.lastHeartbeatMs
    )
}
