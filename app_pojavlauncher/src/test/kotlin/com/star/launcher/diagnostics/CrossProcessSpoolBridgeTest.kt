package com.star.launcher.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.DeviceInfoEntity
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossProcessSpoolBridgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var storage: AetherStorageManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameDir = tempFolder.newFolder("game")
        val dataDir = tempFolder.newFolder("data")
        storage = AetherStorageManager(
            { gameDir },
            { dataDir }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `writes redacted spool from game process and ingests into room cleanly`() = runTest {
        db.deviceInfoDao().upsert(
            DeviceInfoEntity(
                deviceFingerprint = "fp-spool",
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
                instanceId = "inst-spool",
                name = "Spool",
                gameDirPath = "/sdcard/spool",
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

        val diag = DiagnosticsManager(
            db.diagnosticSessionDao(),
            db.compatibilityResultDao(),
            clockMillis = { 200L },
            idGenerator = { "sess-spool-1" }
        )
        val sessionId = diag.startSession(
            instanceId = "inst-spool",
            versionId = null,
            runtimeId = null,
            rendererProfileId = null,
            deviceFingerprint = "fp-spool",
            startupSummary = "Booting"
        )

        val bridge = CrossProcessSpoolBridge(storage, diag)
        val spoolFile = bridge.writeExitSpool(
            SessionExitSpoolRecord(
                sessionId = sessionId,
                status = SessionStatus.EXITED_OK,
                exitCode = 0,
                reachedGlContextInit = true,
                rendererId = "opengles_mobileglues",
                crashReportPath = null,
                summaryMessage = "Finished with Authorization: Bearer DO_NOT_LEAK_TOKEN"
            )
        )

        assertTrue(spoolFile.isFile)
        assertFalse(spoolFile.readText().contains("DO_NOT_LEAK_TOKEN"))

        CrossProcessSpoolBridge.flushDatabaseWal(db)
        val count = bridge.ingestPendingSpools()
        assertEquals(1, count)
        assertFalse(spoolFile.exists())

        val updated = db.diagnosticSessionDao().getSession(sessionId)!!
        assertEquals(SessionStatus.EXITED_OK, updated.status)
        assertEquals(0, updated.exitCode)

        val verified = db.compatibilityResultDao()
            .getByDeviceAndTier("fp-spool", CompatibilityTier.VERIFIED_RUNTIME)
        assertEquals(1, verified.size)
        assertEquals(CompatibilityStatus.PASS, verified.first().status)
    }
}
