package com.star.launcher.bridge.logging

import com.star.launcher.bridge.model.BridgeLogEntry
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.diagnostics.SensitiveLogRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque

/**
 * Thread-safe, ring-buffered, secret-redacted log collector for STAR Bedrock Bridge.
 * Tails isolated process logs (`geyser.log`, `server.log`) and records controller lifecycle events.
 */
class BridgeLogManager(
    private val paths: BridgeStoragePaths,
    maxInMemoryEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val entries = ArrayDeque<BridgeLogEntry>()
    private var maxEntries = maxInMemoryEntries.coerceIn(100, 3000)

    private val _logsFlow = MutableStateFlow<List<BridgeLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<BridgeLogEntry>> = _logsFlow.asStateFlow()

    private var geyserFileOffset = 0L
    private var serverFileOffset = 0L

    fun updateRetentionLimit(limit: Int) {
        synchronized(lock) {
            maxEntries = limit.coerceIn(100, 3000)
            while (entries.size > maxEntries) {
                entries.removeFirst()
            }
            _logsFlow.value = entries.toList()
        }
    }

    fun logInfo(source: String, message: String) = append("INFO", source, message, persistToEventsLog = true)

    fun logWarn(source: String, message: String) = append("WARN", source, message, persistToEventsLog = true)

    fun logError(source: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            "$message: ${throwable::class.java.simpleName}: ${throwable.message ?: ""}"
        } else {
            message
        }
        append("ERROR", source, full, persistToEventsLog = true)
    }

    fun logDebug(source: String, message: String, debugEnabled: Boolean) {
        if (!debugEnabled) return
        append("DEBUG", source, message, persistToEventsLog = true)
    }

    fun append(level: String, source: String, rawMessage: String, persistToEventsLog: Boolean = false) {
        val redacted = redactBridgeSecrets(rawMessage)
        val entry = BridgeLogEntry(
            timestampMs = clockMillis(),
            source = source,
            level = level,
            message = redacted
        )
        synchronized(lock) {
            if (entries.size >= maxEntries) {
                entries.removeFirst()
            }
            entries.addLast(entry)
            _logsFlow.value = entries.toList()
        }
        if (persistToEventsLog) {
            runCatching {
                val file = paths.bridgeEventsLogFile()
                truncateIfTooLarge(file)
                file.appendText("${entry.timestampMs} [$level][$source] $redacted\n")
            }
        }
    }

    fun pollProcessLogs() {
        geyserFileOffset = tailFile("Geyser", paths.geyserLogFile(), geyserFileOffset)
        serverFileOffset = tailFile("Server", paths.serverLogFile(), serverFileOffset)
    }

    fun resetTailOffsets() {
        synchronized(lock) {
            geyserFileOffset = 0L
            serverFileOffset = 0L
        }
    }

    fun clearLogs() {
        synchronized(lock) {
            entries.clear()
            _logsFlow.value = emptyList()
        }
    }

    fun recentMessages(limit: Int = 30): List<String> = synchronized(lock) {
        entries.toList().takeLast(limit).map { "[${it.level}][${it.source}] ${it.message}" }
    }

    fun exportDiagnosticText(header: String): String = synchronized(lock) {
        buildString {
            append(redactBridgeSecrets(header)).append("\n\n")
            append("--- Bridge Log Tail (${entries.size} entries) ---\n")
            entries.forEach { entry ->
                append(entry.timestampMs)
                    .append(" [").append(entry.level).append("]")
                    .append("[").append(entry.source).append("] ")
                    .append(entry.message)
                    .append('\n')
            }
        }
    }

    private fun tailFile(source: String, file: File, lastOffset: Long): Long {
        if (!file.isFile) return 0L
        val len = file.length()
        val startOffset = if (len < lastOffset) 0L else lastOffset
        if (len == startOffset) return startOffset
        val maxReadBytes = 64 * 1024L
        val effectiveStart = if (len - startOffset > maxReadBytes) len - maxReadBytes else startOffset
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(effectiveStart)
                val bytes = ByteArray((len - effectiveStart).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                text.lineSequence()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val level = when {
                            line.contains("ERROR", ignoreCase = true) || line.contains("Exception") -> "ERROR"
                            line.contains("WARN", ignoreCase = true) -> "WARN"
                            else -> "INFO"
                        }
                        append(level, source, line, persistToEventsLog = false)
                    }
                len
            }
        }.getOrDefault(startOffset)
    }

    private fun truncateIfTooLarge(file: File) {
        if (file.isFile && file.length() > MAX_PERSISTED_LOG_BYTES) {
            val backup = File(file.parentFile, "${file.name}.prev")
            backup.delete()
            file.renameTo(backup)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 800
        private const val MAX_PERSISTED_LOG_BYTES = 2L * 1024L * 1024L

        private val pemBlockPattern = Regex(
            """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"""
        )
        private val genericPemPattern = Regex(
            """-----BEGIN [A-Z0-9 ]*KEY-----[\s\S]*?-----END [A-Z0-9 ]*KEY-----"""
        )
        private val keyPemLinePattern = Regex("""(?i)(floodgate[-_ ]?key|private[-_ ]?key)\s*[:=]\s*\S+""")

        fun redactBridgeSecrets(raw: String): String {
            var out = SensitiveLogRedactor.redact(raw)
            out = pemBlockPattern.replace(out, "[REDACTED_PEM_PRIVATE_KEY]")
            out = genericPemPattern.replace(out, "[REDACTED_PEM_KEY]")
            out = keyPemLinePattern.replace(out) { "${it.groupValues[1]}=[REDACTED]" }
            return out
        }
    }
}
