package com.star.launcher.diagnostics

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.model.SessionStatus
import org.json.JSONObject
import java.io.File

data class SessionExitSpoolRecord(
    val sessionId: String,
    val status: SessionStatus,
    val exitCode: Int?,
    val reachedGlContextInit: Boolean,
    val rendererId: String,
    val crashReportPath: String?,
    val summaryMessage: String
)

/**
 * Handles cross-process diagnostic persistence safely between `:launcher` and `:game`:
 * - `:launcher` owns all Room SQLite writes and flushes WAL checkpoints before `:game` handoff.
 * - `:game` only writes redacted JSON spool files into `aether_spool/`, ingested on next `:launcher` startup.
 */
class CrossProcessSpoolBridge(
    private val storageManager: AetherStorageManager,
    private val diagnosticsManager: DiagnosticsManager
) {
    /**
     * Safely writes a redacted session completion marker from any process (`:game` or `:launcher`).
     */
    fun writeExitSpool(record: SessionExitSpoolRecord): File =
        writeExitSpoolStandalone(storageManager, record)

    /**
     * Called inside `:launcher` to ingest completed `:game` session spools into Room and delete processed files.
     */
    suspend fun ingestPendingSpools(): Int {
        val spoolDir = storageManager.diagnosticSpoolDir()
        if (!spoolDir.isDirectory) return 0
        val files = spoolDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return 0
        var ingested = 0
        for (file in files) {
            val parsed = runCatching { parseSpoolJson(file.readText()) }.getOrNull() ?: continue
            diagnosticsManager.finishSessionAndRecordCompatibility(
                sessionId = parsed.sessionId,
                status = parsed.status,
                exitCode = parsed.exitCode,
                reachedGlContextInit = parsed.reachedGlContextInit,
                rendererId = parsed.rendererId,
                crashReportPath = parsed.crashReportPath,
                summaryMessage = parsed.summaryMessage
            )
            file.delete()
            ingested++
        }
        return ingested
    }

    companion object {
        fun writeExitSpoolStandalone(
            storageManager: AetherStorageManager,
            record: SessionExitSpoolRecord
        ): File {
            val spoolDir = storageManager.diagnosticSpoolDir()
            val safeId = AetherStorageManager.sanitizeSegment(record.sessionId)
            val tmpFile = File(spoolDir, "$safeId.json.tmp")
            val finalFile = File(spoolDir, "$safeId.json")

            val json = JSONObject().apply {
                put("sessionId", record.sessionId)
                put("status", record.status.name)
                put("exitCode", record.exitCode)
                put("reachedGlContextInit", record.reachedGlContextInit)
                put("rendererId", record.rendererId)
                put("crashReportPath", record.crashReportPath ?: JSONObject.NULL)
                put("summaryMessage", SensitiveLogRedactor.redact(record.summaryMessage))
            }
            tmpFile.writeText(json.toString())
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            return finalFile
        }

        /**
         * Java-friendly helper for `:game` process (`MainActivity`) without initializing Room.
         */
        @JvmStatic
        fun writeGameExitSpoolJvm(
            sessionId: String,
            crashed: Boolean,
            reachedGlContextInit: Boolean,
            rendererId: String?,
            summaryMessage: String
        ) {
            runCatching {
                val status = if (crashed) SessionStatus.CRASHED else SessionStatus.EXITED_OK
                writeExitSpoolStandalone(
                    AetherStorageManager(),
                    SessionExitSpoolRecord(
                        sessionId = sessionId,
                        status = status,
                        exitCode = if (crashed) 1 else 0,
                        reachedGlContextInit = reachedGlContextInit,
                        rendererId = rendererId?.ifBlank { "unknown" } ?: "unknown",
                        crashReportPath = null,
                        summaryMessage = summaryMessage
                    )
                )
            }
        }
        fun parseSpoolJson(rawJson: String): SessionExitSpoolRecord {
            val obj = JSONObject(rawJson)
            val exitCode = if (obj.isNull("exitCode")) null else obj.getInt("exitCode")
            val crashPath = if (obj.isNull("crashReportPath")) null else obj.optString("crashReportPath").ifBlank { null }
            return SessionExitSpoolRecord(
                sessionId = obj.getString("sessionId"),
                status = SessionStatus.valueOf(obj.getString("status")),
                exitCode = exitCode,
                reachedGlContextInit = obj.optBoolean("reachedGlContextInit", false),
                rendererId = obj.optString("rendererId", "unknown"),
                crashReportPath = crashPath,
                summaryMessage = obj.optString("summaryMessage", "Session ended")
            )
        }

        /**
         * Flushes SQLite WAL frames before `:launcher` hands off to `:game` and terminates its own PID.
         */
        fun flushDatabaseWal(database: AetherDatabase) {
            runCatching {
                database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }
        }
    }
}
