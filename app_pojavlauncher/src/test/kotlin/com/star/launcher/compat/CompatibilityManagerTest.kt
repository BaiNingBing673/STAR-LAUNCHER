package com.star.launcher.compat

import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.ModLoaderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompatibilityManagerTest {

    private fun arm64AdrenoSnapshot() = DeviceCapabilitiesSnapshot(
        deviceFingerprint = "fp-adreno",
        cpuAbiPrimary = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        is64Bit = true,
        androidApiLevel = 34,
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno (TM) 740",
        glVersion = "OpenGL ES 3.2",
        glesMajorVersion = 3,
        gpuFamily = GpuFamily.ADRENO,
        hasVulkan = true,
        vulkanVersion = "1.3.128",
        hasAdrenoKgslNode = true,
        totalRamMb = 12288L,
        probedAt = 1000L
    )

    private fun maliSnapshot(glesMajor: Int = 3, hasVulkan: Boolean = true) = DeviceCapabilitiesSnapshot(
        deviceFingerprint = "fp-mali",
        cpuAbiPrimary = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a"),
        is64Bit = true,
        androidApiLevel = 33,
        gpuVendor = "ARM",
        gpuRenderer = "Mali-G78",
        glVersion = "OpenGL ES $glesMajor.0",
        glesMajorVersion = glesMajor,
        gpuFamily = GpuFamily.MALI,
        hasVulkan = hasVulkan,
        vulkanVersion = if (hasVulkan) "1.1.0" else null,
        hasAdrenoKgslNode = false,
        totalRamMb = 8192L,
        probedAt = 1000L
    )

    private fun x86Snapshot() = DeviceCapabilitiesSnapshot(
        deviceFingerprint = "fp-x86",
        cpuAbiPrimary = "x86",
        supportedAbis = listOf("x86"),
        is64Bit = false,
        androidApiLevel = 30,
        gpuVendor = "Intel",
        gpuRenderer = "Mesa Intel",
        glVersion = "OpenGL ES 3.0",
        glesMajorVersion = 3,
        gpuFamily = GpuFamily.UNKNOWN,
        hasVulkan = false,
        vulkanVersion = null,
        hasAdrenoKgslNode = false,
        totalRamMb = 4096L,
        probedAt = 1000L
    )

    @Test
    fun `arm64 adreno with java 21 and kopper zink evaluates theoretical pass and leaves tested null`() {
        val evaluator = CompatibilityEvaluator(clockMillis = { 2000L })
        val report = evaluator.evaluate(
            capabilities = arm64AdrenoSnapshot(),
            target = CompatibilityTarget(
                instanceId = "inst-1",
                versionId = "1.21.4",
                requiredJavaMajor = 21,
                rendererId = "opengles3_desktopgl_zink_kopper",
                modLoaderType = ModLoaderType.FABRIC,
                selectedRuntimeId = "External-21",
                selectedRuntimeJavaMajor = 21
            )
        )

        assertEquals(CompatibilityTier.DETECTED, report.detected.tier)
        assertEquals(CompatibilityStatus.PASS, report.detected.status)
        assertEquals(CompatibilityTier.THEORETICAL, report.theoretical.tier)
        assertEquals(CompatibilityStatus.PASS, report.theoretical.status)
        assertNull(report.tested)
        assertNull(report.verifiedRuntime)
    }

    @Test
    fun `mali with zink reports experimental instead of guaranteed pass`() {
        val evaluator = CompatibilityEvaluator(clockMillis = { 2000L })
        val report = evaluator.evaluate(
            capabilities = maliSnapshot(glesMajor = 3, hasVulkan = true),
            target = CompatibilityTarget(
                instanceId = "inst-1",
                versionId = "1.20.4",
                requiredJavaMajor = 17,
                rendererId = "opengles3_desktopgl_zink_kopper",
                modLoaderType = ModLoaderType.VANILLA,
                selectedRuntimeId = "Internal-17",
                selectedRuntimeJavaMajor = 17
            )
        )

        assertEquals(CompatibilityStatus.EXPERIMENTAL, report.theoretical.status)
    }

    @Test
    fun `x86 32bit with java 21 version is marked unsupported`() {
        val evaluator = CompatibilityEvaluator(clockMillis = { 2000L })
        val report = evaluator.evaluate(
            capabilities = x86Snapshot(),
            target = CompatibilityTarget(
                instanceId = "inst-x86",
                versionId = "1.21.4",
                requiredJavaMajor = 21,
                rendererId = "opengles2_holy_gl4es",
                modLoaderType = ModLoaderType.VANILLA,
                selectedRuntimeId = null,
                selectedRuntimeJavaMajor = null
            )
        )

        assertEquals(CompatibilityStatus.UNSUPPORTED, report.theoretical.status)
    }

    @Test
    fun `gles2 device rejects mobileglues`() {
        val evaluator = CompatibilityEvaluator(clockMillis = { 2000L })
        val report = evaluator.evaluate(
            capabilities = maliSnapshot(glesMajor = 2, hasVulkan = false),
            target = CompatibilityTarget(
                instanceId = "inst-old",
                versionId = "1.16.5",
                requiredJavaMajor = 8,
                rendererId = "opengles_mobileglues",
                modLoaderType = ModLoaderType.VANILLA,
                selectedRuntimeId = "Internal",
                selectedRuntimeJavaMajor = 8
            )
        )

        assertEquals(CompatibilityStatus.UNSUPPORTED, report.theoretical.status)
    }

    @Test
    fun `classifyGpuFamily identifies vendors without relying on device models`() {
        assertEquals(GpuFamily.ADRENO, classifyGpuFamily("Qualcomm", "Adreno (TM) 750"))
        assertEquals(GpuFamily.MALI, classifyGpuFamily("ARM", "Mali-G715-Immortalis"))
        assertEquals(GpuFamily.XCLIPSE, classifyGpuFamily("Samsung", "Samsung Xclipse 940"))
        assertEquals(GpuFamily.POWERVR, classifyGpuFamily("Imagination Technologies", "PowerVR Rogue"))
    }
}
