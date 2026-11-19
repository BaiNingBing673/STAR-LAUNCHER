package com.star.launcher.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.star.launcher.bridge.process.BridgeHeadlessJvmRunner
import com.star.launcher.bridge.process.BridgeIpcProtocol
import com.star.launcher.bridge.process.BridgeJvmNative
import com.star.launcher.bridge.process.BridgeProcessRole
import com.star.launcher.bridge.process.BridgeProcessState
import com.star.launcher.bridge.process.BridgeProcessStatusRecord
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.ui.dashboard.AetherDashboardActivity
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

abstract class BaseBridgeJvmService : Service() {
    protected abstract val role: BridgeProcessRole
    protected abstract val notificationId: Int
    protected abstract val serviceTitle: String
    protected abstract val gracefulStopCommand: String

    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var startedAtMs: Long = 0L
    private lateinit var paths: BridgeStoragePaths

    @Volatile
    private var watcherThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Tools.initStorageConstants(applicationContext)
        paths = BridgeStoragePaths()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground("Initializing $serviceTitle...")
        val action = intent?.action
        when (action) {
            ACTION_FORCE_KILL -> {
                terminateImmediately("Force stopped by user")
                return START_NOT_STICKY
            }
            ACTION_STOP_GRACEFUL -> {
                requestGracefulShutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!started.compareAndSet(false, true)) {
                    return START_NOT_STICKY
                }
                val runtimeName = intent.getStringExtra(EXTRA_RUNTIME_NAME).orEmpty()
                val memoryMb = intent.getIntExtra(EXTRA_MEMORY_MB, 512).coerceAtLeast(256)
                val extraArgsRaw = intent.getStringExtra(EXTRA_CUSTOM_JVM_ARGS).orEmpty()
                val debugMode = intent.getBooleanExtra(EXTRA_DEBUG_MODE, false)
                launchRuntimeProcess(runtimeName, memoryMb, extraArgsRaw, debugMode)
            }
            else -> {
                if (!started.get()) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    protected abstract fun resolveJarFile(paths: BridgeStoragePaths): File
    protected abstract fun resolveWorkingDir(paths: BridgeStoragePaths): File
    protected abstract fun resolveLogFile(paths: BridgeStoragePaths): File
    protected abstract fun resolveStatusFile(paths: BridgeStoragePaths): File
    protected abstract fun resolveCommandSpoolFile(paths: BridgeStoragePaths): File
    protected abstract fun buildProgramArgs(debugMode: Boolean): List<String>

    private fun launchRuntimeProcess(
        runtimeName: String,
        memoryMb: Int,
        extraArgsRaw: String,
        debugMode: Boolean
    ) {
        startedAtMs = System.currentTimeMillis()
        val statusFile = resolveStatusFile(paths)
        val pid = Process.myPid()

        BridgeIpcProtocol.writeStatus(
            statusFile,
            BridgeProcessStatusRecord(
                role = role,
                state = BridgeProcessState.STARTING,
                pid = pid,
                rssMb = BridgeIpcProtocol.readSelfRssMb(),
                startedAtMs = startedAtMs,
                heartbeatAtMs = System.currentTimeMillis(),
                detail = "Starting $serviceTitle"
            )
        )

        startHeartbeatAndCommandThread(statusFile, resolveCommandSpoolFile(paths), pid)

        Thread({
            try {
                val jarFile = resolveJarFile(paths)
                if (!jarFile.isFile) {
                    throw IllegalStateException("Required artifact missing: ${jarFile.absolutePath}")
                }
                val workingDir = resolveWorkingDir(paths)
                val logFile = resolveLogFile(paths)

                BridgeIpcProtocol.writeStatus(
                    statusFile,
                    BridgeProcessStatusRecord(
                        role = role,
                        state = BridgeProcessState.RUNNING,
                        pid = pid,
                        rssMb = BridgeIpcProtocol.readSelfRssMb(),
                        startedAtMs = startedAtMs,
                        heartbeatAtMs = System.currentTimeMillis(),
                        detail = "$serviceTitle running"
                    )
                )
                ensureForeground("$serviceTitle is active")

                val extraJvm = extraArgsRaw
                    .split(Regex("""\s+"""))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val exitCode = BridgeHeadlessJvmRunner.launchHeadlessJar(
                    serviceContext = this,
                    runtimeName = runtimeName,
                    jarFile = jarFile,
                    workingDir = workingDir,
                    logFile = logFile,
                    memoryMb = memoryMb,
                    extraJvmArgs = extraJvm,
                    programArgs = buildProgramArgs(debugMode),
                    onExit = { code, isSignal ->
                        writeFinalExitStatus(statusFile, pid, code, isSignal)
                    }
                )
                writeFinalExitStatus(statusFile, pid, exitCode, false)
            } catch (t: Throwable) {
                BridgeIpcProtocol.writeStatus(
                    statusFile,
                    BridgeProcessStatusRecord(
                        role = role,
                        state = BridgeProcessState.CRASHED,
                        pid = pid,
                        exitCode = 1,
                        rssMb = BridgeIpcProtocol.readSelfRssMb(),
                        startedAtMs = startedAtMs,
                        heartbeatAtMs = System.currentTimeMillis(),
                        detail = "${t::class.java.simpleName}: ${t.message ?: "Startup failed"}"
                    )
                )
            } finally {
                stopForegroundCompat()
                stopSelf()
                Process.killProcess(Process.myPid())
            }
        }, "StarBridge-${role.id}").start()
    }

    private fun startHeartbeatAndCommandThread(statusFile: File, spoolFile: File, pid: Int) {
        watcherThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    val commands = BridgeIpcProtocol.drainCommands(spoolFile)
                    commands.forEach { cmd ->
                        BridgeJvmNative.writeStdin("$cmd\n")
                    }
                    if (!stopping.get()) {
                        val current = BridgeIpcProtocol.readStatus(statusFile)
                        if (current == null || current.state == BridgeProcessState.RUNNING || current.state == BridgeProcessState.STARTING) {
                            BridgeIpcProtocol.writeStatus(
                                statusFile,
                                BridgeProcessStatusRecord(
                                    role = role,
                                    state = current?.state ?: BridgeProcessState.RUNNING,
                                    pid = pid,
                                    rssMb = BridgeIpcProtocol.readSelfRssMb(),
                                    startedAtMs = startedAtMs,
                                    heartbeatAtMs = System.currentTimeMillis(),
                                    detail = current?.detail ?: "$serviceTitle running"
                                )
                            )
                        }
                    }
                }
                try {
                    Thread.sleep(1500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "StarBridgeWatch-${role.id}").apply {
            isDaemon = true
            start()
        }
    }

    private fun requestGracefulShutdown() {
        if (!stopping.compareAndSet(false, true)) return
        val statusFile = resolveStatusFile(paths)
        val pid = Process.myPid()
        ensureForeground("Stopping $serviceTitle gracefully...")
        BridgeIpcProtocol.writeStatus(
            statusFile,
            BridgeProcessStatusRecord(
                role = role,
                state = BridgeProcessState.STOPPING,
                pid = pid,
                rssMb = BridgeIpcProtocol.readSelfRssMb(),
                startedAtMs = startedAtMs,
                heartbeatAtMs = System.currentTimeMillis(),
                detail = "Stopping $serviceTitle gracefully"
            )
        )
        Thread({
            runCatching { BridgeJvmNative.writeStdin("$gracefulStopCommand\n") }
            val deadline = System.currentTimeMillis() + GRACEFUL_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(300L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            terminateImmediately("$serviceTitle stopped")
        }, "StarBridgeStop-${role.id}").start()
    }

    private fun writeFinalExitStatus(statusFile: File, pid: Int, code: Int, isSignal: Boolean) {
        val cleanStop = stopping.get() || code == 0
        BridgeIpcProtocol.writeStatus(
            statusFile,
            BridgeProcessStatusRecord(
                role = role,
                state = if (cleanStop) BridgeProcessState.EXITED else BridgeProcessState.CRASHED,
                pid = pid,
                exitCode = code,
                isSignal = isSignal,
                rssMb = BridgeIpcProtocol.readSelfRssMb(),
                startedAtMs = startedAtMs,
                heartbeatAtMs = System.currentTimeMillis(),
                detail = if (cleanStop) "$serviceTitle exited cleanly (code=$code)" else "$serviceTitle exited unexpectedly (code=$code, signal=$isSignal)"
            )
        )
    }

    private fun terminateImmediately(detail: String) {
        stopping.set(true)
        watcherThread?.interrupt()
        runCatching { BridgeJvmNative.closeStdinPipe() }
        BridgeIpcProtocol.writeStatus(
            resolveStatusFile(paths),
            BridgeProcessStatusRecord(
                role = role,
                state = BridgeProcessState.STOPPED,
                pid = Process.myPid(),
                exitCode = 0,
                startedAtMs = startedAtMs,
                heartbeatAtMs = System.currentTimeMillis(),
                detail = detail
            )
        )
        stopForegroundCompat()
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun ensureForeground(contentText: String) {
        val stopIntent = Intent(this, this::class.java).apply { action = ACTION_STOP_GRACEFUL }
        val pendingStop = PendingIntent.getService(
            this,
            notificationId,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openDashboardIntent = Intent(this, AetherDashboardActivity::class.java).apply {
            putExtra(AetherDashboardActivity.EXTRA_INITIAL_ROUTE, "BEDROCK_BRIDGE")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            notificationId + 100,
            openDashboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(serviceTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.notif_icon)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "STAR Bedrock Bridge",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Keeps local Java server and Geyser Bedrock Bridge running independently of the game window."
                    }
                )
            }
        }
    }

    companion object {
        const val ACTION_START = "com.star.launcher.bridge.ACTION_START"
        const val ACTION_STOP_GRACEFUL = "com.star.launcher.bridge.ACTION_STOP_GRACEFUL"
        const val ACTION_FORCE_KILL = "com.star.launcher.bridge.ACTION_FORCE_KILL"

        const val EXTRA_RUNTIME_NAME = "extra_runtime_name"
        const val EXTRA_MEMORY_MB = "extra_memory_mb"
        const val EXTRA_CUSTOM_JVM_ARGS = "extra_custom_jvm_args"
        const val EXTRA_DEBUG_MODE = "extra_debug_mode"

        private const val CHANNEL_ID = "star_bedrock_bridge_channel"
        private const val GRACEFUL_WAIT_MS = 8500L
    }
}

class GeyserBridgeService : BaseBridgeJvmService() {
    override val role = BridgeProcessRole.GEYSER
    override val notificationId = 4102
    override val serviceTitle = "STAR Geyser Bedrock Bridge"
    override val gracefulStopCommand = "geyser stop"

