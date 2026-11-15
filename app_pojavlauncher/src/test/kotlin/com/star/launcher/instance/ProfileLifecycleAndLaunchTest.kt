package com.star.launcher.instance

import android.app.Activity
import android.content.Context
import android.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.compat.GpuFamily
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.CrossProcessSpoolBridge
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.download.DownloadManager
import com.star.launcher.engine.adapter.AetherLaunchPayload
import com.star.launcher.engine.adapter.PojavNativeEngineAdapter
import com.star.launcher.engine.adapter.RendererFacade
import com.star.launcher.engine.adapter.RendererSpec
import com.star.launcher.engine.adapter.RuntimeDescriptor
import com.star.launcher.engine.adapter.RuntimeFacade
import com.star.launcher.ui.dashboard.AetherDashboardController
import com.star.launcher.version.VersionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executor
import net.kdt.pojavlaunch.NewJREUtil
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileLifecycleAndLaunchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AetherDatabase
    private lateinit var gameHome: File
    private lateinit var dataDir: File
    private lateinit var versionsDir: File
    private lateinit var runtimesDir: File
    private var clockTick: Long = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val directExecutor = Executor { runnable -> runnable.run() }
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        gameHome = tempFolder.newFolder("game_home")
        dataDir = tempFolder.newFolder("data_dir")
        versionsDir = File(gameHome, "versions").apply { mkdirs() }
        runtimesDir = File(dataDir, "runtimes").apply { mkdirs() }

        Tools.DIR_GAME_HOME = gameHome.absolutePath
        Tools.DIR_GAME_NEW = gameHome.absolutePath
        Tools.DIR_DATA = dataDir.absolutePath
        Tools.DIR_HOME_VERSION = versionsDir.absolutePath
        Tools.GAME_PROFILES_FILE = File(gameHome, "launcher_profiles.json").absolutePath
        Tools.MULTIRT_HOME = runtimesDir.absolutePath

        LauncherPreferences.DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context)
        LauncherPreferences.DEFAULT_PREF.edit().clear().commit()
        LauncherProfiles.mainProfileJson = null
    }

    @After
    fun tearDown() {
        db.close()
        LauncherProfiles.mainProfileJson = null
    }

    private fun createVersionOnDiskAndDb(
        versionId: String,
        requiredJavaMajor: Int = 21
    ) = GameVersionEntity(
        versionId = versionId,
        inheritsFrom = null,
        versionType = "release",
        modLoaderType = ModLoaderType.VANILLA,
        modLoaderVersion = null,
        requiredJavaMajor = requiredJavaMajor,
        lwjglMajor = 3,
        manifestPath = File(versionsDir, "$versionId/$versionId.json").apply {
            parentFile?.mkdirs()
            writeText("""{"id":"$versionId","type":"release"}""")
        }.also {
            File(versionsDir, "$versionId/$versionId.jar").writeText("jar-placeholder")
        }.absolutePath,
        isInstalled = true,
        releaseTimeEpochMs = ++clockTick
    )

    private fun buildStack(dispatcher: kotlinx.coroutines.CoroutineDispatcher): Triple<InstanceManager, AetherSettingsRepository, VersionManager> {
        val storage = AetherStorageManager({ gameHome }, { dataDir })
        val probe = object : DeviceCapabilityProbe {
            override fun probe() = DeviceCapabilitiesSnapshot(
                deviceFingerprint = "fp-arm64",
                cpuAbiPrimary = "arm64-v8a",
                supportedAbis = listOf("arm64-v8a"),
                is64Bit = true,
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
                probedAt = ++clockTick
            )
        }
        val diagnosticsManager = DiagnosticsManager(
            db.diagnosticSessionDao(),
            db.compatibilityResultDao(),
            { ++clockTick }
        )
        val instanceManager = InstanceManager(
            gameInstanceDao = db.gameInstanceDao(),
            gameVersionDao = db.gameVersionDao(),
            runtimeRegistryDao = db.runtimeRegistryDao(),
            rendererProfileDao = db.rendererProfileDao(),
            deviceInfoDao = db.deviceInfoDao(),
            storageManager = storage,
            capabilityProbe = probe,
            compatibilityEvaluator = CompatibilityEvaluator(clockMillis = { ++clockTick }),
            nativeEngineAdapter = PojavNativeEngineAdapter { null },
            diagnosticsManager = diagnosticsManager,
            clockMillis = { ++clockTick }
        )
        val settingsRepo = AetherSettingsRepository(db.settingsDao(), { ++clockTick })
        val versionManager = VersionManager(db.gameVersionDao(), storage)
        return Triple(instanceManager, settingsRepo, versionManager)
    }

    private suspend fun seedDefaults(instanceManager: InstanceManager, vararg versions: Pair<String, Int>) {
        instanceManager.syncRuntimesAndRenderers(
            runtimes = listOf(
                RuntimeDescriptor("External-21", "Java 21", 21, "aarch64", "21.0.5", false),
                RuntimeDescriptor("Internal-17", "Java 17", 17, "aarch64", "17.0.10", true)
            ),
            renderers = listOf(
                RendererSpec("opengles_mobileglues", "MobileGlues", requiresVulkan = false, requiresGles3 = true)
            ),
            runtimeInstallRoot = runtimesDir.absolutePath
        )
        for ((ver, javaMajor) in versions) {
            db.gameVersionDao().upsert(createVersionOnDiskAndDb(ver, javaMajor))
        }
    }

    private fun buildController(
        instanceManager: InstanceManager,
        settingsRepo: AetherSettingsRepository,
        versionManager: VersionManager,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        scope: kotlinx.coroutines.CoroutineScope
    ): AetherDashboardController {
        val storage = AetherStorageManager({ gameHome }, { dataDir })
        val probe = object : DeviceCapabilityProbe {
            override fun probe() = DeviceCapabilitiesSnapshot(
                deviceFingerprint = "fp-arm64",
                cpuAbiPrimary = "arm64-v8a",
                supportedAbis = listOf("arm64-v8a"),
                is64Bit = true,
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
        val diagnosticsManager = DiagnosticsManager(db.diagnosticSessionDao(), db.compatibilityResultDao())
        val testRuntimes = listOf(
            RuntimeDescriptor("External-21", "Java 21", 21, "aarch64", "21.0.5", false),
            RuntimeDescriptor("Internal-17", "Java 17", 17, "aarch64", "17.0.10", true)
        )
        val testRenderers = listOf(
            RendererSpec("opengles_mobileglues", "MobileGlues", requiresVulkan = false, requiresGles3 = true)
        )
        return AetherDashboardController(
            instanceManager = instanceManager,
            versionManager = versionManager,
            downloadManager = DownloadManager(db.downloadTaskDao(), storage),
            capabilityProbe = probe,
            compatibilityEvaluator = CompatibilityEvaluator(),
            nativeEngineAdapter = PojavNativeEngineAdapter { null },
            spoolBridge = CrossProcessSpoolBridge(storage, diagnosticsManager),
            runtimeFacade = object : RuntimeFacade {
                override fun listInstalledRuntimes() = testRuntimes
                override fun resolveRuntime(runtimeId: String) = testRuntimes.firstOrNull { it.runtimeId == runtimeId }
                override fun isCompatibleWithJavaVersion(runtime: RuntimeDescriptor, requiredJavaMajor: Int) =
                    runtime.majorJavaVersion >= requiredJavaMajor
            },
            rendererFacade = object : RendererFacade {
                override fun listSupportedRenderers() = testRenderers
                override fun isRendererCompatible(rendererId: String) =
                    testRenderers.any { it.rendererId == rendererId }
            },
            diagnosticsManager = diagnosticsManager,
            settingsRepository = settingsRepo,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            scope = scope
        )
    }

    @Test
    fun `legacy LauncherProfiles normalizes keys and updates PREF_KEY_CURRENT_PROFILE without crashing`() {
        val profilesFile = File(Tools.GAME_PROFILES_FILE)
        profilesFile.writeText(
            """
            {
              "selectedProfile": "(Default)",
              "profiles": {
                "(Default)": { "name": "Default", "lastVersionId": "26.3" }
              }
            }
            """.trimIndent()
        )
        // Fresh STAR Launcher install has empty PREF_KEY_CURRENT_PROFILE
        assertEquals("", LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""))

        LauncherProfiles.load()
        val resolvedProfile = LauncherProfiles.getCurrentProfile()
        assertEquals("26.3", resolvedProfile.lastVersionId)

        val persistedKey = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "")
        assertFalse(persistedKey.isNullOrBlank())
        assertNotEquals("(Default)", persistedKey)
        assertNotNull(LauncherProfiles.mainProfileJson.profiles[persistedKey])
    }

    @Test
    fun `legacy LauncherProfiles recovers to another profile on deletion and syncs selection`() {
        val profilesFile = File(Tools.GAME_PROFILES_FILE)
        val firstUuid = "11111111-1111-1111-1111-111111111111"
        val secondUuid = "22222222-2222-2222-2222-222222222222"
        profilesFile.writeText(
            """
            {
              "selectedProfile": "$firstUuid",
              "profiles": {
                "$firstUuid": { "name": "Primary", "lastVersionId": "1.21.4" },
                "$secondUuid": { "name": "Secondary", "lastVersionId": "26.3" }
              }
            }
            """.trimIndent()
        )
        LauncherPreferences.DEFAULT_PREF.edit()
            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, firstUuid)
            .commit()

        LauncherProfiles.load()
        assertTrue(LauncherProfiles.removeProfile(firstUuid))
        assertEquals(secondUuid, LauncherProfiles.getCurrentProfileKey())
        assertEquals("Secondary", LauncherProfiles.getCurrentProfile().name)

        // Cannot delete the last remaining profile
        assertFalse(LauncherProfiles.removeProfile(secondUuid))
        assertEquals(1, LauncherProfiles.mainProfileJson.profiles.size)

        // Zero-profile extreme failure surfaces clear user-facing error rather than silent corruption
        LauncherProfiles.mainProfileJson.profiles.clear()
        var thrown: Throwable? = null
        try {
            LauncherProfiles.getCurrentProfile()
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        assertTrue(thrown?.message?.contains("create or select a profile", ignoreCase = true) == true)
    }

    @Test
    fun `non-legacy STAR runtime verification skips LauncherProfiles even when legacy profile is missing`() {
        // Legacy profiles file absent and PREF_KEY_CURRENT_PROFILE points nowhere
        LauncherPreferences.DEFAULT_PREF.edit()
            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "deleted-legacy-key")
            .commit()

        // Stage a minimal valid arm64 runtime directory for Java 21
        val rtDir = File(runtimesDir, "External-21")
        File(rtDir, "bin").mkdirs()
        File(rtDir, "bin/java").writeText("#!/bin/sh\n")
        File(rtDir, "lib/aarch64").mkdirs()
        File(rtDir, "version").writeText("21.0.5")
        File(rtDir, "release").writeText("JAVA_VERSION=\"21.0.5\"\nOS_ARCH=\"aarch64\"\n")

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        LauncherProfiles.mainProfileJson = null
        val ready = NewJREUtil.ensureRuntimeReady(activity, 21, false, "External-21", false)
        assertTrue(ready)
        // Must NOT have loaded or written launcher_profiles.json or thrown stopped existing
        assertNull(LauncherProfiles.mainProfileJson)
    }

    @Test
    fun `launch installed version, newly added version, switch profiles, and switch MC versions repeatedly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val (instanceManager, settingsRepo, versionManager) = buildStack(dispatcher)
            seedDefaults(instanceManager, "1.21.4" to 21, "26.3" to 21, "1.20.4" to 17)

            val controller = buildController(instanceManager, settingsRepo, versionManager, dispatcher, controllerScope)
            controller.startObserving()
            advanceUntilIdle()

            // 1. Create two profiles & launch installed version 1.21.4
            val p1 = instanceManager.createInstance("Survival", "1.21.4", "External-21", null)
            val p2 = instanceManager.createInstance("Snapshot Lab", "26.3", "External-21", null)
            advanceUntilIdle()

            controller.selectFeaturedInstance(p1.instanceId)
            advanceUntilIdle()
            var launchedPayload: AetherLaunchPayload? = null
            controller.prepareInstanceLaunch(p1.instanceId) { _, payload ->
                launchedPayload = payload
            }
            advanceUntilIdle()
            assertEquals(p1.instanceId, launchedPayload?.instanceId)
            assertEquals("1.21.4", launchedPayload?.versionId)

            // 2. Switch between multiple profiles and launch newly added/downloaded version 26.3
            controller.selectFeaturedInstance(p2.instanceId)
            advanceUntilIdle()
            assertEquals(p2.instanceId, controller.uiState.value.selectedInstanceId)

            var newlyDownloadedPayload: AetherLaunchPayload? = null
            controller.playVersion("26.3") { _, payload ->
                newlyDownloadedPayload = payload
            }
            advanceUntilIdle()
            assertEquals(p2.instanceId, newlyDownloadedPayload?.instanceId)
            assertEquals("26.3", newlyDownloadedPayload?.versionId)

            // 3. Switch Minecraft versions repeatedly without invalidating selected profile
            controller.saveInstanceSettings(p2.instanceId, "Snapshot Lab", "1.20.4", "Internal-17", null, 3072, null, null)
            advanceUntilIdle()
            assertEquals(p2.instanceId, controller.uiState.value.selectedInstanceId)

            controller.saveInstanceSettings(p2.instanceId, "Snapshot Lab", "1.21.4", "External-21", null, 3072, null, null)
            advanceUntilIdle()
            controller.saveInstanceSettings(p2.instanceId, "Snapshot Lab", "26.3", "External-21", null, 3072, null, null)
            advanceUntilIdle()

            assertEquals(p2.instanceId, controller.uiState.value.selectedInstanceId)
            assertEquals("26.3", controller.uiState.value.featuredInstance?.versionId)

            var relaunchedPayload: AetherLaunchPayload? = null
            controller.prepareInstanceLaunch(p2.instanceId) { _, payload ->
                relaunchedPayload = payload
            }
            advanceUntilIdle()
            assertEquals(p2.instanceId, relaunchedPayload?.instanceId)
            assertEquals("26.3", relaunchedPayload?.versionId)
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun `persists and restores last valid profile across launcher restarts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope1 = CoroutineScope(dispatcher + SupervisorJob())
        val scope2 = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val (instanceManager1, settingsRepo1, versionManager1) = buildStack(dispatcher)
            seedDefaults(instanceManager1, "1.21.4" to 21, "26.3" to 21)

            val firstProfile = instanceManager1.createInstance("First", "1.21.4", "External-21", null)
            val secondProfile = instanceManager1.createInstance("Second", "26.3", "External-21", null)

            val controller1 = buildController(instanceManager1, settingsRepo1, versionManager1, dispatcher, scope1)
            controller1.startObserving()
            advanceUntilIdle()

            // Explicitly select firstProfile (even though secondProfile has newer timestamp)
            controller1.selectFeaturedInstance(firstProfile.instanceId)
            controller1.selectVersion("1.21.4")
            advanceUntilIdle()
            assertEquals(firstProfile.instanceId, controller1.uiState.value.selectedInstanceId)
            scope1.cancel()

            // Simulate app restart with a fresh controller bound to the same persistent DB/settings
            val (instanceManager2, settingsRepo2, versionManager2) = buildStack(dispatcher)
            val controller2 = buildController(instanceManager2, settingsRepo2, versionManager2, dispatcher, scope2)
            controller2.startObserving()
            advanceUntilIdle()

            assertEquals(firstProfile.instanceId, controller2.uiState.value.selectedInstanceId)
            assertEquals("1.21.4", controller2.uiState.value.selectedVersionId)

            var restoredPayload: AetherLaunchPayload? = null
            controller2.prepareInstanceLaunch(controller2.uiState.value.selectedInstanceId!!) { _, payload ->
                restoredPayload = payload
            }
            advanceUntilIdle()
            assertEquals(firstProfile.instanceId, restoredPayload?.instanceId)
            assertEquals("1.21.4", restoredPayload?.versionId)
            assertNotNull(secondProfile.instanceId)
        } finally {
            scope1.cancel()
            scope2.cancel()
        }
    }

    @Test
    fun `prevents profile deletion during launch preparation and recovers or errors cleanly when missing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val (instanceManager, settingsRepo, versionManager) = buildStack(dispatcher)
            seedDefaults(instanceManager, "26.3" to 21, "1.21.4" to 21)

            val profileA = instanceManager.createInstance("Main 26.3", "26.3", "External-21", null)
            val markerA = File(profileA.gameDirPath, "saves/world.txt").apply {
                parentFile?.mkdirs()
                writeText("important-save")
            }
            val profileB = instanceManager.createInstance("Backup 1.21.4", "1.21.4", "External-21", null)

            val controller = buildController(instanceManager, settingsRepo, versionManager, dispatcher, controllerScope)
            controller.startObserving()
            advanceUntilIdle()

            // 1. Deleting a profile while launch prep is active must be rejected without touching DB or files
            assertTrue(instanceManager.acquireLaunchPreparation(profileA.instanceId))
            assertFalse(instanceManager.deleteInstance(profileA.instanceId, deleteAetherOwnedFiles = true))
            controller.deleteInstance(profileA.instanceId, deleteAetherOwnedFiles = true)
            advanceUntilIdle()
            assertNotNull(instanceManager.getInstance(profileA.instanceId))
            assertTrue(markerA.isFile)
            assertTrue(controller.uiState.value.statusBanner?.contains("launch is being prepared", ignoreCase = true) == true)
            instanceManager.releaseLaunchPreparation(profileA.instanceId)

            // 2. If selected profile was deleted outside launch prep while another valid profile exists,
            // automatically select the other valid profile and continue safely
            controller.selectFeaturedInstance(profileA.instanceId)
            advanceUntilIdle()
            assertTrue(instanceManager.deleteInstance(profileA.instanceId, deleteAetherOwnedFiles = false))
            assertTrue(markerA.isFile)

            var fallbackPayload: AetherLaunchPayload? = null
            controller.prepareInstanceLaunch(profileA.instanceId) { _, payload ->
                fallbackPayload = payload
            }
            advanceUntilIdle()
            assertEquals(profileB.instanceId, fallbackPayload?.instanceId)
            assertEquals(profileB.instanceId, controller.uiState.value.selectedInstanceId)

            // 3. When all profiles are removed, show friendly error asking user to create or select a profile
            assertTrue(instanceManager.deleteInstance(profileB.instanceId, deleteAetherOwnedFiles = false))
            advanceUntilIdle()
            var zeroProfilePayload: AetherLaunchPayload? = null
            controller.prepareInstanceLaunch(profileB.instanceId) { _, payload ->
                zeroProfilePayload = payload
            }
            advanceUntilIdle()
            assertNull(zeroProfilePayload)
            val banner = controller.uiState.value.statusBanner.orEmpty()
            assertTrue(banner.contains("create or select a profile", ignoreCase = true))
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun `rapidly switching profiles during launch preparation only launches the latest selected profile`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val (instanceManager, settingsRepo, versionManager) = buildStack(dispatcher)
            seedDefaults(instanceManager, "1.21.4" to 21, "26.3" to 21)

            val profileA = instanceManager.createInstance("Profile A", "1.21.4", "External-21", null)
            val profileB = instanceManager.createInstance("Profile B", "26.3", "External-21", null)

            val controller = buildController(instanceManager, settingsRepo, versionManager, dispatcher, controllerScope)
            controller.startObserving()
            advanceUntilIdle()

            val launchedIds = mutableListOf<String>()
            val latestCompleted = CompletableDeferred<Unit>()

            controller.prepareInstanceLaunch(profileA.instanceId) { _, payload ->
                launchedIds.add(payload.instanceId)
            }
            controller.selectFeaturedInstance(profileB.instanceId)
            controller.prepareInstanceLaunch(profileB.instanceId) { _, payload ->
                launchedIds.add(payload.instanceId)
                latestCompleted.complete(Unit)
            }

            advanceUntilIdle()
            assertTrue(latestCompleted.isCompleted)
            assertEquals(listOf(profileB.instanceId), launchedIds)
            assertEquals(profileB.instanceId, controller.uiState.value.selectedInstanceId)
        } finally {
            controllerScope.cancel()
        }
    }
}
