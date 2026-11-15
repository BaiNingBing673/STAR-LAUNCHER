package com.star.launcher.diagnostics

import com.star.launcher.data.db.dao.CompatibilityResultDao
import com.star.launcher.data.db.dao.DiagnosticSessionDao
import com.star.launcher.data.db.entity.CompatibilityResultEntity
import com.star.launcher.data.db.entity.DiagnosticLogEntity
import com.star.launcher.data.db.entity.GameSessionEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.DiagnosticLogLevel
import com.star.launcher.data.db.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Redacts authentication credentials, JWTs, bearer headers, and session tokens
 * before log lines are written to Room or exported in diagnostic bundles.
 */
object SensitiveLogRedactor {
    private val jwtPattern = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")
    private val bearerPattern = Regex("""(?i)(Authorization\s*:\s*Bearer\s+|Bearer\s+)[A-Za-z0-9._~+/-]+=*""")
    private val cliAccessTokenPattern = Regex("""(--accessToken(?:=|\s+))(\S+)""")
    private val jsonSecretPattern = Regex(
        """(?i)("(?:accessToken|refreshToken|clientToken|msaRefreshToken|password|xstsToken|x-api-key|apiKey)"\s*:\s*")([^"]*)(")"""
    )
    private val apiKeyHeaderPattern = Regex("""(?i)((?:x-api-key|x-star-client-token)\s*[:=]\s*)(\S+)""")
    private val uuidTokenArgPattern = Regex("""(--uuid(?:=|\s+))([0-9a-fA-F-]{32,36})""")

    fun redact(rawLine: String): String {
        var out = rawLine
        out = cliAccessTokenPattern.replace(out) { "${it.groupValues[1]}[REDACTED]" }
        out = uuidTokenArgPattern.replace(out) { "${it.groupValues[1]}[REDACTED_UUID]" }
        out = jsonSecretPattern.replace(out) { "${it.groupValues[1]}[REDACTED]${it.groupValues[3]}" }
        out = apiKeyHeaderPattern.replace(out) { "${it.groupValues[1]}[REDACTED]" }
        out = bearerPattern.replace(out) { "${it.groupValues[1]}[REDACTED]" }
        out = jwtPattern.replace(out, "[REDACTED_JWT]")
        return out
    }
}

class DiagnosticsManager(
    private val diagnosticSessionDao: DiagnosticSessionDao,
    private val compatibilityResultDao: CompatibilityResultDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    fun observeRecentSessions(limit: Int = 20): Flow<List<GameSessionEntity>> =
        diagnosticSessionDao.observeRecentSessions(limit)

    fun observeRecentLogs(limit: Int = 50): Flow<List<DiagnosticLogEntity>> =
        diagnosticSessionDao.observeRecentLogs(limit)

    suspend fun startSession(
        instanceId: String,
        versionId: String?,
        runtimeId: String?,
        rendererProfileId: String?,
        deviceFingerprint: String?,
        startupSummary: String
    ): String {
        val sessionId = idGenerator()
        val now = clockMillis()
        val session = GameSessionEntity(
            sessionId = sessionId,
            instanceId = instanceId,
            versionId = versionId,
            runtimeId = runtimeId,
            rendererProfileId = rendererProfileId,
            deviceFingerprint = deviceFingerprint,
            status = SessionStatus.LAUNCHING,
            exitCode = null,
            startedAt = now,
            endedAt = null,
            crashReportPath = null
        )
        val initialLog = DiagnosticLogEntity(
            sessionId = sessionId,
            timestampMs = now,
            level = DiagnosticLogLevel.INFO,
            sourceTag = "Diagnostics",
            messageRedacted = SensitiveLogRedactor.redact(startupSummary)
        )
        diagnosticSessionDao.startSessionWithLog(session, initialLog)
        return sessionId
    }

    suspend fun appendLog(
        sessionId: String,
        level: DiagnosticLogLevel,
        sourceTag: String,
        rawMessage: String
    ): Long {
        return diagnosticSessionDao.insertLog(
            DiagnosticLogEntity(
                sessionId = sessionId,
                timestampMs = clockMillis(),
                level = level,
                sourceTag = sourceTag,
                messageRedacted = SensitiveLogRedactor.redact(rawMessage)
            )
        )
    }

    /**
     * Finalizes a session and persists a [CompatibilityTier.VERIFIED_RUNTIME] record only when
     * runtime execution actually occurred (e.g., GL context initialized or process exited with code).
     */
    suspend fun finishSessionAndRecordCompatibility(
        sessionId: String,
        status: SessionStatus,
        exitCode: Int?,
        reachedGlContextInit: Boolean,
        rendererId: String,
        crashReportPath: String? = null,
        summaryMessage: String
    ) {
        val now = clockMillis()
        val existing = diagnosticSessionDao.getSession(sessionId)
        diagnosticSessionDao.finishSessionWithLog(
            sessionId = sessionId,
            status = status,
            exitCode = exitCode,
            endedAt = now,
            crashReportPath = crashReportPath,
            finalLog = DiagnosticLogEntity(
                sessionId = sessionId,
                timestampMs = now,
                level = if (status == SessionStatus.EXITED_OK) DiagnosticLogLevel.INFO else DiagnosticLogLevel.ERROR,
                sourceTag = "GameProcess",
                messageRedacted = SensitiveLogRedactor.redact(summaryMessage)
            )
        )

        val fingerprint = existing?.deviceFingerprint ?: return
        val compatStatus = when {
            status == SessionStatus.EXITED_OK && exitCode == 0 && reachedGlContextInit ->
                CompatibilityStatus.PASS
            status == SessionStatus.KILLED && reachedGlContextInit ->
                CompatibilityStatus.WARN
            else ->
                CompatibilityStatus.FAIL
        }

        compatibilityResultDao.upsert(
            CompatibilityResultEntity(
                resultId = idGenerator(),
                deviceFingerprint = fingerprint,
                instanceId = existing.instanceId,
                versionId = existing.versionId,
                rendererId = rendererId,
                runtimeId = existing.runtimeId,
                tier = CompatibilityTier.VERIFIED_RUNTIME,
                status = compatStatus,
                summaryReason = "Session $sessionId status=$status exitCode=${exitCode ?: "null"} glInit=$reachedGlContextInit",
                detailsJson = "{\"sessionId\":\"$sessionId\",\"reachedGlInit\":$reachedGlContextInit}",
                evaluatedAt = now
            )
        )
    }
}