    override fun resolveJarFile(paths: BridgeStoragePaths): File = paths.geyserJarFile()
    override fun resolveWorkingDir(paths: BridgeStoragePaths): File = paths.geyserDir()
    override fun resolveLogFile(paths: BridgeStoragePaths): File = paths.geyserLogFile()
    override fun resolveStatusFile(paths: BridgeStoragePaths): File = paths.geyserStatusFile()
    override fun resolveCommandSpoolFile(paths: BridgeStoragePaths): File = paths.geyserCommandSpoolFile()
    override fun buildProgramArgs(debugMode: Boolean): List<String> = listOf("--nogui")

    companion object {
        const val ACTION_START = BaseBridgeJvmService.ACTION_START
        const val ACTION_STOP_GRACEFUL = BaseBridgeJvmService.ACTION_STOP_GRACEFUL
        const val ACTION_FORCE_KILL = BaseBridgeJvmService.ACTION_FORCE_KILL
        const val EXTRA_RUNTIME_NAME = BaseBridgeJvmService.EXTRA_RUNTIME_NAME
        const val EXTRA_MEMORY_MB = BaseBridgeJvmService.EXTRA_MEMORY_MB
        const val EXTRA_DEBUG_MODE = BaseBridgeJvmService.EXTRA_DEBUG_MODE
    }
}

class LocalServerService : BaseBridgeJvmService() {
    override val role = BridgeProcessRole.LOCAL_SERVER
    override val notificationId = 4101
    override val serviceTitle = "STAR Local Java Server"
    override val gracefulStopCommand = "stop"

