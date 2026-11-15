package com.star.launcher.compat

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.utils.MCOptionUtils

enum class GraphicsPreset(val id: String, val label: String, val summary: String) {
    ULTRA_LOW(
        id = "ultra_low",
        label = "Ultra Low",
        summary = "4 chunks, Fast graphics, minimal particles, 65% scale, low RAM footprint"
    ),
    PERFORMANCE(
        id = "performance",
        label = "Performance",
        summary = "6 chunks, Fast graphics, decreased particles, 80% scale, unlocked FPS"
    ),
    BALANCED(
        id = "balanced",
        label = "Balanced",
        summary = "10 chunks, Fancy graphics, normal entities, 90% scale, unlocked FPS"
    ),
    QUALITY(
        id = "quality",
        label = "Quality",
        summary = "14 chunks, Fancy graphics, full particles & mipmaps, 100% scale"
    ),
    CUSTOM(
        id = "custom",
        label = "Custom",
        summary = "Manual user overrides"
    )
}

/**
 * Real Minecraft-supported `options.txt` values + launcher runtime knobs for a preset.
 */
data class GraphicsPresetSpec(
    val preset: GraphicsPreset,
    val renderDistanceChunks: Int,
    val simulationDistanceChunks: Int,
    val particlesMode: Int, // MC options.txt: 0 = All, 1 = Decreased, 2 = Minimal
    val entityDistanceScaling: Float,
    val graphicsMode: Int, // MC options.txt: 0 = Fast, 1 = Fancy
    val smoothLightingAo: Int, // MC options.txt: 0 = Off, 1 = Min, 2 = Max
    val mipmapLevels: Int,
    val cloudsMode: String, // "false" / "fast" / "fancy"
    val entityShadows: Boolean,
    val biomeBlendRadius: Int,
    val resolutionRatioPercent: Int,
    val defaultFpsLimit: Int, // 0 = Unlimited (written as maxFps:260 in options.txt)
    val glslCacheMb: Int,
    val lowEndCompatibilityMode: Boolean
) {
    fun toMcOptionsMap(): Map<String, String> = linkedMapOf(
        "renderDistance" to renderDistanceChunks.toString(),
        "simulationDistance" to simulationDistanceChunks.toString(),
        "particles" to particlesMode.toString(),
        "entityDistanceScaling" to entityDistanceScaling.toString(),
        "graphicsMode" to graphicsMode.toString(),
        "ao" to smoothLightingAo.toString(),
        "mipmapLevels" to mipmapLevels.toString(),
        "clouds" to cloudsMode,
        "entityShadows" to entityShadows.toString(),
        "biomeBlendRadius" to biomeBlendRadius.toString()
    )
}

data class HardwareRecommendation(
    val preset: GraphicsPreset,
    val lowEndCompatibilityMode: Boolean,
    val recommendedRamMb: Int,
    val recommendedRendererId: String,
    val rationale: String
)

/**
 * Hardware-aware adaptive graphics configurator and Low-End Compatibility Mode controller.
 * Applies only real Minecraft `options.txt` configuration keys and real STAR launcher settings,
 * while preserving manual user overrides once customized.
 */
object AdaptiveGraphicsManager {

    const val PREF_KEY_PRESET = "adaptive_graphics_preset"
    const val PREF_KEY_INITIALIZED = "adaptive_graphics_initialized"
    const val PREF_KEY_LOW_END_MODE = "low_end_compat_mode"

    fun specForPreset(preset: GraphicsPreset): GraphicsPresetSpec = when (preset) {
        GraphicsPreset.ULTRA_LOW -> GraphicsPresetSpec(
            preset = GraphicsPreset.ULTRA_LOW,
            renderDistanceChunks = 4,
            simulationDistanceChunks = 4,
            particlesMode = 2,
            entityDistanceScaling = 0.5f,
            graphicsMode = 0,
            smoothLightingAo = 0,
            mipmapLevels = 0,
            cloudsMode = "false",
            entityShadows = false,
            biomeBlendRadius = 0,
            resolutionRatioPercent = 65,
            defaultFpsLimit = 60,
            glslCacheMb = 64,
            lowEndCompatibilityMode = true
        )
        GraphicsPreset.PERFORMANCE -> GraphicsPresetSpec(
            preset = GraphicsPreset.PERFORMANCE,
            renderDistanceChunks = 6,
            simulationDistanceChunks = 5,
            particlesMode = 1,
            entityDistanceScaling = 0.75f,
            graphicsMode = 0,
            smoothLightingAo = 1,
            mipmapLevels = 2,
            cloudsMode = "fast",
            entityShadows = false,
            biomeBlendRadius = 1,
            resolutionRatioPercent = 80,
            defaultFpsLimit = 0,
            glslCacheMb = 128,
            lowEndCompatibilityMode = false
        )
        GraphicsPreset.BALANCED -> GraphicsPresetSpec(
            preset = GraphicsPreset.BALANCED,
            renderDistanceChunks = 10,
            simulationDistanceChunks = 8,
            particlesMode = 0,
            entityDistanceScaling = 1.0f,
            graphicsMode = 1,
            smoothLightingAo = 2,
            mipmapLevels = 4,
            cloudsMode = "fancy",
            entityShadows = true,
            biomeBlendRadius = 3,
            resolutionRatioPercent = 90,
            defaultFpsLimit = 0,
            glslCacheMb = 128,
            lowEndCompatibilityMode = false
        )
        GraphicsPreset.QUALITY, GraphicsPreset.CUSTOM -> GraphicsPresetSpec(
            preset = preset,
            renderDistanceChunks = 14,
            simulationDistanceChunks = 10,
            particlesMode = 0,
            entityDistanceScaling = 1.25f,
            graphicsMode = 1,
            smoothLightingAo = 2,
            mipmapLevels = 4,
            cloudsMode = "fancy",
            entityShadows = true,
            biomeBlendRadius = 5,
            resolutionRatioPercent = 100,
            defaultFpsLimit = 0,
            glslCacheMb = 256,
            lowEndCompatibilityMode = false
        )
    }

