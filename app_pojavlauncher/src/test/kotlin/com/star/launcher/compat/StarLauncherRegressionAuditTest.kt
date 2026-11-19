package com.star.launcher.compat

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.CrossProcessSpoolBridge
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.download.DownloadManager
import com.star.launcher.engine.adapter.EngineLaunchSpec
import com.star.launcher.engine.adapter.NativeEngineAdapter
import com.star.launcher.engine.adapter.RendererFacade
import com.star.launcher.engine.adapter.RendererSpec
import com.star.launcher.instance.InstanceManager
import com.star.launcher.ui.dashboard.AetherDashboardController
import com.star.launcher.version.VersionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.GLInfoUtils
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarLauncherRegressionAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        val dataRoot = tempFolder.newFolder("data_root")
        Tools.DIR_DATA = dataRoot.absolutePath
        Tools.DIR_GAME_HOME = tempFolder.newFolder("game_home").absolutePath
        Tools.releaseRenderersCache()
    }

    @After
    fun tearDown() {
        db.close()
        Tools.releaseRenderersCache()
        GLInfoUtils.resetCacheForTesting()
    }

    private fun nubiaNeoMaliSnapshot(
        vulkanVersion: String? = "1.1.128",
        glesMajor: Int = 3
    ) = DeviceCapabilitiesSnapshot(
        deviceFingerprint = "fp-unisoc-mali-g57",
        cpuAbiPrimary = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        is64Bit = true,
        androidApiLevel = 34,
        gpuVendor = "ARM",
        gpuRenderer = "Mali-G57",
        glVersion = "OpenGL ES $glesMajor.2 v1.r38p1-01eac0",
        glesMajorVersion = glesMajor,
        gpuFamily = GpuFamily.MALI,
        hasVulkan = vulkanVersion != null,
        vulkanVersion = vulkanVersion,
        hasAdrenoKgslNode = false,
        totalRamMb = 8192L,
        probedAt = 1_000L,
        cpuCoreCount = 8,
        cpuMaxFreqMhz = 2700,
        cpuHardwareModel = "UMS9620",
        availableRamMb = 3600L,
        screenWidthPx = 1080,
        screenHeightPx = 2400,
        supportedRefreshRatesHz = listOf(60, 120)
    )

    @Test
    fun `per-version renderer persists across controller restarts and isolates multiple Minecraft versions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsRepo = AetherSettingsRepository(db.settingsDao())
        val storage = AetherStorageManager()
        val probe = object : DeviceCapabilityProbe {
            override fun probe(): DeviceCapabilitiesSnapshot = nubiaNeoMaliSnapshot()
        }
        val evaluator = CompatibilityEvaluator()
        val diagnostics = DiagnosticsManager(db.diagnosticSessionDao(), db.compatibilityResultDao())
        val nativeAdapter = object : NativeEngineAdapter {
            override fun verifyEngineLibrariesPresent(): List<String> = emptyList()
            override fun validateLaunchSpec(spec: EngineLaunchSpec): List<String> = emptyList()
        }
        val instanceManager = InstanceManager(
            gameInstanceDao = db.gameInstanceDao(),
            gameVersionDao = db.gameVersionDao(),
            runtimeRegistryDao = db.runtimeRegistryDao(),
            rendererProfileDao = db.rendererProfileDao(),
            deviceInfoDao = db.deviceInfoDao(),
            storageManager = storage,
            capabilityProbe = probe,
            compatibilityEvaluator = evaluator,
            nativeEngineAdapter = nativeAdapter,
            diagnosticsManager = diagnostics
        )
        val versionManager = VersionManager(db.gameVersionDao(), storage)
        val downloadManager = DownloadManager(db.downloadTaskDao(), storage)
        val spoolBridge = CrossProcessSpoolBridge(storage, diagnostics)
        val rendererFacade = object : RendererFacade {
            override fun listSupportedRenderers(): List<RendererSpec> = listOf(
                RendererSpec("opengles_mobileglues", "MobileGlues", requiresVulkan = false, requiresGles3 = true),
                RendererSpec("opengles2", "NG-GL4ES", requiresVulkan = false, requiresGles3 = false),
                RendererSpec("opengles3_desktopgl_zink_kopper", "Kopper-Zink", requiresVulkan = true, requiresGles3 = true)
            )
            override fun isRendererCompatible(rendererId: String): Boolean = true
        }

        // Seed renderer profiles into Room
        db.rendererProfileDao().upsert(
            RendererProfileEntity("profile_opengles_mobileglues", "opengles_mobileglues", "MobileGlues", "{}", false, false, true, 1L)
        )
        db.rendererProfileDao().upsert(
            RendererProfileEntity("profile_opengles2", "opengles2", "NG-GL4ES", "{}", false, false, false, 1L)
        )

        val scope1 = TestScope(dispatcher)
        val controller1 = AetherDashboardController(
            instanceManager = instanceManager,
            versionManager = versionManager,
            downloadManager = downloadManager,
            capabilityProbe = probe,
            compatibilityEvaluator = evaluator,
            nativeEngineAdapter = nativeAdapter,
            spoolBridge = spoolBridge,
            rendererFacade = rendererFacade,
            diagnosticsManager = diagnostics,
            settingsRepository = settingsRepo,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            scope = scope1
        )
        controller1.startObserving()
        advanceUntilIdle()

        // Save different renderers for two distinct Minecraft versions
        controller1.saveVersionRenderer("1.21.4", "profile_opengles_mobileglues")
        controller1.saveVersionRenderer("1.12.2", "profile_opengles2")
        advanceUntilIdle()

        assertEquals("profile_opengles_mobileglues", controller1.uiState.value.versionRendererProfiles["1.21.4"])
        assertEquals("profile_opengles2", controller1.uiState.value.versionRendererProfiles["1.12.2"])
        assertTrue(controller1.uiState.value.statusBanner.orEmpty().contains("Saved renderer"))

        // Reject invalid / unavailable renderer cleanly without corrupting saved value
        controller1.saveVersionRenderer("1.21.4", "profile_nonexistent_gpu")
        advanceUntilIdle()
        assertEquals("profile_opengles_mobileglues", controller1.uiState.value.versionRendererProfiles["1.21.4"])
        assertTrue(controller1.uiState.value.statusBanner.orEmpty().contains("unavailable", ignoreCase = true))

        // Reopen screen with a brand-new controller instance -> persisted renderers MUST restore!
        val scope2 = TestScope(dispatcher)
        val controller2 = AetherDashboardController(
            instanceManager = instanceManager,
            versionManager = versionManager,
            downloadManager = downloadManager,
            capabilityProbe = probe,
            compatibilityEvaluator = evaluator,
            nativeEngineAdapter = nativeAdapter,
            spoolBridge = spoolBridge,
            rendererFacade = rendererFacade,
            diagnosticsManager = diagnostics,
            settingsRepository = settingsRepo,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            scope = scope2
        )
        controller2.startObserving()
        advanceUntilIdle()

        assertEquals("profile_opengles_mobileglues", controller2.uiState.value.versionRendererProfiles["1.21.4"])
        assertEquals("profile_opengles2", controller2.uiState.value.versionRendererProfiles["1.12.2"])

        // Clearing 1.12.2 to Auto must NOT affect 1.21.4
        controller2.saveVersionRenderer("1.12.2", null)
        advanceUntilIdle()
        assertNull(controller2.uiState.value.versionRendererProfiles["1.12.2"])
        assertEquals("profile_opengles_mobileglues", controller2.uiState.value.versionRendererProfiles["1.21.4"])

        scope1.cancel()
        scope2.cancel()
    }

    @Test
    fun `GLInfoUtils safely parses Mali UNISOC and malformed GL version strings`() {
        assertEquals(3, GLInfoUtils.parseMajorGLVersion("OpenGL ES 3.2 v1.r38p1-01eac0.b38"))
        assertEquals(3, GLInfoUtils.parseMajorGLVersion("OpenGL ES 3.0"))
        assertEquals(2, GLInfoUtils.parseMajorGLVersion("OpenGL ES 2.0"))
        assertEquals(2, GLInfoUtils.parseMajorGLVersion(null))
        assertEquals(2, GLInfoUtils.parseMajorGLVersion(""))
        assertEquals(2, GLInfoUtils.parseMajorGLVersion("MalformedVendorDriver"))

        val maliInfo = GLInfoUtils.GLInfo("ARM", "Mali-G57", 3)
        assertTrue(maliInfo.isMali())
        assertFalse(maliInfo.isAdreno())

        val unknownInfo = GLInfoUtils.GLInfo(null, null, 2)
        assertFalse(unknownInfo.isAdreno())
        assertFalse(unknownInfo.isMali())
    }

    @Test
    fun `Nubia Neo ARM64 Mali-G57 compatibility checks select safe renderer and reject Vulkan 1_0 Zink`() {
        val evaluator = CompatibilityEvaluator()

        // Mali-G57 + MobileGlues on Android 14 arm64 -> PASS
        val mgReport = evaluator.evaluate(
            capabilities = nubiaNeoMaliSnapshot(vulkanVersion = "1.1.128", glesMajor = 3),
            target = CompatibilityTarget(
                instanceId = "inst-neo",
                versionId = "1.21.4",
                requiredJavaMajor = 21,
                rendererId = "opengles_mobileglues",
                modLoaderType = ModLoaderType.FABRIC,
                selectedRuntimeId = "External-21",
                selectedRuntimeJavaMajor = 21
            )
        )
        assertEquals(CompatibilityStatus.PASS, mgReport.theoretical.status)

        // Mali-G57 with Vulkan 1.0.x + Kopper-Zink -> UNSUPPORTED (never force unsupported Vulkan 1.0 Zink!)
        val vk10Report = evaluator.evaluate(
            capabilities = nubiaNeoMaliSnapshot(vulkanVersion = "1.0.65", glesMajor = 3),
            target = CompatibilityTarget(
                instanceId = "inst-neo",
                versionId = "1.21.4",
                requiredJavaMajor = 21,
                rendererId = "opengles3_desktopgl_zink_kopper",
                modLoaderType = ModLoaderType.VANILLA,
                selectedRuntimeId = "External-21",
                selectedRuntimeJavaMajor = 21
            )
        )
        assertEquals(CompatibilityStatus.UNSUPPORTED, vk10Report.theoretical.status)
        assertTrue(vk10Report.theoretical.summaryReason.contains("1.1+", ignoreCase = true))

        // Mali-G57 with Vulkan 1.1+ + Kopper-Zink -> EXPERIMENTAL (recommends MobileGlues, no Turnip)
        val vk11Report = evaluator.evaluate(
            capabilities = nubiaNeoMaliSnapshot(vulkanVersion = "1.1.128", glesMajor = 3),
            target = CompatibilityTarget(
                instanceId = "inst-neo",
                versionId = "1.21.4",
                requiredJavaMajor = 21,
                rendererId = "opengles3_desktopgl_zink_kopper",
                modLoaderType = ModLoaderType.VANILLA,
                selectedRuntimeId = "External-21",
                selectedRuntimeJavaMajor = 21
            )
        )
        assertEquals(CompatibilityStatus.EXPERIMENTAL, vk11Report.theoretical.status)

        // Adaptive recommendation on Nubia Neo (8GB RAM, 8-core arm64, Mali-G57)
        val rec = AdaptiveGraphicsManager.recommend(nubiaNeoMaliSnapshot())
        assertEquals("opengles_mobileglues", rec.recommendedRendererId)
        assertTrue(rec.recommendedRamMb in 1024..3072)
    }

    @Test
    fun `display scaling and compatible renderer list never return zero resolution or empty list`() {
        // Nubia Neo tall 2400x1080 display scaling & edge cases
        assertEquals(1920, Tools.getDisplayFriendlyRes(2400, 0.8f))
        assertEquals(864, Tools.getDisplayFriendlyRes(1080, 0.8f))
        assertEquals(64, Tools.getDisplayFriendlyRes(0, 1.0f))
        assertEquals(64, Tools.getDisplayFriendlyRes(-100, 0.5f))
        assertEquals(1080, Tools.getDisplayFriendlyRes(1080, Float.NaN))

        // Renderer list with empty native lib dir still returns guaranteed safe fallback
        val context = ApplicationProvider.getApplicationContext<Context>()
        val emptyNativeDir = tempFolder.newFolder("empty_native_libs")
        Tools.NATIVE_LIB_DIR = emptyNativeDir.absolutePath
        Tools.releaseRenderersCache()
        val list = Tools.getCompatibleRenderers(context)
        assertFalse("Compatible renderers list must never be empty", list.rendererIds.isEmpty())
        assertEquals("opengles_system_gles", list.rendererIds.first())
    }

    @Test
    fun `unified STAR launcher icon and notification icon resources resolve consistently`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotEquals(0, R.mipmap.ic_launcher)
        assertNotEquals(0, R.mipmap.ic_launcher_round)
        assertNotEquals(0, R.drawable.ic_pojav_full)
        assertNotEquals(0, R.drawable.notif_icon)
        assertNotEquals(0, R.drawable.ic_launcher_monochrome)

        assertNotNull(context.getDrawable(R.drawable.ic_pojav_full))
        assertNotNull(context.getDrawable(R.drawable.notif_icon))
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher))
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher_round))

        val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(R.mipmap.ic_launcher, appInfo.icon)
    }
}
