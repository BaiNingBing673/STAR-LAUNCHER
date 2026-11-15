package com.star.launcher.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.data.db.entity.AppSettingEntity
import com.star.launcher.data.db.entity.CompatibilityResultEntity
import com.star.launcher.data.db.entity.DeviceInfoEntity
import com.star.launcher.data.db.entity.DiagnosticLogEntity
import com.star.launcher.data.db.entity.DownloadTaskEntity
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameSessionEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.DiagnosticLogLevel
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.db.model.SessionStatus
import com.star.launcher.data.db.model.SettingValueType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AetherDatabaseTest {

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
    fun `transactional instance creation and session lifecycle cascades cleanly`() = runTest {
        val device = DeviceInfoEntity(
            deviceFingerprint = "fp-arm64-adreno740",
            cpuAbiPrimary = "arm64-v8a",
            supportedAbisJson = "[\"arm64-v8a\",\"armeabi-v7a\"]",
            is64Bit = true,
            androidApiLevel = 34,
            gpuVendor = "Qualcomm",
            gpuRenderer = "Adreno (TM) 740",
            glVersion = "OpenGL ES 3.2 V@0676",
            glesMajorVersion = 3,
            hasVulkan = true,
            vulkanVersion = "1.3.128",
            totalRamMb = 12288L,
            probedAt = 1000L
        )
        val runtime = RuntimeRegistryEntity(
            runtimeId = "Internal",
            displayName = "Internal JRE 21",
            majorVersion = 21,
            arch = "aarch64",
            installPath = "/data/user/0/com.star.launcher.debug/runtimes/Internal",
            binaryVersion = "21.0.4",
            isInternal = true,
            isVerified = true,
            installedAt = 1001L
        )
        val profile1 = RendererProfileEntity(
            profileId = "prof-mg",
            rendererId = "opengles_mobileglues",
            displayName = "MobileGlues",
            backendConfigJson = "{}",
            useAngle = false,
            preferSystemDriver = true,
            isDefault = true,
            updatedAt = 1002L
        )
        val profile2 = RendererProfileEntity(
            profileId = "prof-zink",
            rendererId = "opengles3_desktopgl_zink_kopper",
            displayName = "Zink Kopper",
            backendConfigJson = "{}",
            useAngle = false,
            preferSystemDriver = false,
            isDefault = false,
            updatedAt = 1003L
        )
        val version = GameVersionEntity(
            versionId = "1.21.4",
            inheritsFrom = null,
            versionType = "release",
            modLoaderType = ModLoaderType.VANILLA,
            modLoaderVersion = null,
            requiredJavaMajor = 21,
            lwjglMajor = 3,
            manifestPath = "/sdcard/versions/1.21.4/1.21.4.json",
            isInstalled = true,
            releaseTimeEpochMs = 1733184000000L
        )

        db.settingsDao().upsert(AppSettingEntity("ram_auto", "true", SettingValueType.BOOL, 1000L))
        db.deviceInfoDao().upsert(device)
        db.runtimeRegistryDao().upsert(runtime)
        db.rendererProfileDao().upsert(profile1)
        db.rendererProfileDao().upsert(profile2)
        db.rendererProfileDao().setDefaultProfile("prof-zink")
        db.gameVersionDao().upsert(version)

        val profiles = db.rendererProfileDao().observeAll().first()
        assertEquals(false, profiles.first { it.profileId == "prof-mg" }.isDefault)
        assertEquals(true, profiles.first { it.profileId == "prof-zink" }.isDefault)

        val instance = GameInstanceEntity(
            instanceId = "inst-1",
            name = "Survival 1.21.4",
            gameDirPath = "/sdcard/instances/survival",
            versionId = "1.21.4",
            runtimeId = "Internal",
            rendererProfileId = "prof-zink",
            allocatedRamMb = 3072,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = null,
            createdAt = 2000L
        )
        val initialCompat = CompatibilityResultEntity(
            resultId = "compat-1",
            deviceFingerprint = device.deviceFingerprint,
            instanceId = instance.instanceId,
            versionId = version.versionId,
            rendererId = "opengles3_desktopgl_zink_kopper",
            runtimeId = runtime.runtimeId,
            tier = CompatibilityTier.THEORETICAL,
            status = CompatibilityStatus.PASS,
            summaryReason = "ARM64 + Vulkan 1.3 + Java 21 compatible",
            detailsJson = "{}",
            evaluatedAt = 2001L
        )

        db.gameInstanceDao().upsertInstanceWithCompatibility(instance, initialCompat)
        assertNotNull(db.gameInstanceDao().getById("inst-1"))

        val dlTask = DownloadTaskEntity(
            taskId = "dl-1",
            instanceId = "inst-1",
            versionId = "1.21.4",
            taskType = DownloadTaskType.CLIENT_JAR,
            sourceUrl = "https://piston-data.mojang.com/client.jar",
            targetPath = "/sdcard/versions/1.21.4/1.21.4.jar",
            expectedSha1 = "deadbeef",
            totalBytes = 1024L,
            downloadedBytes = 1024L,
            status = DownloadStatus.COMPLETED,
            retryCount = 0,
            errorMessage = null,
            updatedAt = 2002L
        )
        db.downloadTaskDao().upsert(dlTask)

        val session = GameSessionEntity(
            sessionId = "sess-1",
            instanceId = "inst-1",
            versionId = "1.21.4",
            runtimeId = "Internal",
            rendererProfileId = "prof-zink",
            deviceFingerprint = device.deviceFingerprint,
            status = SessionStatus.LAUNCHING,
            exitCode = null,
            startedAt = 3000L,
            endedAt = null,
            crashReportPath = null
        )
        db.diagnosticSessionDao().startSessionWithLog(
            session = session,
            initialLog = DiagnosticLogEntity(
                sessionId = "sess-1",
                timestampMs = 3001L,
                level = DiagnosticLogLevel.INFO,
                sourceTag = "Launcher",
                messageRedacted = "Preparing JVM launch"
            )
        )
        db.diagnosticSessionDao().finishSessionWithLog(
            sessionId = "sess-1",
            status = SessionStatus.EXITED_OK,
            exitCode = 0,
            endedAt = 3500L,
            crashReportPath = null,
            finalLog = DiagnosticLogEntity(
                sessionId = "sess-1",
                timestampMs = 3500L,
                level = DiagnosticLogLevel.INFO,
                sourceTag = "GameProcess",
                messageRedacted = "Clean exit 0"
            )
        )

        assertEquals(SessionStatus.EXITED_OK, db.diagnosticSessionDao().getSession("sess-1")?.status)
        assertEquals(2, db.diagnosticSessionDao().getLogsForSession("sess-1").size)

        // Deleting the instance cascades compatibility results, download tasks, sessions, and logs.
        db.gameInstanceDao().deleteById("inst-1")
        assertTrue(db.downloadTaskDao().getForInstance("inst-1").isEmpty())
        assertTrue(db.compatibilityResultDao().observeForInstance("inst-1").first().isEmpty())
        assertNull(db.diagnosticSessionDao().getSession("sess-1"))
        assertTrue(db.diagnosticSessionDao().getLogsForSession("sess-1").isEmpty())
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `duplicate instance gameDirPath violates unique index`() = runTest {
        db.gameInstanceDao().insertStrict(
            GameInstanceEntity(
                instanceId = "inst-a",
                name = "Alpha",
                gameDirPath = "/sdcard/shared-dir",
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
        db.gameInstanceDao().insertStrict(
            GameInstanceEntity(
                instanceId = "inst-b",
                name = "Beta",
                gameDirPath = "/sdcard/shared-dir",
                versionId = null,
                runtimeId = null,
                rendererProfileId = null,
                allocatedRamMb = 2048,
                customJvmArgs = null,
                controlMapPath = null,
                lastPlayedAt = null,
                createdAt = 101L
            )
        )
    }
}