    /**
     * Classifies device capabilities into a balanced initial preset + safe conservative JVM heap cap.
     */
    fun recommend(capabilities: DeviceCapabilitiesSnapshot): HardwareRecommendation {
        val totalRam = capabilities.totalRamMb
        val availRam = capabilities.availableRamMb
        val cores = capabilities.cpuCoreCount
        val maxMhz = capabilities.cpuMaxFreqMhz
        val isLowRam = (totalRam in 1..4096) || (availRam in 1..1300)
        val isWeakCpu = cores in 1..4 || (maxMhz in 1..1900)
        val isLowGles = capabilities.glesMajorVersion < 3 || !capabilities.is64Bit
        val isWarm = (capabilities.thermalCelsiusMax ?: 0f) >= 44f

        val lowEndMode = isLowRam || isLowGles || (isWeakCpu && totalRam <= 6144)

        val recommendedRam = computeSafeHeapRecommendationMb(totalRam, availRam, lowEndMode)
        val recommendedRenderer = when {
            capabilities.glesMajorVersion >= 3 && capabilities.gpuFamily == GpuFamily.ADRENO -> "opengles_mobileglues"
            capabilities.glesMajorVersion >= 3 -> "opengles_mobileglues"
            else -> "opengles2"
        }

        val preset = when {
            !capabilities.is64Bit || (totalRam in 1..3200) || isLowGles -> GraphicsPreset.ULTRA_LOW
            lowEndMode || (totalRam in 3201..5500) -> GraphicsPreset.PERFORMANCE
            (totalRam >= 9000) && (maxMhz == 0 || maxMhz >= 2600) && !isWarm &&
                capabilities.glesMajorVersion >= 3 -> GraphicsPreset.QUALITY
            else -> GraphicsPreset.BALANCED
        }

        val rationale = buildString {
            append("${capabilities.cpuAbiPrimary} · ${cores} cores")
            if (maxMhz > 0) append(" (${maxMhz} MHz)")
            append(" · ${totalRam} MB RAM")
            if (capabilities.hasVulkan) append(" · Vulkan ${capabilities.vulkanVersion ?: "supported"}")
            if (lowEndMode) append(" -> Low-End Compatibility Mode enabled")
        }

        return HardwareRecommendation(
            preset = preset,
            lowEndCompatibilityMode = lowEndMode,
            recommendedRamMb = recommendedRam,
            recommendedRendererId = recommendedRenderer,
            rationale = rationale
        )
    }

    /**
     * Computes a device-aware conservative JVM `-Xmx` allocation without blindly maxing out
     * memory (avoids Android LMK kills and long GC pauses on large-RAM devices too).
     */
    fun computeSafeHeapRecommendationMb(totalRamMb: Long, availableRamMb: Long, lowEndMode: Boolean): Int {
        val base = when {
            totalRamMb <= 0L -> 1536
            totalRamMb <= 3072L -> 1024
            totalRamMb <= 4096L -> 1536
            totalRamMb <= 6144L -> 2048
            totalRamMb <= 8192L -> 3072
            else -> 4096
        }
        val cappedByAvailable = if (availableRamMb > 512L) {
            minOf(base, ((availableRamMb * 55) / 100).toInt().coerceAtLeast(1024))
        } else {
            base
        }
        return if (lowEndMode) minOf(cappedByAvailable, 1536) else cappedByAvailable
    }

    fun getCurrentPreset(context: Context): GraphicsPreset {
        val prefs = ensurePrefs(context)
        val rawId = prefs.getString(PREF_KEY_PRESET, null)
        return GraphicsPreset.values().firstOrNull { it.id == rawId } ?: GraphicsPreset.BALANCED
    }

    fun isLowEndModeEnabled(context: Context): Boolean {
        val prefs = ensurePrefs(context)
        return prefs.getBoolean(PREF_KEY_LOW_END_MODE, false)
    }

