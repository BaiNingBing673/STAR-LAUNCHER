package com.star.launcher.bridge.process

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.service.GeyserBridgeService
import com.star.launcher.bridge.service.LocalServerService
import com.star.launcher.bridge.storage.BridgeStoragePaths
import java.io.File

/**
 * Controls isolated Android foreground service processes (`:bridge_server` and `:bridge_geyser`)
 * independently so Java Server and Geyser can be started, monitored, commanded, and stopped safely.
 */
interface BridgeProcessController {
    fun startLocalServer(runtimeName: String, memoryMb: Int, customJvmArgs: String)
    fun stopLocalServer(graceful: Boolean = true)
    fun sendServerCommand(command: String)
    fun readServerStatus(): BridgeProcessStatusRecord?

    fun startGeyser(runtimeName: String, memoryMb: Int, debugMode: Boolean)
    fun stopGeyser(graceful: Boolean = true)
    fun sendGeyserCommand(command: String)
    fun readGeyserStatus(): BridgeProcessStatusRecord?

    fun isProcessAlive(record: BridgeProcessStatusRecord?): Boolean
}

class AndroidBridgeProcessManager(
    context: Context,
    private val paths: BridgeStoragePaths,
    private val logManager: BridgeLogManager,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : BridgeProcessController {
    private val appContext = context.applicationContext

    override fun startLocalServer(runtimeName: String, memoryMb: Int, customJvmArgs: String) {
        paths.serverCommandSpoolFile().delete()
        val intent = Intent(appContext, LocalServerService::class.java).apply {
            action = LocalServerService.ACTION_START
            putExtra(LocalServerService.EXTRA_RUNTIME_NAME, runtimeName)
            putExtra(LocalServerService.EXTRA_MEMORY_MB, memoryMb)
            putExtra(LocalServerService.EXTRA_CUSTOM_JVM_ARGS, customJvmArgs)
        }
        ContextCompat.startForegroundService(appContext, intent)
        logManager.logInfo("ProcessManager", "Dispatched start for Local Java Server (:bridge_server) using $runtimeName (${memoryMb}MB)")
    }

    override fun stopLocalServer(graceful: Boolean) {
        if (graceful) {
            BridgeIpcProtocol.appendCommand(paths.serverCommandSpoolFile(), "stop")
        }
        val intent = Intent(appContext, LocalServerService::class.java).apply {
            action = if (graceful) LocalServerService.ACTION_STOP_GRACEFUL else LocalServerService.ACTION_FORCE_KILL
        }
        runCatching { appContext.startService(intent) }
        logManager.logInfo("ProcessManager", "Requested ${if (graceful) "graceful" else "force"} stop for Local Java Server")
    }

    override fun sendServerCommand(command: String) {
        BridgeIpcProtocol.appendCommand(paths.serverCommandSpoolFile(), command)
    }

    override fun readServerStatus(): BridgeProcessStatusRecord? =
        BridgeIpcProtocol.readStatus(paths.serverStatusFile())

    override fun startGeyser(runtimeName: String, memoryMb: Int, debugMode: Boolean) {
        paths.geyserCommandSpoolFile().delete()
        val intent = Intent(appContext, GeyserBridgeService::class.java).apply {
            action = GeyserBridgeService.ACTION_START
            putExtra(GeyserBridgeService.EXTRA_RUNTIME_NAME, runtimeName)
            putExtra(GeyserBridgeService.EXTRA_MEMORY_MB, memoryMb)
            putExtra(GeyserBridgeService.EXTRA_DEBUG_MODE, debugMode)
        }
        ContextCompat.startForegroundService(appContext, intent)
        logManager.logInfo("ProcessManager", "Dispatched start for Geyser Standalone (:bridge_geyser) using $runtimeName (${memoryMb}MB)")
    }

    override fun stopGeyser(graceful: Boolean) {
        if (graceful) {
            BridgeIpcProtocol.appendCommand(paths.geyserCommandSpoolFile(), "geyser stop")
        }
        val intent = Intent(appContext, GeyserBridgeService::class.java).apply {
            action = if (graceful) GeyserBridgeService.ACTION_STOP_GRACEFUL else GeyserBridgeService.ACTION_FORCE_KILL
        }
        runCatching { appContext.startService(intent) }
        logManager.logInfo("ProcessManager", "Requested ${if (graceful) "graceful" else "force"} stop for Geyser Standalone")
    }

    override fun sendGeyserCommand(command: String) {
        BridgeIpcProtocol.appendCommand(paths.geyserCommandSpoolFile(), command)
    }

    override fun readGeyserStatus(): BridgeProcessStatusRecord? =
        BridgeIpcProtocol.readStatus(paths.geyserStatusFile())

    override fun isProcessAlive(record: BridgeProcessStatusRecord?): Boolean {
        if (record == null) return false
        if (record.state != BridgeProcessState.STARTING &&
            record.state != BridgeProcessState.RUNNING &&
            record.state != BridgeProcessState.STOPPING
        ) {
            return false
        }
        val ageMs = clockMillis() - record.heartbeatAtMs
        if (record.heartbeatAtMs > 0L && ageMs > HEARTBEAT_STALE_MS) {
            // Check /proc/<pid> as secondary confirmation on Linux/Android
            if (record.pid > 0 && File("/proc/${record.pid}").exists()) {
                return true
            }
            return false
        }
        return true
    }

    companion object {
        private const val HEARTBEAT_STALE_MS = 12_000L
    }
}
