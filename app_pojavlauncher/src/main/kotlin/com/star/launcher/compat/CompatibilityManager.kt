package com.star.launcher.compat

import com.star.launcher.data.db.entity.CompatibilityResultEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.ModLoaderType
import java.util.UUID

data class CompatibilityTarget(
    val instanceId: String?,
    val versionId: String,
    val requiredJavaMajor: Int,
    val rendererId: String,
    val modLoaderType: ModLoaderType,
    val selectedRuntimeId: String?,
    val selectedRuntimeJavaMajor: Int?,
    val persistedVersionId: String? = versionId
)

data class TieredCompatibilityReport(
    val detected: CompatibilityResultEntity,
    val theoretical: CompatibilityResultEntity,
    val tested: CompatibilityResultEntity? = null,
    val verifiedRuntime: CompatibilityResultEntity? = null
)

interface TestedMatrixProvider {
    fun findTestedEntry(
        capabilities: DeviceCapabilitiesSnapshot,
        target: CompatibilityTarget
    ): CompatibilityResultEntity?
}

/**
 * Default read-only provider when no verified QA matrix entry covers this capability tuple.
 * Never invents tested claims.
 */
object EmptyTestedMatrixProvider : TestedMatrixProvider {
    override fun findTestedEntry(
        capabilities: DeviceCapabilitiesSnapshot,
        target: CompatibilityTarget
    ): CompatibilityResultEntity? = null
}

/**
 * Evaluates device capabilities against Minecraft/runtime/renderer targets while strictly separating:
 * DETECTED capability vs THEORETICAL compatibility vs TESTED matrix vs VERIFIED_RUNTIME outcomes.
 */
