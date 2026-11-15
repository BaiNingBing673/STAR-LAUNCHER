package com.star.launcher.compat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.diagnostics.CrashReportCoordinator
import com.star.launcher.diagnostics.StartupProfiler
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.utils.MCOptionUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OptimizationAndAdaptiveGraphicsTest {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = createTempDirectory("star-opt-test").toFile()
        Tools.DIR_DATA = File(tempDir, "data").apply { mkdirs() }.absolutePath
        Tools.DIR_GAME_HOME = File(tempDir, "game").apply { mkdirs() }.absolutePath
        Tools.DIR_GAME_NEW = File(tempDir, "game/.minecraft").apply { mkdirs() }.absolutePath
        LauncherPreferences.DEFAULT_PREF = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE).apply {
            edit().clear().commit()
        }
        StartupProfiler.resetForTest()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        StartupProfiler.resetForTest()
    }

    @Test
    fun `fps management writes Unlimited as 260 and explicit limits into options txt`() {
        val gameDir = File(tempDir, "inst").apply { mkdirs() }
        MCOptionUtils.load(gameDir.absolutePath)

        MCOptionUtils.applyFpsLimit(0, false)
        MCOptionUtils.save()
        assertEquals("260", MCOptionUtils.get("maxFps"))
        assertEquals("false", MCOptionUtils.get("enableVsync"))

        MCOptionUtils.applyFpsLimit(90, false)
        MCOptionUtils.save()
        assertEquals("90", MCOptionUtils.get("maxFps"))
        assertEquals("false", MCOptionUtils.get("enableVsync"))

        MCOptionUtils.applyFpsLimit(144, true)
        MCOptionUtils.save()
        val persisted = File(gameDir, "options.txt").readText()
        assertTrue(persisted.contains("maxFps:144"))
        assertTrue(persisted.contains("enableVsync:true"))
        assertEquals(0, LauncherPreferences.normalizeFpsLimit(0))
        assertEquals(260, LauncherPreferences.normalizeFpsLimit(999))
        assertEquals(10, LauncherPreferences.normalizeFpsLimit(3))
    }

    @Test
    fun `adaptive graphics detects low-end hardware and recommends conservative settings while avoiding over-allocation on flagship`() {
        val lowEnd = DeviceCapabilitiesSnapshot(
            deviceFingerprint = "low",
            cpuAbiPrimary = "armeabi-v7a",
            supportedAbis = listOf("armeabi-v7a"),
            is64Bit = false,
            androidApiLevel = 28,
            gpuVendor = "ARM",
            gpuRenderer = "Mali-T830",
            glVersion = "OpenGL ES 2",
            glesMajorVersion = 2,
            gpuFamily = GpuFamily.MALI,
            hasVulkan = false,
            vulkanVersion = null,
            hasAdrenoKgslNode = false,
            totalRamMb = 3072,
            probedAt = 1L,
            cpuCoreCount = 4,
            cpuMaxFreqMhz = 1500,
            availableRamMb = 900
        )
        val lowRec = AdaptiveGraphicsManager.recommend(lowEnd)
        assertEquals(GraphicsPreset.ULTRA_LOW, lowRec.preset)
        assertTrue(lowRec.lowEndCompatibilityMode)
        assertTrue(lowRec.recommendedRamMb <= 1536)

        val flagship = DeviceCapabilitiesSnapshot(
            deviceFingerprint = "flag",
            cpuAbiPrimary = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            is64Bit = true,
            androidApiLevel = 35,
            gpuVendor = "Qualcomm",
            gpuRenderer = "Adreno 735",
            glVersion = "OpenGL ES 3",
            glesMajorVersion = 3,
            gpuFamily = GpuFamily.ADRENO,
            hasVulkan = true,
            vulkanVersion = "1.3.277",
            hasAdrenoKgslNode = true,
            totalRamMb = 12288,
            probedAt = 2L,
            cpuCoreCount = 8,
            cpuMaxFreqMhz = 3000,
            availableRamMb = 7000
        )
        val flagRec = AdaptiveGraphicsManager.recommend(flagship)
        assertEquals(GraphicsPreset.QUALITY, flagRec.preset)
        assertFalse(flagRec.lowEndCompatibilityMode)
        assertTrue(flagRec.recommendedRamMb in 2048..4096)

        // First-launch auto-init writes defaults; second call must preserve user override!
        val appliedFirst = AdaptiveGraphicsManager.initializeDefaultsOnFirstLaunch(context, flagship)
        assertEquals(GraphicsPreset.QUALITY, appliedFirst?.preset)
        AdaptiveGraphicsManager.markCustomOverride(context)
        assertEquals(GraphicsPreset.CUSTOM, AdaptiveGraphicsManager.getCurrentPreset(context))
        assertEquals(null, AdaptiveGraphicsManager.initializeDefaultsOnFirstLaunch(context, lowEnd))
        assertEquals(GraphicsPreset.CUSTOM, AdaptiveGraphicsManager.getCurrentPreset(context))
    }

    @Test
    fun `adaptive presets write real minecraft options and crash coordinator redacts secrets`() {
        val gameDir = File(tempDir, "inst2").apply { mkdirs() }
        val spec = AdaptiveGraphicsManager.applyPreset(context, GraphicsPreset.ULTRA_LOW, gameDirPath = gameDir.absolutePath)
        val optionsText = File(gameDir, "options.txt").readText()
        assertTrue(optionsText.contains("renderDistance:4"))
        assertTrue(optionsText.contains("simulationDistance:4"))
        assertTrue(optionsText.contains("particles:2"))
        assertTrue(optionsText.contains("graphicsMode:0"))
        assertTrue(optionsText.contains("enableVsync:false"))
        assertEquals(65, spec.resolutionRatioPercent)

        val tStart = StartupProfiler.beginStage()
        StartupProfiler.endStage("unit_probe", tStart, "ok")
        assertTrue(StartupProfiler.snapshotSamples().any { it.stage == "unit_probe" })

        val secretException = IllegalStateException("Auth failed x-api-key=secret-cf-token-9999")
        val latest = CrashReportCoordinator.recordUnhandledCrash(context, Thread.currentThread(), secretException, false)
        assertTrue(latest.isFile)
        val body = latest.readText()
        assertFalse(body.contains("secret-cf-token-9999"))
        assertTrue(body.contains("unit_probe"))
        assertTrue(CrashReportCoordinator.listRecentCrashReports(context).isNotEmpty())
    }
}
