package com.star.launcher.stargfx

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.Tools
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object StarGfxFallbackController {
    private const val PREF_FILE = "stargfx_integration_prefs"
    private const val KEY_ENABLED = "stargfx_enabled"
    private const val KEY_BACKEND = "stargfx_backend"
    private const val KEY_CONSECUTIVE_FAILURES = "stargfx_consecutive_failures"
    private const val KEY_LAST_ERROR = "stargfx_last_error"
    private const val KEY_LAST_REPORT_JSON = "stargfx_last_report_json"
    private const val KEY_LAST_REPORT_TXT = "stargfx_last_report_txt"
    private const val KEY_LAST_IMAGE_PATH = "stargfx_last_image_path"
    private const val KEY_LAST_ACCEPTANCE_LEVEL = "stargfx_last_acceptance_level"
    private const val KEY_SAFE_FALLBACK_RENDERER = "stargfx_safe_fallback_renderer"

    const val RENDERER_ID_STARGFX_VULKAN = "stargfx_vulkan"
    const val RENDERER_ID_STARGFX_GLES = "stargfx_gles"
    const val DEFAULT_STABLE_RENDERER = "opengles_mobileglues"
    private const val MAX_FAILURES_BEFORE_AUTO_DISABLE = 2

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply {
                if (enabled) putInt(KEY_CONSECUTIVE_FAILURES, 0)
            }
            .apply()
    }

    fun getSelectedBackend(context: Context): StarGfxBackendPreference =
        StarGfxBackendPreference.fromId(prefs(context).getString(KEY_BACKEND, StarGfxBackendPreference.VULKAN.id))

    fun setSelectedBackend(context: Context, backend: StarGfxBackendPreference) {
        prefs(context).edit().putString(KEY_BACKEND, backend.id).apply()
    }

    fun getConsecutiveFailures(context: Context): Int =
        prefs(context).getInt(KEY_CONSECUTIVE_FAILURES, 0)

    fun getLastError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)

    fun getLastReportJsonPath(context: Context): String? =
        prefs(context).getString(KEY_LAST_REPORT_JSON, null)

    fun getLastReportTxtPath(context: Context): String? =
        prefs(context).getString(KEY_LAST_REPORT_TXT, null)

    fun getLastCapturedImagePath(context: Context): String? =
        prefs(context).getString(KEY_LAST_IMAGE_PATH, null)

    fun getLastAcceptanceLevel(context: Context): String =
        prefs(context).getString(KEY_LAST_ACCEPTANCE_LEVEL, StarGfxAcceptanceLevel.BUILT.label)
            ?: StarGfxAcceptanceLevel.BUILT.label

    fun getStableFallbackRenderer(context: Context): String =
        prefs(context).getString(KEY_SAFE_FALLBACK_RENDERER, DEFAULT_STABLE_RENDERER)
            ?: DEFAULT_STABLE_RENDERER

    fun recordSuccessfulRun(
        context: Context,
        level: StarGfxAcceptanceLevel,
        jsonPath: String?,
        txtPath: String?,
        imagePath: String?
    ) {
        prefs(context).edit()
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .putString(KEY_LAST_ERROR, null)
            .putString(KEY_LAST_ACCEPTANCE_LEVEL, level.label)
            .apply {
                if (!jsonPath.isNullOrBlank()) putString(KEY_LAST_REPORT_JSON, jsonPath)
                if (!txtPath.isNullOrBlank()) putString(KEY_LAST_REPORT_TXT, txtPath)
                if (!imagePath.isNullOrBlank()) putString(KEY_LAST_IMAGE_PATH, imagePath)
            }
            .apply()
    }

    fun isStarGfxRendererId(rendererId: String?): Boolean =
        rendererId == RENDERER_ID_STARGFX_VULKAN || rendererId == RENDERER_ID_STARGFX_GLES

    private const val RENDERPEARL_264_MARKER_ENTRY =
        "com/mojang/renderpearl/api/commands/RenderPassDescriptor\$Attachment\$ClearValue.class"

    private fun findRenderPearl264Jar(versionId: String?): File? {
        if (versionId.isNullOrBlank()) return null
        val versionsRoot = Tools.DIR_HOME_VERSION?.let(::File) ?: return null
        var currentId: String? = versionId
        val visited = HashSet<String>()
        for (depth in 0 until 6) {
            val id = currentId ?: break
            if (!visited.add(id)) break
            val versionDir = File(versionsRoot, id)
            val jarFile = File(versionDir, "$id.jar")
            if (jarFile.isFile) {
                try {
                    ZipFile(jarFile).use { zip ->
                        if (zip.getEntry(RENDERPEARL_264_MARKER_ENTRY) != null) {
                            return jarFile
                        }
                    }
                } catch (_: Exception) {
                }
            }
            val jsonFile = File(versionDir, "$id.json")
            if (!jsonFile.isFile) break
            currentId = try {
                val obj = JSONObject(jsonFile.readText())
                val inherits = obj.optString("inheritsFrom", "")
                inherits.ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Evaluates whether STAR GFX can directly render the requested Minecraft version.
     * Minecraft >= 26.4 exposes the RenderPearl 26.4 SPI (`StarGfxGpuBackend`), while older versions
     * require Desktop OpenGL 3.2+ (`MobileGlues`).
     */
    fun checkMinecraftCompatibility(
        context: Context,
        versionId: String?,
        requestedRenderer: String? = RENDERER_ID_STARGFX_VULKAN
    ): StarGfxMinecraftCompatibilityStatus {
        val libDir = Tools.NATIVE_LIB_DIR ?: context.applicationInfo.nativeLibraryDir
        val hasMg = libDir != null && File(libDir, "libmobileglues.so").isFile
        val hasHalJni = libDir != null && File(libDir, "libstargfx_hal_jni.so").isFile
        if (requestedRenderer == RENDERER_ID_STARGFX_VULKAN) {
            val rpJar = findRenderPearl264Jar(versionId)
            if (hasHalJni && rpJar != null) {
                return StarGfxMinecraftCompatibilityStatus(
                    compatible = true,
                    reason = "RenderPearl 26.4+ SPI detected in ${rpJar.name} with libstargfx_hal_jni.so.",
                    requiredTranslationLayer = "RenderPearl 26.4+ -> StarGfxGpuBackend -> StarGfxHalJni"
                )
            }
            val detail = if (!hasHalJni) {
                "libstargfx_hal_jni.so is missing in $libDir"
            } else {
                "Minecraft ${versionId ?: "Java"} does not contain RenderPearl 26.4+ SPI ($RENDERPEARL_264_MARKER_ENTRY)"
            }
            return StarGfxMinecraftCompatibilityStatus(
                compatible = false,
                reason = "$detail. Restoring ${if (hasMg) DEFAULT_STABLE_RENDERER else "opengles2"}.",
                requiredTranslationLayer = "RenderPearl 26.4+ (for stargfx_vulkan) or MobileGlues for <26.4"
            )
        }
        return StarGfxMinecraftCompatibilityStatus(
            compatible = false,
            reason = "STAR GFX GLES does not export a Desktop OpenGL 3.2+ " +
                "(gl*/eglGetProcAddress + GLSL 150-460) frontend required by LWJGL for Minecraft ${versionId ?: "Java"}. " +
                "Restoring ${if (hasMg) DEFAULT_STABLE_RENDERER else "opengles2"}.",
            requiredTranslationLayer = "Desktop OpenGL 3.2-4.6 frontend -> sgfx_* (not yet implemented)"
        )
    }

    /**
     * Phase 10: Records error, preserves diagnostic files, disables STAR GFX on repeated failures,
     * and returns the safe stable renderer without corrupting any Minecraft profiles or files.
     */
    fun recordFailureAndRestoreStable(
        context: Context,
        errorMessage: String,
        jsonPath: String? = null,
        txtPath: String? = null,
        forceDisable: Boolean = false
    ): String {
        val p = prefs(context)
        val failures = p.getInt(KEY_CONSECUTIVE_FAILURES, 0) + 1
        val shouldDisable = forceDisable || failures >= MAX_FAILURES_BEFORE_AUTO_DISABLE
        val stableRenderer = resolveBestStableRenderer(context)
        p.edit()
            .putInt(KEY_CONSECUTIVE_FAILURES, failures)
            .putString(KEY_LAST_ERROR, errorMessage)
            .putString(KEY_SAFE_FALLBACK_RENDERER, stableRenderer)
            .apply {
                if (shouldDisable) putBoolean(KEY_ENABLED, false)
                if (!jsonPath.isNullOrBlank()) putString(KEY_LAST_REPORT_JSON, jsonPath)
                if (!txtPath.isNullOrBlank()) putString(KEY_LAST_REPORT_TXT, txtPath)
            }
            .apply()
        Tools.LOCAL_RENDERER = stableRenderer
        return stableRenderer
    }

    /**
     * Invoked from MainActivity.runCraft if a STAR GFX renderer ID was selected for Minecraft Java.
     * Verifies STAR GFX runtime/backend natively, records the exact Desktop OpenGL compatibility gap,
     * saves a diagnostic report, and explicitly restores the stable fallback renderer without ever
     * pretending STAR GFX rendered Minecraft.
     */
    @JvmStatic
    fun handleMinecraftLaunchAttempt(
        activity: Activity,
        versionId: String?,
        requestedRenderer: String?
    ): String {
        val compat = checkMinecraftCompatibility(activity, versionId, requestedRenderer)
        if (compat.compatible && requestedRenderer == RENDERER_ID_STARGFX_VULKAN) {
            recordSuccessfulRun(
                activity,
                StarGfxAcceptanceLevel.STAR_LAUNCHER_INTEGRATED,
                null,
                null,
                null
            )
            Tools.LOCAL_RENDERER = RENDERER_ID_STARGFX_VULKAN
            Logger.appendToLog("STAR GFX RenderPearl Gate: ${compat.reason}")
            Logger.appendToLog("STAR GFX -> Launching Minecraft $versionId with $RENDERER_ID_STARGFX_VULKAN")
            return RENDERER_ID_STARGFX_VULKAN
        }
        val backendId = if (requestedRenderer == RENDERER_ID_STARGFX_GLES) "gles" else "vulkan"
        val libVer = StarGfxNative.verifyLibraries()
        val sysProbe = StarGfxNative.probeSystemGraphics()
        var diag = JSONObject()
        val ptr = StarGfxNative.createSession(backendId, null, 256, 256)
        if (ptr != 0L) {
            try {
                diag = StarGfxNative.getDiagnosticsJson(ptr)
            } finally {
                StarGfxNative.destroySession(ptr)
            }
        }
        val level = if (diag.optBoolean("deviceInitialized")) {
            StarGfxAcceptanceLevel.STAR_LAUNCHER_INTEGRATED
        } else if (libVer.optBoolean("stargfxLoaded")) {
            StarGfxAcceptanceLevel.NATIVE_LIBRARY_LOADED
        } else {
            StarGfxAcceptanceLevel.BUILT
        }
        val exported = StarGfxDiagnosticExporter.exportReport(
            context = activity,
            backendSelected = requestedRenderer ?: backendId,
            libVerification = libVer,
            systemProbe = sysProbe,
            runtimeDiag = diag,
            renderProof = null,
            benchmarkShort = null,
            benchmarkSustained = null,
            acceptanceLevel = level,
            runtimeErrors = listOf(compat.reason),
            crashInfo = null,
            capturedPng = null
        )
        val restored = if (requestedRenderer == RENDERER_ID_STARGFX_VULKAN) {
            val stableRenderer = resolveBestStableRenderer(activity)
            prefs(activity).edit()
                .putString(KEY_LAST_ERROR, compat.reason)
                .putString(KEY_SAFE_FALLBACK_RENDERER, stableRenderer)
                .putString(KEY_LAST_REPORT_JSON, exported.jsonFile.absolutePath)
                .putString(KEY_LAST_REPORT_TXT, exported.textFile.absolutePath)
                .apply()
            Tools.LOCAL_RENDERER = stableRenderer
            stableRenderer
        } else {
            recordFailureAndRestoreStable(
                activity,
                compat.reason,
                exported.jsonFile.absolutePath,
                exported.textFile.absolutePath,
                forceDisable = false
            )
        }
        Logger.appendToLog("STAR GFX Compatibility Gate: ${compat.reason}")
        Logger.appendToLog("STAR GFX Diagnostic Report saved: ${exported.jsonFile.absolutePath}")
        Logger.appendToLog("STAR GFX Fallback -> restored stable renderer: $restored")
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                "STAR GFX RenderPearl requires Minecraft 26.4+ ($restored restored).",
                Toast.LENGTH_LONG
            ).show()
        }
        return restored
    }

    fun resolveBestStableRenderer(context: Context): String {
        val nativeDir = Tools.NATIVE_LIB_DIR ?: context.applicationInfo.nativeLibraryDir
        if (nativeDir != null && File(nativeDir, "libmobileglues.so").isFile) {
            return DEFAULT_STABLE_RENDERER
        }
        return "opengles2"
    }
}
