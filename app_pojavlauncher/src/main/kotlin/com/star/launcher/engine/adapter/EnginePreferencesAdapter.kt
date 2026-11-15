package com.star.launcher.engine.adapter

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences

data class EnginePreferencesSnapshot(
    val rendererId: String = "opengles2",
    val compatibleRenderers: List<Pair<String, String>> = emptyList(),
    // Video
    val ignoreNotch: Boolean = false,
    val notchAvailable: Boolean = false,
    val resolutionRatioPercent: Int = 100,
    val sustainedPerformance: Boolean = false,
    val alternateSurface: Boolean = false,
    val forceVsync: Boolean = false,
    val vsyncInZink: Boolean = false,
    val fpsLimit: Int = 0, // 0 = Unlimited, 30/60/90/120 or custom 10..260
    // MobileGlues Renderer Settings
    val mgGlslCacheSizeMb: String = "0",
    val mgAngleMode: String = "1",
    val mgErrorSetting: String = "0",
    val mgTimerQueryExt: Boolean = false,
    val mgAngleDepthClearFixMode: Boolean = false,
    val mgComputeShaderExt: Boolean = false,
    val mgDsaExt: Boolean = false,
    // Controls
    val disableGestures: Boolean = false,
    val disableDoubleTap: Boolean = false,
    val timeLongPressTriggerMs: Int = 300,
    val buttonScalePercent: Int = 100,
    val buttonAllCaps: Boolean = true,
    val keyboardPanning: Boolean = true,
    val mouseScalePercent: Int = 100,
    val mouseSpeedPercent: Int = 100,
    val mouseStart: Boolean = true,
    val alwaysGrabMouse: Boolean = false,
    val gyroAvailable: Boolean = false,
    val enableGyro: Boolean = false,
    val gyroSensitivityPercent: Int = 100,
    val gyroSampleRateMs: Int = 16,
    val gyroSmoothing: Boolean = true,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val gamepadDeadzoneScalePercent: Int = 100,
    val gamepadPassthruForced: Boolean = false,
    val forceEnableTouchController: Boolean = false,
    val touchControllerVibrateLengthMs: Int = 100,
    // Java
    val ramAllocationMb: Int = LauncherPreferences.PREF_RAM_ALLOCATION,
    val javaArgs: String = "",
    val disableAutoJreSelect: Boolean = false,
    // Misc
    val checkLibraries: Boolean = true,
    val arcCapes: Boolean = false,
    val downloadSource: String = "default",
    val verifyManifest: Boolean = true,
    val zinkPreferSystemDriver: Boolean = false,
    val zinkPreferSystemSupported: Boolean = false,
    val forceEnglish: Boolean = false,
    // Experimental
    val dumpShaders: Boolean = false,
    val bigCoreAffinity: Boolean = false,
    val sodiumOverride: Boolean = false
)

/**
 * Adapter translating inherited engine SharedPreferences keys into clean immutable snapshots
 * and atomic preference updates for STAR Launcher UI screens.
 */
object EnginePreferencesAdapter {

