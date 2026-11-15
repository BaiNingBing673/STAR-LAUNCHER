package com.star.launcher.engine.adapter

import net.kdt.pojavlaunch.multirt.Runtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAdapterMappingTest {

    @Test
    fun `mapUpstreamRuntime preserves name version and architecture`() {
        val upstream = Runtime("Internal")
        val mapped = mapUpstreamRuntime(upstream)

        assertEquals("Internal", mapped.runtimeId)
        assertEquals(0, mapped.majorJavaVersion)
        assertEquals("unknown", mapped.arch)
        assertTrue(mapped.isInternal)
    }

    @Test
    fun `mapRendererSpec detects vulkan and gles3 requirements from renderer ids`() {
        val zink = mapRendererSpec("opengles3_desktopgl_zink_kopper", "Zink")
        assertTrue(zink.requiresVulkan)
        assertTrue(zink.requiresGles3)

        val mobileGlues = mapRendererSpec("opengles_mobileglues", "MobileGlues")
        assertFalse(mobileGlues.requiresVulkan)
        assertTrue(mobileGlues.requiresGles3)

        val holy = mapRendererSpec("opengles2_holy_gl4es", "Holy GL4ES")
        assertFalse(holy.requiresVulkan)
        assertFalse(holy.requiresGles3)
    }

    @Test
    fun `validateLaunchSpec flags insufficient ram and unknown java version`() {
        val adapter = PojavNativeEngineAdapter { null }
        val invalidSpec = EngineLaunchSpec(
            instanceId = "inst-1",
            versionId = "1.21.4",
            gameDirPath = "/sdcard/games/inst-1",
            runtime = RuntimeDescriptor("BrokenRT", "Broken", 0, "aarch64", null, false),
            renderer = RendererSpec("opengles_mobileglues", "MobileGlues", false, true),
            inputProfile = InputProfileSpec(null),
            allocatedRamMb = 128
        )

        val issues = adapter.validateLaunchSpec(invalidSpec)
        assertEquals(2, issues.size)
        assertTrue(issues.any { it.contains("allocatedRamMb") })
        assertTrue(issues.any { it.contains("Java major version") })
        assertEquals(PojavNativeEngineAdapter.REQUIRED_CORE_LIBRARIES, adapter.verifyEngineLibrariesPresent())
    }
}