    override fun resolveJarFile(paths: BridgeStoragePaths): File = paths.serverJarFile()
    override fun resolveWorkingDir(paths: BridgeStoragePaths): File = paths.serverDir()
    override fun resolveLogFile(paths: BridgeStoragePaths): File = paths.serverLogFile()
    override fun resolveStatusFile(paths: BridgeStoragePaths): File = paths.serverStatusFile()
    override fun resolveCommandSpoolFile(paths: BridgeStoragePaths): File = paths.serverCommandSpoolFile()
    override fun buildProgramArgs(debugMode: Boolean): List<String> = listOf("nogui")

    companion object {
        const val ACTION_START = BaseBridgeJvmService.ACTION_START
        const val ACTION_STOP_GRACEFUL = BaseBridgeJvmService.ACTION_STOP_GRACEFUL
        const val ACTION_FORCE_KILL = BaseBridgeJvmService.ACTION_FORCE_KILL
        const val EXTRA_RUNTIME_NAME = BaseBridgeJvmService.EXTRA_RUNTIME_NAME
        const val EXTRA_MEMORY_MB = BaseBridgeJvmService.EXTRA_MEMORY_MB
        const val EXTRA_CUSTOM_JVM_ARGS = BaseBridgeJvmService.EXTRA_CUSTOM_JVM_ARGS
    }
}