    fun markCustomOverride(context: Context) {
        ensurePrefs(context).edit()
            .putString(PREF_KEY_PRESET, GraphicsPreset.CUSTOM.id)
            .putBoolean(PREF_KEY_INITIALIZED, true)
            .apply()
    }

    fun setLowEndMode(context: Context, enabled: Boolean) {
        val prefs = ensurePrefs(context)
        prefs.edit()
            .putBoolean(PREF_KEY_LOW_END_MODE, enabled)
            .apply()
        if (enabled) {
            val caps = AndroidDeviceCapabilityProbe(context).probe()
            val safeRam = computeSafeHeapRecommendationMb(caps.totalRamMb, caps.availableRamMb, lowEndMode = true)
            prefs.edit()
                .putInt("allocation", safeRam)
                .putString("mg_renderer_setting_glsl_cache_size", "64")
                .putBoolean("sustainedPerformance", true)
                .apply()
            LauncherPreferences.PREF_RAM_ALLOCATION = safeRam
            LauncherPreferences.PREF_SUSTAINED_PERFORMANCE = true
        }
    }

    /**
     * Initializes adaptive graphics defaults once on first launch; preserves manual overrides thereafter.
     */
    fun initializeDefaultsOnFirstLaunch(
        context: Context,
        capabilities: DeviceCapabilitiesSnapshot
    ): HardwareRecommendation? {
        val prefs = ensurePrefs(context)
        if (prefs.getBoolean(PREF_KEY_INITIALIZED, false)) {
            return null
        }
        val rec = recommend(capabilities)
        applyPreset(
            context = context,
            preset = rec.preset,
            capabilities = capabilities,
            gameDirPath = null,
            explicitLowEndMode = rec.lowEndCompatibilityMode
        )
        return rec
    }

    /**
     * Applies [preset] to launcher preferences and optionally directly into [gameDirPath]/options.txt
     * (or `Tools.DIR_GAME_NEW` when initialized).
     */
    fun applyPreset(
        context: Context,
        preset: GraphicsPreset,
        capabilities: DeviceCapabilitiesSnapshot? = null,
        gameDirPath: String? = null,
        explicitLowEndMode: Boolean? = null
    ): GraphicsPresetSpec {
        val spec = specForPreset(preset)
        val caps = capabilities ?: AndroidDeviceCapabilityProbe(context).probe()
        val lowEnd = explicitLowEndMode ?: (spec.lowEndCompatibilityMode || recommend(caps).lowEndCompatibilityMode)
        val safeRam = computeSafeHeapRecommendationMb(caps.totalRamMb, caps.availableRamMb, lowEnd)

        val prefs = ensurePrefs(context)
        prefs.edit()
            .putString(PREF_KEY_PRESET, preset.id)
            .putBoolean(PREF_KEY_INITIALIZED, true)
            .putBoolean(PREF_KEY_LOW_END_MODE, lowEnd)
            .putInt("resolutionRatio", spec.resolutionRatioPercent)
            .putInt("fpsLimit", spec.defaultFpsLimit)
            .putInt("allocation", safeRam)
            .putBoolean("force_vsync", false)
            .putBoolean("vsync_in_zink", false)
            .putBoolean("sustainedPerformance", lowEnd || (caps.thermalCelsiusMax ?: 0f) >= 42f)
            .putString("mg_renderer_setting_glsl_cache_size", spec.glslCacheMb.toString())
            .apply()

        LauncherPreferences.PREF_SCALE_FACTOR = spec.resolutionRatioPercent / 100f
        LauncherPreferences.PREF_FPS_LIMIT = spec.defaultFpsLimit
        LauncherPreferences.PREF_RAM_ALLOCATION = safeRam
        LauncherPreferences.PREF_FORCE_VSYNC = false
        LauncherPreferences.PREF_VSYNC_IN_ZINK = false

        val resolvedDir = gameDirPath?.takeIf { it.isNotBlank() } ?: Tools.DIR_GAME_NEW
        if (!resolvedDir.isNullOrBlank()) {
            applySpecToMcOptions(resolvedDir, spec)
        }
        return spec
    }

    fun applySpecToMcOptions(gameDirPath: String, spec: GraphicsPresetSpec) {
        val dir = java.io.File(gameDirPath)
        if (!dir.exists()) dir.mkdirs()
        MCOptionUtils.load(dir.absolutePath)
        for ((k, v) in spec.toMcOptionsMap()) {
            MCOptionUtils.set(k, v)
        }
        MCOptionUtils.applyFpsLimit(spec.defaultFpsLimit, false)
        MCOptionUtils.save()
    }

    private fun ensurePrefs(context: Context): SharedPreferences {
        if (LauncherPreferences.DEFAULT_PREF == null) {
            LauncherPreferences.DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
        return LauncherPreferences.DEFAULT_PREF
    }
}
