package com.star.launcher.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.DeviceInfoEntity
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.DiagnosticLogLevel
import com.star.launcher.data.db.model.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsManagerTest {

    private lateinit var db: AetherDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `redactor strips jwt bearer json secrets and cli access tokens`() {
        val raw = "Cmd: --username Steve --uuid 12345678-1234-1234-1234-1234567890ab " +
            "--accessToken secretJwtHere.payloadHere.sigHere " +
            "Authorization: Bearer abc.def.ghi " +
            "{\"accessToken\":\"superSecret\",\"refreshToken\":\"refreshSecret\"} " +
            "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.signaturepartvalue"

        val redacted = SensitiveLogRedactor.redact(raw)

        assertFalse(redacted.contains("secretJwtHere"))
        assertFalse(redacted.contains("superSecret"))
        assertFalse(redacted.contains("refreshSecret"))
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(redacted.contains("12345678-1234-1234-1234-1234567890ab"))
        assertTrue(redacted.contains("--username Steve"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `diagnostics manager stores redacted logs and records verified runtime outcome`() = runTest {
        db.deviceInfoDao().upsert(
            DeviceInfoEntity(
                deviceFingerprint = "fp-test",
                cpuAbiPrimary = "arm64-v8a",
                supportedAbisJson = "[\"arm64-v8a\"]",
                is64Bit = true,
                androidApiLevel = 34,
                gpuVendor = "Qualcomm",
                gpuRenderer = "Adreno 740",
                glVersion = "OpenGL ES 3.2",
                glesMajorVersion = 3,
                hasVulkan = true,
                vulkanVersion = "1.3.0",
                totalRamMb = 8192L,
                probedAt = 100L
            )
        )
        db.gameInstanceDao().insertStrict(
            GameInstanceEntity(
                instanceId = "inst-diag",
                name = "Diag Instance",
                gameDirPath = "/sdcard/inst-diag",
                versionId = null,
                runtimeId = null,
                rendererProfileId = null,
                allocatedRamMb = 2048,
                customJvmArgs = null,
                controlMapPath = null,
                lastPlayedAt = null,
                createdAt = 100L
            )
        )

        val manager = DiagnosticsManager(
            diagnosticSessionDao = db.diagnosticSessionDao(),
            compatibilityResultDao = db.compatibilityResultDao(),
            clockMillis = { 500L }
        )

        val sessionId = manager.startSession(
            instanceId = "inst-diag",
            versionId = null,
            runtimeId = null,
            rendererProfileId = null,
            deviceFingerprint = "fp-test",
            startupSummary = "Booting with --accessToken TOP_SECRET_VALUE"
        )
        manager.appendLog(
            sessionId = sessionId,
            level = DiagnosticLogLevel.DEBUG,
            sourceTag = "EGL",
            rawMessage = "GL_CONTEXT_INITIALIZED Authorization: Bearer MY_BEARER_SECRET"
        )
        manager.finishSessionAndRecordCompatibility(
            sessionId = sessionId,
            status = SessionStatus.EXITED_OK,
            exitCode = 0,
            reachedGlContextInit = true,
            rendererId = "opengles_mobileglues",
            summaryMessage = "Process exited cleanly"
        )

        val logs = db.diagnosticSessionDao().getLogsForSession(sessionId)
        assertEquals(3, logs.size)
        assertTrue(logs.none { it.messageRedacted.contains("TOP_SECRET_VALUE") })
        assertTrue(logs.none { it.messageRedacted.contains("MY_BEARER_SECRET") })

        val verified = db.compatibilityResultDao()
            .getByDeviceAndTier("fp-test", CompatibilityTier.VERIFIED_RUNTIME)
        assertEquals(1, verified.size)
        assertEquals(CompatibilityStatus.PASS, verified.first().status)
    }
}
