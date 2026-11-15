package com.star.launcher.instance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.compat.GpuFamily
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.engine.adapter.PojavNativeEngineAdapter
import com.star.launcher.engine.adapter.RendererSpec
import com.star.launcher.engine.adapter.RuntimeDescriptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstanceManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var gameHome: File
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gameHome = tempFolder.newFolder("game_home")
        dataDir = tempFolder.newFolder("data_dir")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun managerWith(abi: String = "arm64-v8a", is64Bit: Boolean = true): InstanceManager {
        val probe = object : DeviceCapabilityProbe {
            override fun probe() = DeviceCapabilitiesSnapshot(
                deviceFingerprint = "fp-$abi",
                cpuAbiPrimary = abi,
                supportedAbis = listOf(abi),
                is64Bit = is64Bit,
                androidApiLevel = 34,
                gpuVendor = "Qualcomm",
                gpuRenderer = "Adreno 740",
                glVersion = "OpenGL ES 3.2",
                glesMajorVersion = 3,
                gpuFamily = GpuFamily.ADRENO,
                hasVulkan = true,
                vulkanVersion = "1.3.128",
                hasAdrenoKgslNode = true,
                totalRamMb = 8192L,
                probedAt = 100L
            )
        }
        val storage = AetherStorageManager({ gameHome }, { dataDir })
        val diag = DiagnosticsManager(db.diagnosticSessionDao(), db.compatibilityResultDao(), { 200L })
        return InstanceManager(
            gameInstanceDao = db.gameInstanceDao(),
            gameVersionDao = db.gameVersionDao(),
            runtimeRegistryDao = db.runtimeRegistryDao(),
            rendererProfileDao = db.rendererProfileDao(),
            deviceInfoDao = db.deviceInfoDao(),
            storageManager = storage,
            capabilityProbe = probe,
            compatibilityEvaluator = CompatibilityEvaluator(clockMillis = { 200L }),
            nativeEngineAdapter = PojavNativeEngineAdapter { null },
            diagnosticsManager = diag,
            clockMillis = { 300L }
        )
    }

    @Test
    fun `creates isolated instance prepares launch and blocks unsupported x86 java 21`() = runTest {
        db.gameVersionDao().upsert(
            GameVersionEntity(
                versionId = "1.21.4",
                inheritsFrom = null,
                versionType = "release",
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = 21,
                lwjglMajor = 3,
                manifestPath = "/versions/1.21.4/1.21.4.json",
                isInstalled = true,
                releaseTimeEpochMs = 1000L
            )
        )
        db.runtimeRegistryDao().insertStrict(
            RuntimeRegistryEntity(
                runtimeId = "Internal-21",
                displayName = "JRE 21",
                majorVersion = 21,
                arch = "aarch64",
                installPath = "/rt/21",
                binaryVersion = "21.0.4",
                isInternal = true,
                isVerified = true,
                installedAt = 100L
            )
        )
        db.rendererProfileDao().upsert(
            RendererProfileEntity(
                profileId = "mg",
                rendererId = "opengles_mobileglues",
                displayName = "MobileGlues",
                backendConfigJson = "{}",
                useAngle = false,
                preferSystemDriver = true,
                isDefault = true,
                updatedAt = 100L
            )
        )

        val armManager = managerWith(abi = "arm64-v8a", is64Bit = true)
        val instance = armManager.createInstance(
            name = "My Survival",
            versionId = "1.21.4",
            runtimeId = "Internal-21",
            rendererProfileId = "mg",
            allocatedRamMb = 3072
        )
        assertTrue(File(instance.gameDirPath).isDirectory)

        val ready = armManager.prepareLaunch(instance.instanceId)
        assertTrue(ready is LaunchPreparationResult.Ready)

        // Same instance on 32-bit x86 must be blocked cleanly by compatibility policy.
        val x86Manager = managerWith(abi = "x86", is64Bit = false)
        val blocked = x86Manager.prepareLaunch(instance.instanceId)
        assertTrue(blocked is LaunchPreparationResult.Blocked)
    }

    @Test
    fun `imports legacy launcher profiles read-only without deleting files`() = runTest {
        val legacyDir = File(gameHome, ".minecraft").apply { mkdirs() }
        val legacyMarker = File(legacyDir, "user_world_keep.txt").apply { writeText("keep") }

        val p1 = MinecraftProfile().apply {
            name = "Default"
            lastVersionId = "1.20.1"
        }
        val manager = managerWith()
        val imported = manager.importLegacyProfilesReadOnly(
            legacyProfiles = mapOf("uuid-1" to p1),
            defaultLegacyGameDir = legacyDir.absolutePath
        )

        assertEquals(1, imported)
        // Idempotent second call
        assertEquals(0, manager.importLegacyProfilesReadOnly(mapOf("uuid-1" to p1), legacyDir.absolutePath))

        // Deleting imported Room entry must never touch non-Aether legacy directory
        manager.deleteInstance("legacy_uuid-1", deleteAetherOwnedFiles = true)
        assertTrue(legacyMarker.isFile)
        assertFalse(db.gameInstanceDao().observeAll().first().any { it.instanceId == "legacy_uuid-1" })
    }

    @Test
    fun `syncs runtimes renderers and updates instance settings cleanly`() = runTest {
        db.gameVersionDao().upsert(
            GameVersionEntity(
                versionId = "1.20.4",
                inheritsFrom = null,
                versionType = "release",
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = 17,
                lwjglMajor = 3,
                manifestPath = "/versions/1.20.4/1.20.4.json",
                isInstalled = true,
                releaseTimeEpochMs = 1000L
            )
        )
        val manager = managerWith()
        manager.syncRuntimesAndRenderers(
            runtimes = listOf(
                RuntimeDescriptor("Internal-17", "JRE 17", 17, "aarch64", "17.0.10", true)
            ),
            renderers = listOf(
                RendererSpec("opengles_mobileglues", "MobileGlues", requiresVulkan = false, requiresGles3 = true)
            ),
            runtimeInstallRoot = File(dataDir, "runtimes").absolutePath
        )

        val created = manager.createInstance(
            name = "Initial",
            versionId = null,
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 1024
        )
        val profileId = InstanceManager.rendererProfileIdFor("opengles_mobileglues")
        val updated = manager.updateInstanceConfig(
            instanceId = created.instanceId,
            name = "Configured Pack",
            versionId = "1.20.4",
            runtimeId = "Internal-17",
            rendererProfileId = profileId,
            allocatedRamMb = 3584,
            customJvmArgs = "-XX:+UseZGC",
            controlMapPath = "default.json"
        )
        assertEquals("Configured Pack", updated.name)
        assertEquals("1.20.4", updated.versionId)
        assertEquals("Internal-17", updated.runtimeId)
        assertEquals(profileId, updated.rendererProfileId)
        assertEquals(3584, updated.allocatedRamMb)

        val ready = manager.prepareLaunch(created.instanceId) as LaunchPreparationResult.Ready
        assertEquals("Configured Pack", ready.instanceName)
        assertEquals(3584, ready.spec.allocatedRamMb)
        assertEquals("-XX:+UseZGC", ready.spec.customJvmArgs)
        assertEquals("default.json", ready.spec.inputProfile.controlMapPath)
    }

    @Test
    fun `preserves uncataloged versionId on create and blocks missing or unknown version on launch`() = runTest {
        val manager = managerWith()
        manager.syncRuntimesAndRenderers(
            runtimes = listOf(
                RuntimeDescriptor("External-21", "JRE 21", 21, "aarch64", "21.0.4", false)
            ),
            renderers = listOf(
                RendererSpec("opengles_mobileglues", "MobileGlues", requiresVulkan = false, requiresGles3 = true)
            ),
            runtimeInstallRoot = File(dataDir, "runtimes").absolutePath
        )

        val uncataloged = manager.createInstance(
            name = "Fresh World",
            versionId = "1.21.4",
            runtimeId = null,
            rendererProfileId = null
        )
        assertEquals("1.21.4", uncataloged.versionId)
        val ready = manager.prepareLaunch(uncataloged.instanceId)
        assertTrue(ready is LaunchPreparationResult.Ready)
        assertEquals("1.21.4", (ready as LaunchPreparationResult.Ready).spec.versionId)

        val versionless = manager.createInstance(
            name = "No Version",
            versionId = null,
            runtimeId = null,
            rendererProfileId = null
        )
        val blocked = manager.prepareLaunch(versionless.instanceId)
        assertTrue(blocked is LaunchPreparationResult.Blocked)
        assertTrue((blocked as LaunchPreparationResult.Blocked).reasons.any { it.contains("no Minecraft version", ignoreCase = true) })

        val unknownAttempt = manager.createInstance(
            name = "Unknown String",
            versionId = "unknown",
            runtimeId = null,
            rendererProfileId = null
        )
        assertEquals(null, unknownAttempt.versionId)
        assertTrue(manager.prepareLaunch(unknownAttempt.instanceId) is LaunchPreparationResult.Blocked)
    }
}
