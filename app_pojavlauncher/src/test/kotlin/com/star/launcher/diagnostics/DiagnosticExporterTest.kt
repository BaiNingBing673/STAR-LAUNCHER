package com.star.launcher.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.DeviceInfoEntity
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.db.model.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
    fun `exports redacted diagnostic report including scrubbed latestlog tail`() = runTest {
        val gameHome = tempFolder.newFolder("game_home")
        val dataDir = tempFolder.newFolder("data_dir")
        val storage = AetherStorageManager({ gameHome }, { dataDir })

        db.deviceInfoDao().upsert(
            DeviceInfoEntity(
                deviceFingerprint = "fp-export",
                cpuAbiPrimary = "arm64-v8a",
                supportedAbisJson = "[\"arm64-v8a\"]",
                is64Bit = true,
                androidApiLevel = 34,
                gpuVendor = "Qualcomm",
                gpuRenderer = "Adreno 740",
                glVersion = "OpenGL ES 3.2",
                glesMajorVersion = 3,
                hasVulkan = true,
                vulkanVersion = "1.3",
                totalRamMb = 8192L,
                probedAt = 100L
            )
        )

        db.gameVersionDao().upsert(
            GameVersionEntity(
                versionId = "1.21.4",
                inheritsFrom = null,
                versionType = "release",
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = 21,
                lwjglMajor = 3,
                manifestPath = "/1.21.4.json",
                isInstalled = true,
                releaseTimeEpochMs = 100L
            )
        )
        db.gameInstanceDao().insertStrict(
            GameInstanceEntity(
                instanceId = "inst-1",
                name = "Test Instance",
                gameDirPath = storage.ensureInstanceDirectories("inst-1").absolutePath,
                versionId = "1.21.4",
                runtimeId = null,
                rendererProfileId = null,
                allocatedRamMb = 2048,
                customJvmArgs = null,
                controlMapPath = null,
                lastPlayedAt = null,
                createdAt = 100L
            )
        )

        val diag = DiagnosticsManager(db.diagnosticSessionDao(), db.compatibilityResultDao(), { 500L }) { "sess-exp" }
        diag.startSession("inst-1", "1.21.4", null, null, "fp-export", "Start --accessToken secretTokenValue")
        diag.finishSessionAndRecordCompatibility(
            sessionId = "sess-exp",
            status = SessionStatus.EXITED_OK,
            exitCode = 0,
            reachedGlContextInit = true,
            rendererId = "opengles_mobileglues",
            summaryMessage = "Clean exit"
        )

        val latestLog = File(gameHome, "latestlog.txt").apply {
            writeText("Starting JVM\nAuthorization: Bearer topSecretBearer123\nLoaded world")
        }

        val exporter = DiagnosticExporter(storage, db.deviceInfoDao(), db.diagnosticSessionDao(), { latestLog }, { 999L })
        val report = exporter.exportRedactedReport()
        assertTrue(report.isFile)
        val text = report.readText()
        assertTrue(text.contains("Adreno 740"))
        assertTrue(text.contains("sess-exp"))
        assertFalse(text.contains("secretTokenValue"))
        assertFalse(text.contains("topSecretBearer123"))
        assertTrue(text.contains("[REDACTED]"))
    }
}
