package com.star.launcher.bridge.process

import org.json.JSONObject
import java.io.File

enum class BridgeProcessRole(val id: String) {
    LOCAL_SERVER("server"),
    GEYSER("geyser")
}

enum class BridgeProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    EXITED,
    CRASHED
}

data class BridgeProcessStatusRecord(
    val role: BridgeProcessRole,
    val state: BridgeProcessState,
    val pid: Int,
    val exitCode: Int? = null,
    val isSignal: Boolean = false,
    val rssMb: Int = 0,
    val startedAtMs: Long = 0L,
    val heartbeatAtMs: Long = 0L,
    val detail: String = ""
)

/**
 * Atomic JSON IPC protocol between `:launcher` and isolated `:bridge_server` / `:bridge_geyser`
 * foreground service processes. Avoids concurrent Room SQLite writers across Android processes.
 */
object BridgeIpcProtocol {
    fun writeStatus(file: File, record: BridgeProcessStatusRecord) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val obj = JSONObject().apply {
            put("role", record.role.id)
            put("state", record.state.name)
            put("pid", record.pid)
            put("exitCode", record.exitCode ?: JSONObject.NULL)
            put("isSignal", record.isSignal)
            put("rssMb", record.rssMb)
            put("startedAtMs", record.startedAtMs)
            put("heartbeatAtMs", record.heartbeatAtMs)
            put("detail", record.detail)
        }
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun readStatus(file: File): BridgeProcessStatusRecord? {
        if (!file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val roleId = obj.getString("role")
            val role = BridgeProcessRole.values().firstOrNull { it.id == roleId } ?: return null
            val state = BridgeProcessState.valueOf(obj.getString("state"))
            val exitCode = if (obj.isNull("exitCode")) null else obj.getInt("exitCode")
            BridgeProcessStatusRecord(
                role = role,
                state = state,
                pid = obj.optInt("pid", 0),
                exitCode = exitCode,
                isSignal = obj.optBoolean("isSignal", false),
                rssMb = obj.optInt("rssMb", 0),
                startedAtMs = obj.optLong("startedAtMs", 0L),
                heartbeatAtMs = obj.optLong("heartbeatAtMs", 0L),
                detail = obj.optString("detail", "")
            )
        }.getOrNull()
    }

    fun appendCommand(spoolFile: File, commandLine: String) {
        spoolFile.parentFile?.mkdirs()
        val clean = commandLine.replace("\r", "").replace("\n", " ").trim()
        if (clean.isEmpty()) return
        spoolFile.appendText("$clean\n", Charsets.UTF_8)
    }

    fun drainCommands(spoolFile: File): List<String> {
        if (!spoolFile.isFile || spoolFile.length() == 0L) return emptyList()
        val staging = File(spoolFile.parentFile, "${spoolFile.name}.draining")
        if (!spoolFile.renameTo(staging)) {
            val lines = runCatching { spoolFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            runCatching { spoolFile.writeText("", Charsets.UTF_8) }
            return lines.map { it.trim() }.filter { it.isNotEmpty() }
        }
        val lines = runCatching { staging.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        staging.delete()
        return lines.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun readSelfRssMb(): Int = runCatching {
        val status = File("/proc/self/status").readText(Charsets.UTF_8)
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return@runCatching 0
        val kb = line.substringAfter("VmRSS:").trim().substringBefore(' ').toIntOrNull() ?: 0
        (kb / 1024).coerceAtLeast(1)
    }.getOrDefault(0)
}
