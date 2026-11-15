package com.star.launcher.runtime

import android.os.Build
import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.compat.GpuFamily
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.engine.adapter.PojavNativeEngineAdapter
import com.star.launcher.engine.adapter.PojavRuntimeFacade
import com.star.launcher.instance.InstanceManager
import com.star.launcher.instance.LaunchPreparationResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.NewJREUtil
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeSelectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var previousMultirtHome: String? = null
    private var originalAbis64: Array<String>? = null
    private var originalAbis32: Array<String>? = null

    @Before
    fun setUp() {
        previousMultirtHome = Tools.MULTIRT_HOME
        originalAbis64 = Build.SUPPORTED_64_BIT_ABIS
        originalAbis32 = Build.SUPPORTED_32_BIT_ABIS

        val runtimesDir = tempFolder.newFolder("runtimes")
        Tools.MULTIRT_HOME = runtimesDir.absolutePath
        MultiRTUtils.clearCache()
        setDeviceAbis(arrayOf("arm64-v8a"), arrayOf("armeabi-v7a"))
    }

    @After
    fun tearDown() {
        MultiRTUtils.clearCache()
        Tools.MULTIRT_HOME = previousMultirtHome
        originalAbis64?.let { a64 ->
            originalAbis32?.let { a32 ->
                setDeviceAbis(a64, a32)
            }
        }
    }

    private fun setDeviceAbis(abis64: Array<String>, abis32: Array<String>) {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", abis64)
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_32_BIT_ABIS", abis32)
    }

    private fun createRuntime(
        name: String,
        javaVersion: String,
        osArch: String,
        withLibJvm: Boolean = true,
        libSubdir: String = "lib/server/libjvm.so"
    ) {
        val dir = File(Tools.MULTIRT_HOME, name).apply { mkdirs() }
        File(dir, "release").writeText(
            """
            JAVA_VERSION="$javaVersion"
            OS_ARCH="$osArch"
            """.trimIndent()
        )
        if (withLibJvm) {
            val libFile = File(dir, libSubdir)
            libFile.parentFile?.mkdirs()
            libFile.writeText("stub-elf")
        }
    }

    @Test
    fun `missing runtime throws descriptive error instead of opaque autopick crash`() {
        val selection = MultiRTUtils.selectRuntimeForLaunch(null, 21)
        assertNull(selection.selectedRuntime)
        assertTrue(selection.errorMessage.contains("Java 21+"))
        assertTrue(selection.errorMessage.contains("arm64"))

        val profile = MinecraftProfile()
        try {
            Tools.pickRuntime(profile, 21)
            fail("Expected RuntimeException when no runtimes are installed")
        } catch (ex: RuntimeException) {
            val msg = ex.message.orEmpty()
            assertTrue("Expected descriptive failure, got: $msg", msg.contains("No compatible Java 21+"))
            assertTrue("Expected Settings hint, got: $msg", msg.contains("Settings -> Java Runtimes"))
        }
    }

    @Test
    fun `corrupted runtime missing libjvm and loose files are rejected`() {
        File(Tools.MULTIRT_HOME, "stray-file.txt").writeText("ignored")
        createRuntime(
            name = "External-21-broken",
            javaVersion = "21.0.5",
            osArch = "aarch64",
            withLibJvm = false
        )

        val installedCompatible = MultiRTUtils.getInstalledCompatibleRuntimes()
        assertTrue(installedCompatible.isEmpty())

        val report = MultiRTUtils.inspectRuntime("External-21-broken", 21, true)
        assertEquals(MultiRTUtils.RuntimeValidationStatus.CORRUPTED, report.status)
        assertTrue(report.reason.contains("corrupted", ignoreCase = true))

        val facade = PojavRuntimeFacade()
        assertTrue(facade.listInstalledRuntimes().isEmpty())
        assertNull(facade.resolveRuntime("External-21-broken"))
    }

    @Test
    fun `wrong architecture runtime is never silently picked`() {
        createRuntime(
            name = "External-21-x86",
            javaVersion = "21.0.5",
            osArch = "i386",
            withLibJvm = true,
            libSubdir = "lib/i386/server/libjvm.so"
        )

        val selection = MultiRTUtils.selectRuntimeForLaunch("External-21-x86", 21)
        assertNull(selection.selectedRuntime)
        assertEquals(1, selection.reports.size)
        assertEquals(
            MultiRTUtils.RuntimeValidationStatus.ARCHITECTURE_MISMATCH,
            selection.reports.first().status
        )
        assertNull(MultiRTUtils.getExactJreName(21))
        assertNull(MultiRTUtils.getNearestJreName(17))
    }

    @Test
    fun `selects nearest compatible runtime when preferred runtime java version is too low`() {
        createRuntime(
            name = "External-8",
            javaVersion = "1.8.0_412",
            osArch = "aarch64",
            withLibJvm = true,
            libSubdir = "lib/aarch64/server/libjvm.so"
        )
        createRuntime(
            name = "External-21",
            javaVersion = "21.0.5",
            osArch = "aarch64",
            withLibJvm = true
        )
        createRuntime(
            name = "External-25",
            javaVersion = "25.0.1",
            osArch = "aarch64",
            withLibJvm = true
        )

        val profile = MinecraftProfile().apply {
            javaDir = Tools.LAUNCHERPROFILES_RTPREFIX + "External-8"
        }

        val pickedFor17 = Tools.pickRuntime(profile, 17)
        assertEquals("External-21", pickedFor17)
        assertEquals(Tools.LAUNCHERPROFILES_RTPREFIX + "External-21", profile.javaDir)

        val pickedFor8 = Tools.pickRuntime(MinecraftProfile(), 8)
        assertEquals("External-8", pickedFor8)

        val facade = PojavRuntimeFacade()
        assertEquals(3, facade.listInstalledRuntimes().size)
        assertNotNull(facade.resolveRuntime("External-8"))
    }

    @Test
    fun `downloadable runtime resolution selects nearest supported external runtime for arch`() {
        setDeviceAbis(arrayOf("arm64-v8a"), arrayOf("armeabi-v7a"))
        assertEquals(NewJREUtil.ExternalRuntime.JRE_8, NewJREUtil.findDownloadableRuntime(8))
        assertEquals(NewJREUtil.ExternalRuntime.JRE_17, NewJREUtil.findDownloadableRuntime(16))
        assertEquals(NewJREUtil.ExternalRuntime.JRE_25, NewJREUtil.findDownloadableRuntime(22))

        setDeviceAbis(emptyArray(), arrayOf("x86"))
        assertEquals(Architecture.ARCH_X86, Architecture.getDeviceArchitecture())
        assertEquals(NewJREUtil.ExternalRuntime.JRE_17, NewJREUtil.findDownloadableRuntime(17))
        assertNull(NewJREUtil.findDownloadableRuntime(21))
    }

    @Test
    fun `InstanceManager blocks launch when no compatible runtime exists in Room registry`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val gameHome = tempFolder.newFolder("game_home")
            val dataDir = tempFolder.newFolder("data_dir")
            val manager = InstanceManager(
                gameInstanceDao = db.gameInstanceDao(),
                gameVersionDao = db.gameVersionDao(),
                runtimeRegistryDao = db.runtimeRegistryDao(),
                rendererProfileDao = db.rendererProfileDao(),
                deviceInfoDao = db.deviceInfoDao(),
                storageManager = AetherStorageManager({ gameHome }, { dataDir }),
                capabilityProbe = object : DeviceCapabilityProbe {
                    override fun probe(): DeviceCapabilitiesSnapshot = DeviceCapabilitiesSnapshot(
                        deviceFingerprint = "fp-test",
                        cpuAbiPrimary = "arm64-v8a",
                        supportedAbis = listOf("arm64-v8a"),
                        is64Bit = true,
                        androidApiLevel = 34,
                        gpuVendor = "Qualcomm",
                        gpuRenderer = "Adreno",
                        gpuFamily = GpuFamily.ADRENO,
                        glVersion = "OpenGL ES 3.2",
                        glesMajorVersion = 3,
                        hasVulkan = true,
                        vulkanVersion = "1.3",
                        hasAdrenoKgslNode = true,
                        totalRamMb = 8192L,
                        probedAt = 100L
                    )
                },
                compatibilityEvaluator = CompatibilityEvaluator(),
                nativeEngineAdapter = PojavNativeEngineAdapter { null },
                diagnosticsManager = DiagnosticsManager(db.diagnosticSessionDao(), db.compatibilityResultDao())
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
                    manifestPath = "/versions/1.21.4/1.21.4.json",
                    isInstalled = true,
                    releaseTimeEpochMs = 1L
                )
            )
            val inst = manager.createInstance(
                name = "Vanilla 1.21.4",
                versionId = "1.21.4",
                runtimeId = null,
                rendererProfileId = null,
                allocatedRamMb = 2048
            )
            val result = manager.prepareLaunch(inst.instanceId)
            assertTrue("Expected Blocked when no runtime installed, got $result", result is LaunchPreparationResult.Blocked)
            val blocked = result as LaunchPreparationResult.Blocked
            assertTrue(blocked.reasons.any { it.contains("Java 21+") && it.contains("Java Runtimes") })

            manager.syncRuntimesAndRenderers(
                runtimes = listOf(
                    com.star.launcher.engine.adapter.RuntimeDescriptor(
                        runtimeId = "External-21",
                        displayName = "External-21",
                        majorJavaVersion = 21,
                        arch = "aarch64",
                        versionString = "21.0.5",
                        isInternal = false
                    )
                ),
                renderers = listOf(
                    com.star.launcher.engine.adapter.RendererSpec(
                        rendererId = "opengles_mobileglues",
                        displayName = "MobileGlues",
                        requiresVulkan = false,
                        requiresGles3 = true
                    )
                )
            )
            val ready = manager.prepareLaunch(inst.instanceId)
            assertTrue("Expected Ready once compatible JRE 21 is registered, got $ready", ready is LaunchPreparationResult.Ready)
            assertEquals("External-21", (ready as LaunchPreparationResult.Ready).spec.runtime.runtimeId)
        } finally {
            db.close()
        }
    }
}