class CompatibilityEvaluator(
    private val testedMatrixProvider: TestedMatrixProvider = EmptyTestedMatrixProvider,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    fun evaluate(
        capabilities: DeviceCapabilitiesSnapshot,
        target: CompatibilityTarget,
        priorVerifiedRuntime: CompatibilityResultEntity? = null
    ): TieredCompatibilityReport {
        val now = clockMillis()

        val detected = CompatibilityResultEntity(
            resultId = idGenerator(),
            deviceFingerprint = capabilities.deviceFingerprint,
            instanceId = target.instanceId,
            versionId = target.persistedVersionId,
            rendererId = target.rendererId,
            runtimeId = target.selectedRuntimeId,
            tier = CompatibilityTier.DETECTED,
            status = CompatibilityStatus.PASS,
            summaryReason = "ABI=${capabilities.cpuAbiPrimary}, " +
                "64bit=${capabilities.is64Bit}, " +
                "API=${capabilities.androidApiLevel}, " +
                "GPU=${capabilities.gpuFamily}/${capabilities.gpuRenderer}, " +
                "GLES=${capabilities.glesMajorVersion}, " +
                "Vulkan=${capabilities.vulkanVersion ?: "none"}",
            detailsJson = "{\"ramMb\":${capabilities.totalRamMb},\"kgsl\":${capabilities.hasAdrenoKgslNode}}",
            evaluatedAt = now
        )

        val theoretical = evaluateTheoretical(capabilities, target, now)
        val tested = testedMatrixProvider.findTestedEntry(capabilities, target)

        // Only surface verified runtime evidence that matches this exact tier & device fingerprint.
        val verified = priorVerifiedRuntime?.takeIf {
            it.tier == CompatibilityTier.VERIFIED_RUNTIME &&
                it.deviceFingerprint == capabilities.deviceFingerprint
        }

        return TieredCompatibilityReport(
            detected = detected,
            theoretical = theoretical,
            tested = tested,
            verifiedRuntime = verified
        )
    }

    private fun evaluateTheoretical(
        capabilities: DeviceCapabilitiesSnapshot,
        target: CompatibilityTarget,
        evaluatedAt: Long
    ): CompatibilityResultEntity {
        val renderer = target.rendererId.lowercase()
        val is32BitX86 = capabilities.cpuAbiPrimary == "x86" && !capabilities.is64Bit

        val (status, reason) = when {
            is32BitX86 && target.requiredJavaMajor >= 21 ->
                CompatibilityStatus.UNSUPPORTED to
                    "32-bit x86 cannot provision JRE ${target.requiredJavaMajor} required by ${target.versionId}"

            target.selectedRuntimeJavaMajor != null &&
                target.selectedRuntimeJavaMajor > 0 &&
                target.selectedRuntimeJavaMajor < target.requiredJavaMajor ->
                CompatibilityStatus.FAIL to
                    "Selected Java ${target.selectedRuntimeJavaMajor} is below required Java ${target.requiredJavaMajor}"

            (renderer.contains("vulkan") || renderer.contains("zink")) && !capabilities.hasVulkan ->
                CompatibilityStatus.UNSUPPORTED to
                    "Renderer ${target.rendererId} requires Vulkan 1.1+, but device reports no Vulkan hardware support"

            (renderer.contains("vulkan") || renderer.contains("zink")) &&
                capabilities.vulkanVersion != null &&
                !isVulkanVersionAtLeast(capabilities.vulkanVersion, 1, 1) ->
                CompatibilityStatus.UNSUPPORTED to
                    "Renderer ${target.rendererId} requires Vulkan 1.1+, detected Vulkan ${capabilities.vulkanVersion}"

            (renderer.contains("vulkan") || renderer.contains("zink")) &&
                capabilities.gpuFamily == GpuFamily.ADRENO &&
                !capabilities.is64Bit ->
                CompatibilityStatus.WARN to
                    "Adreno Turnip driver targets arm64-v8a; 32-bit ABI falls back to system Vulkan if available"

            (renderer.contains("vulkan") || renderer.contains("zink")) &&
                capabilities.gpuFamily != GpuFamily.ADRENO ->
                CompatibilityStatus.EXPERIMENTAL to
                    "${capabilities.gpuFamily} (${capabilities.gpuRenderer}) Vulkan/Zink relies on OEM system Vulkan ${capabilities.vulkanVersion ?: "1.1+"} driver (Turnip unavailable); MobileGlues is recommended for stability"

            (renderer.contains("ltw") || renderer.contains("mobileglues") || renderer.contains("opengles3")) &&
                capabilities.glesMajorVersion < 3 ->
                CompatibilityStatus.UNSUPPORTED to
                    "Renderer ${target.rendererId} requires OpenGL ES 3.0+, detected ES ${capabilities.glesMajorVersion}"

            !capabilities.is64Bit && renderer.contains("mobileglues") ->
                CompatibilityStatus.WARN to
                    "32-bit address space (~3 GB limit) requires conservative Java heap allocation with MobileGlues"

            target.selectedRuntimeJavaMajor == null ->
                CompatibilityStatus.WARN to
                    "No runtime selected yet; requires JRE ${target.requiredJavaMajor}+ on ${capabilities.cpuAbiPrimary}"

            else ->
                CompatibilityStatus.PASS to
                    "Capabilities satisfy theoretical requirements for ${target.versionId} on ${target.rendererId}"
        }

        return CompatibilityResultEntity(
            resultId = idGenerator(),
            deviceFingerprint = capabilities.deviceFingerprint,
            instanceId = target.instanceId,
            versionId = target.persistedVersionId,
            rendererId = target.rendererId,
            runtimeId = target.selectedRuntimeId,
            tier = CompatibilityTier.THEORETICAL,
            status = status,
            summaryReason = reason,
            detailsJson = "{\"modLoader\":\"${target.modLoaderType.name}\",\"requiredJava\":${target.requiredJavaMajor}}",
            evaluatedAt = evaluatedAt
        )
    }

    companion object {
        fun isVulkanVersionAtLeast(vulkanVersion: String?, minMajor: Int, minMinor: Int): Boolean {
            if (vulkanVersion.isNullOrBlank()) return false
            val parts = vulkanVersion.trim().split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return major > minMajor || (major == minMajor && minor >= minMinor)
        }
    }
}