    private fun ensurePrefs(context: Context): SharedPreferences? {
        if (LauncherPreferences.DEFAULT_PREF == null) {
            LauncherPreferences.DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context)
        }
        return LauncherPreferences.DEFAULT_PREF
    }

    fun readSnapshot(context: Context): EnginePreferencesSnapshot {
        val prefs = ensurePrefs(context)
        try {
            LauncherPreferences.loadPreferences(context)
        } catch (_: Throwable) {
            // Native JNI static initializers (e.g. JREUtils/exithook) are unavailable in JVM Robolectric tests.
        }

        val renderers = try {
            val compat = Tools.getCompatibleRenderers(context)
            compat.rendererIds.zip(compat.rendererDisplayNames.toList())
        } catch (_: Throwable) {
            listOf("opengles2" to "Holy GL4ES")
        }

        val zinkSupported = try {
            Tools.checkVulkanSupport(context.packageManager)
        } catch (_: Throwable) {
            false
        }

        return EnginePreferencesSnapshot(
            rendererId = prefs?.getString("renderer", "opengles2") ?: "opengles2",
            compatibleRenderers = renderers,
            ignoreNotch = prefs?.getBoolean("ignoreNotch", LauncherPreferences.PREF_IGNORE_NOTCH)
                ?: LauncherPreferences.PREF_IGNORE_NOTCH,
            notchAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                LauncherPreferences.PREF_NOTCH_SIZE > 0,
            resolutionRatioPercent = (prefs?.getInt(
                "resolutionRatio",
                (LauncherPreferences.PREF_SCALE_FACTOR * 100f).toInt()
            ) ?: (LauncherPreferences.PREF_SCALE_FACTOR * 100f).toInt())
                .let { if (it < 25) 100 else it },
            sustainedPerformance = prefs?.getBoolean(
                "sustainedPerformance",
                LauncherPreferences.PREF_SUSTAINED_PERFORMANCE
            ) ?: LauncherPreferences.PREF_SUSTAINED_PERFORMANCE,
            alternateSurface = prefs?.getBoolean(
                "alternate_surface",
                LauncherPreferences.PREF_USE_ALTERNATE_SURFACE
            ) ?: LauncherPreferences.PREF_USE_ALTERNATE_SURFACE,
            forceVsync = prefs?.getBoolean("force_vsync", LauncherPreferences.PREF_FORCE_VSYNC)
                ?: LauncherPreferences.PREF_FORCE_VSYNC,
            vsyncInZink = prefs?.getBoolean("vsync_in_zink", LauncherPreferences.PREF_VSYNC_IN_ZINK)
                ?: LauncherPreferences.PREF_VSYNC_IN_ZINK,
            fpsLimit = LauncherPreferences.normalizeFpsLimit(
                prefs?.getInt("fpsLimit", LauncherPreferences.PREF_FPS_LIMIT)
                    ?: LauncherPreferences.PREF_FPS_LIMIT
            ),
            mgGlslCacheSizeMb = prefs?.getString("mg_renderer_setting_glsl_cache_size", "0") ?: "0",
            mgAngleMode = prefs?.getString("mg_renderer_setting_angle", "1") ?: "1",
            mgErrorSetting = prefs?.getString("mg_renderer_setting_errorSetting", "0") ?: "0",
            mgTimerQueryExt = prefs?.getBoolean("mg_renderer_setting_timerQueryExt", false) ?: false,
            mgAngleDepthClearFixMode = prefs?.getBoolean("mg_renderer_setting_angleDepthClearFixMode", false) ?: false,
            mgComputeShaderExt = prefs?.getBoolean("mg_renderer_computeShaderext", false) ?: false,
            mgDsaExt = prefs?.getBoolean("mg_renderer_dsaExt", false) ?: false,
            disableGestures = LauncherPreferences.PREF_DISABLE_GESTURES,
            disableDoubleTap = LauncherPreferences.PREF_DISABLE_SWAP_HAND,
            timeLongPressTriggerMs = LauncherPreferences.PREF_LONGPRESS_TRIGGER,
            buttonScalePercent = LauncherPreferences.PREF_BUTTONSIZE.toInt().coerceIn(50, 200),
            buttonAllCaps = LauncherPreferences.PREF_BUTTON_ALL_CAPS,
            keyboardPanning = LauncherPreferences.PREF_KEYBOARD_PANNING,
            mouseScalePercent = (LauncherPreferences.PREF_MOUSESCALE * 100f).toInt().coerceIn(25, 300),
            mouseSpeedPercent = (LauncherPreferences.PREF_MOUSESPEED * 100f).toInt().coerceIn(25, 300),
            mouseStart = LauncherPreferences.PREF_VIRTUAL_MOUSE_START,
            alwaysGrabMouse = LauncherPreferences.PREF_MOUSE_GRAB_FORCE,
            gyroAvailable = runCatching { Tools.deviceSupportsGyro(context) }.getOrDefault(false),
            enableGyro = LauncherPreferences.PREF_ENABLE_GYRO,
            gyroSensitivityPercent = (LauncherPreferences.PREF_GYRO_SENSITIVITY * 100f).toInt().coerceIn(25, 300),
            gyroSampleRateMs = LauncherPreferences.PREF_GYRO_SAMPLE_RATE,
            gyroSmoothing = LauncherPreferences.PREF_GYRO_SMOOTHING,
            gyroInvertX = LauncherPreferences.PREF_GYRO_INVERT_X,
            gyroInvertY = LauncherPreferences.PREF_GYRO_INVERT_Y,
            gamepadDeadzoneScalePercent = (LauncherPreferences.PREF_DEADZONE_SCALE * 100f).toInt().coerceIn(50, 200),
            gamepadPassthruForced = prefs?.getBoolean("gamepadPassthruForced", false) ?: false,
            forceEnableTouchController = LauncherPreferences.PREF_FORCE_ENABLE_TOUCHCONTROLLER,
            touchControllerVibrateLengthMs = LauncherPreferences.PREF_TOUCHCONTROLLER_VIBRATE_LENGTH,
            ramAllocationMb = LauncherPreferences.PREF_RAM_ALLOCATION,
            javaArgs = LauncherPreferences.PREF_CUSTOM_JAVA_ARGS.orEmpty(),
            disableAutoJreSelect = prefs?.getBoolean("disable_autojre_select", false) ?: false,
            checkLibraries = LauncherPreferences.PREF_CHECK_LIBRARY_SHA,
            arcCapes = LauncherPreferences.PREF_ARC_CAPES,
            downloadSource = prefs?.getString("downloadSource", "default") ?: "default",
            verifyManifest = LauncherPreferences.PREF_VERIFY_MANIFEST,
            zinkPreferSystemDriver = LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER,
            zinkPreferSystemSupported = zinkSupported,
            forceEnglish = LauncherPreferences.PREF_FORCE_ENGLISH,
            dumpShaders = LauncherPreferences.PREF_DUMP_SHADERS,
            bigCoreAffinity = LauncherPreferences.PREF_BIG_CORE_AFFINITY,
            sodiumOverride = prefs?.getBoolean("sodium_override", false) ?: false
        )
    }

    fun setBoolean(context: Context, key: String, value: Boolean): EnginePreferencesSnapshot {
        ensurePrefs(context)?.edit()?.putBoolean(key, value)?.apply()
        return readSnapshot(context)
    }

    fun setInt(context: Context, key: String, value: Int): EnginePreferencesSnapshot {
        ensurePrefs(context)?.edit()?.putInt(key, value)?.apply()
        return readSnapshot(context)
    }

    fun setString(context: Context, key: String, value: String): EnginePreferencesSnapshot {
        ensurePrefs(context)?.edit()?.putString(key, value)?.apply()
        return readSnapshot(context)
    }

    fun setSelectedRuntime(context: Context, runtimeName: String): EnginePreferencesSnapshot {
        ensurePrefs(context)?.edit()?.putString("defaultRuntime", runtimeName)?.apply()
        return readSnapshot(context)
    }

    fun wipeGamepadMappings(context: Context): EnginePreferencesSnapshot {
        val prefs = ensurePrefs(context) ?: return readSnapshot(context)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("gamepad_") }
            .forEach { editor.remove(it) }
        editor.apply()
        return readSnapshot(context)
    }
}
