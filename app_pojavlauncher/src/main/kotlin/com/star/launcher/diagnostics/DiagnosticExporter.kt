package com.star.launcher.diagnostics

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.DeviceInfoDao
import com.star.launcher.data.db.dao.DiagnosticSessionDao
import kotlinx.coroutines.flow.first
import net.kdt.pojavlaunch.Tools
import java.io.File

/**
 * Generates sanitized plain-text support reports containing hardware capabilities,
 * recent session statuses, redacted Room diagnostic logs, and a redacted tail of `latestlog.txt`.
 */
class DiagnosticExporter(
    private val storageManager: AetherStorageManager,
    private val deviceInfoDao: DeviceInfoDao,
    private val diagnosticSessionDao: DiagnosticSessionDao,
    private val latestLogProvider: () -> File = { File(Tools.DIR_GAME_HOME, "latestlog.txt") },
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun exportRedactedReport(maxLogLines: Int = 120): File {
        val exportDir = File(storageManager.downloadStagingDir().parentFile, "aether_exports").apply { mkdirs() }
        val outFile = File(exportDir, "star-support-${clockMillis()}.txt")
        val device = deviceInfoDao.getLatest()
        val sessions = diagnosticSessionDao.observeRecentSessions(10).first()
        val logs = diagnosticSessionDao.observeRecentLogs(maxLogLines).first()
        val latestLogFile = latestLogProvider()

        val sb = StringBuilder()
        sb.appendLine("=== STAR LAUNCHER DIAGNOSTIC REPORT ===")
        sb.appendLine("GeneratedEpochMs: ${clockMillis()}")
        sb.appendLine()
        sb.appendLine("[Device Capability Snapshot]")
        if (device == null) {
            sb.appendLine("Not probed yet.")
        } else {
            sb.appendLine("ABI: ${device.cpuAbiPrimary} (64-bit=${device.is64Bit}) | API: ${device.androidApiLevel} | RAM: ${device.totalRamMb} MB")
            sb.appendLine("GPU: ${device.gpuVendor} / ${device.gpuRenderer}")
            sb.appendLine("OpenGL: ${device.glVersion} (ES ${device.glesMajorVersion}) | Vulkan: ${device.vulkanVersion ?: "None"}")
        }
        sb.appendLine()
        sb.appendLine("[Recent Sessions]")
        if (sessions.isEmpty()) {
            sb.appendLine("None recorded.")
        } else {
            sessions.forEach { s ->
                sb.appendLine("${s.sessionId} | inst=${s.instanceId} | ver=${s.versionId ?: "-"} | status=${s.status} | exit=${s.exitCode ?: "-"}")
            }
        }
        sb.appendLine()
        sb.appendLine("[Diagnostic Logs (Redacted)]")
        logs.asReversed().forEach { entry ->
            sb.appendLine("${entry.timestampMs} [${entry.level}/${entry.sourceTag}] ${SensitiveLogRedactor.redact(entry.messageRedacted)}")
        }
        sb.appendLine()
        sb.appendLine("[latestlog.txt Tail (Redacted)]")
        if (latestLogFile.isFile) {
            val tail = latestLogFile.readLines().takeLast(maxLogLines)
            tail.forEach { line ->
                sb.appendLine(SensitiveLogRedactor.redact(line))
            }
        } else {
            sb.appendLine("latestlog.txt not present.")
        }

        outFile.writeText(sb.toString())
        return outFile
    }
}
