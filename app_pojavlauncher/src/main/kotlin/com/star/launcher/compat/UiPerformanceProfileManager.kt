package com.star.launcher.compat

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-selectable mode for STAR Launcher's UI & background adaptive performance engine.
 */
enum class UiPerformanceProfileMode(val displayName: String) {
    AUTO("Auto (Hardware Adaptive)"),
    LOW("Low (Max Battery & Low-RAM)"),
    BALANCED("Balanced (60 FPS Smooth)"),
    HIGH("High (90/120 Hz Flagship)")
}

/**
 * Resolved runtime performance tier for UI animations, image decoding, caching, and overlays.
 */
enum class UiPerformanceProfile(val label: String) {
    LOW("LOW"),
    BALANCED("BALANCED"),
    HIGH("HIGH")
}

/**
 * Concrete tuning parameters derived from [UiPerformanceProfile].
 */
data class UiPerformanceSpec(
    val mode: UiPerformanceProfileMode,
    val profile: UiPerformanceProfile,
    val enableDecorativeLoops: Boolean,
    val enableGlowAndRadialLayers: Boolean,
    val cardShadowElevationScale: Float,
    val preferRgb565Thumbnails: Boolean,
    val maxThumbnailDimensionPx: Int,
    val memoryCacheDivisor: Int,
    val maxConcurrentBackgroundTasks: Int,
    val lyricsWordSyncIntervalMs: Long,
    val lyricsLineSyncIntervalMs: Long,
    val navigationAnimDurationMs: Int
)

/**
 * Adaptive UI performance profile manager (Section 10).
 *
 * Automatically classifies the device into [UiPerformanceProfile.LOW], [UiPerformanceProfile.BALANCED],
 * or [UiPerformanceProfile.HIGH] based on RAM, low-RAM flags, and CPU cores, while allowing explicit
 * user override in Settings.
 */
object UiPerformanceProfileManager {
    private const val PREFS_NAME = "star_ui_performance_prefs"
    private const val KEY_PROFILE_MODE = "ui_performance_profile_mode"

    @Volatile
    private var initialized = false

    @Volatile
    private var detectedHardwareProfile: UiPerformanceProfile = UiPerformanceProfile.BALANCED

    private val _specFlow = MutableStateFlow(buildSpec(UiPerformanceProfileMode.AUTO, UiPerformanceProfile.BALANCED))
    val specFlow: StateFlow<UiPerformanceSpec> = _specFlow.asStateFlow()

    @JvmStatic
    fun currentSpec(): UiPerformanceSpec = _specFlow.value

    @JvmStatic
    fun ensureInitialized(context: Context): UiPerformanceSpec {
        if (initialized) return _specFlow.value
        synchronized(this) {
            if (initialized) return _specFlow.value
            val appContext = context.applicationContext ?: context
            detectedHardwareProfile = classifyHardware(appContext)
            val savedMode = loadSavedMode(appContext)
            val resolved = resolveProfile(savedMode, detectedHardwareProfile)
            val spec = buildSpec(savedMode, resolved)
            _specFlow.value = spec
            initialized = true
            return spec
        }
    }

    @JvmStatic
    fun setMode(context: Context, mode: UiPerformanceProfileMode): UiPerformanceSpec {
        val appContext = context.applicationContext ?: context
        if (!initialized) {
            detectedHardwareProfile = classifyHardware(appContext)
            initialized = true
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE_MODE, mode.name)
            .apply()
        val resolved = resolveProfile(mode, detectedHardwareProfile)
        val spec = buildSpec(mode, resolved)
        _specFlow.value = spec
        return spec
    }

    @JvmStatic
    fun detectedProfile(): UiPerformanceProfile = detectedHardwareProfile

    internal fun classifyHardware(context: Context): UiPerformanceProfile {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null && am.isLowRamDevice) {
                return@runCatching UiPerformanceProfile.LOW
            }
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalRamMb = if (memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 4096L
            val cores = Runtime.getRuntime().availableProcessors()
            when {
                totalRamMb <= 3800L || cores <= 4 -> UiPerformanceProfile.LOW
                totalRamMb <= 6400L || cores <= 6 -> UiPerformanceProfile.BALANCED
                else -> UiPerformanceProfile.HIGH
            }
        }.getOrDefault(UiPerformanceProfile.BALANCED)
    }

    internal fun resolveProfile(
        mode: UiPerformanceProfileMode,
        hardwareProfile: UiPerformanceProfile
    ): UiPerformanceProfile = when (mode) {
        UiPerformanceProfileMode.AUTO -> hardwareProfile
        UiPerformanceProfileMode.LOW -> UiPerformanceProfile.LOW
        UiPerformanceProfileMode.BALANCED -> UiPerformanceProfile.BALANCED
        UiPerformanceProfileMode.HIGH -> UiPerformanceProfile.HIGH
    }

    internal fun buildSpec(
        mode: UiPerformanceProfileMode,
        profile: UiPerformanceProfile
    ): UiPerformanceSpec = when (profile) {
        UiPerformanceProfile.LOW -> UiPerformanceSpec(
            mode = mode,
            profile = UiPerformanceProfile.LOW,
            enableDecorativeLoops = false,
            enableGlowAndRadialLayers = false,
            cardShadowElevationScale = 0f,
            preferRgb565Thumbnails = true,
            maxThumbnailDimensionPx = 192,
            memoryCacheDivisor = 12,
            maxConcurrentBackgroundTasks = 2,
            lyricsWordSyncIntervalMs = 100L,
            lyricsLineSyncIntervalMs = 180L,
            navigationAnimDurationMs = 110
        )
        UiPerformanceProfile.BALANCED -> UiPerformanceSpec(
            mode = mode,
            profile = UiPerformanceProfile.BALANCED,
            enableDecorativeLoops = true,
            enableGlowAndRadialLayers = true,
            cardShadowElevationScale = 0.65f,
            preferRgb565Thumbnails = false,
            maxThumbnailDimensionPx = 256,
            memoryCacheDivisor = 10,
            maxConcurrentBackgroundTasks = 3,
            lyricsWordSyncIntervalMs = 65L,
            lyricsLineSyncIntervalMs = 120L,
            navigationAnimDurationMs = 150
        )
        UiPerformanceProfile.HIGH -> UiPerformanceSpec(
            mode = mode,
            profile = UiPerformanceProfile.HIGH,
            enableDecorativeLoops = true,
            enableGlowAndRadialLayers = true,
            cardShadowElevationScale = 1.0f,
            preferRgb565Thumbnails = false,
            maxThumbnailDimensionPx = 384,
            memoryCacheDivisor = 8,
            maxConcurrentBackgroundTasks = 4,
            lyricsWordSyncIntervalMs = 50L,
            lyricsLineSyncIntervalMs = 90L,
            navigationAnimDurationMs = 175
        )
    }

    private fun loadSavedMode(context: Context): UiPerformanceProfileMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE_MODE, UiPerformanceProfileMode.AUTO.name)
        return UiPerformanceProfileMode.values().firstOrNull { it.name == raw }
            ?: UiPerformanceProfileMode.AUTO
    }
}
