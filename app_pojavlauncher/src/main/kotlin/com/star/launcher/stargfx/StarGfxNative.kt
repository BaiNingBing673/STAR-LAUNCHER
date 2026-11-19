package com.star.launcher.stargfx

import android.util.Log
import android.view.Surface
import org.json.JSONObject

object StarGfxNative {
    private const val TAG = "StarGfxNative"

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var librariesLoaded = false

    @Volatile
    private var loadError: String? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loadAttempted) return librariesLoaded
        loadAttempted = true
        return try {
            runCatching { System.loadLibrary("c++_shared") }
            System.loadLibrary("stargfx")
            System.loadLibrary("stargfx_bridge")
            librariesLoaded = true
            loadError = null
            Log.i(TAG, "Loaded libstargfx.so and libstargfx_bridge.so successfully")
            true
        } catch (t: Throwable) {
            librariesLoaded = false
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "Failed to load STAR GFX native libraries", t)
            false
        }
    }

    fun getLoadError(): String? = loadError

    fun verifyLibraries(): JSONObject {
        if (!ensureLoaded()) {
            return JSONObject().apply {
                put("stargfxLoaded", false)
                put("bridgeLoaded", false)
                put("error", loadError ?: "Native library load failed")
            }
        }
        return runCatching { JSONObject(nativeVerifyLibraries()) }.getOrElse {
            JSONObject().apply {
                put("stargfxLoaded", false)
                put("bridgeLoaded", false)
                put("error", it.message ?: "JNI verification failed")
            }
        }
    }

    fun probeSystemGraphics(): JSONObject {
        if (!ensureLoaded()) {
            return JSONObject().put("error", loadError ?: "Native library not loaded")
        }
        return runCatching { JSONObject(nativeProbeSystemGraphics()) }.getOrElse {
            JSONObject().put("error", it.message ?: "System graphics probe failed")
        }
    }

    fun getLastInitError(): JSONObject {
        if (!ensureLoaded()) {
            return JSONObject().put("code", -4).put("message", loadError ?: "Not loaded")
        }
        return runCatching { JSONObject(nativeGetLastInitError()) }.getOrElse {
            JSONObject().put("code", -1).put("message", it.message ?: "Unknown")
        }
    }

    fun createSession(preferredBackend: String, surface: Surface?, width: Int, height: Int): Long {
        if (!ensureLoaded()) return 0L
        return nativeCreateSession(preferredBackend, surface, width, height)
    }

    fun renderFrame(sessionPtr: Long, angleRad: Float): Long {
        if (!librariesLoaded || sessionPtr == 0L) return -1L
        return nativeRenderFrame(sessionPtr, angleRad)
    }

    fun captureOffscreenFrame(sessionPtr: Long, angleRad: Float, outPpmPath: String?): JSONObject {
        if (!librariesLoaded || sessionPtr == 0L) {
            return JSONObject().put("ok", false).put("error", "Session not active")
        }
        return runCatching { JSONObject(nativeCaptureOffscreenFrame(sessionPtr, angleRad, outPpmPath ?: "")) }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: "Capture exception")
        }
    }

    fun resizeSurface(sessionPtr: Long, surface: Surface?, width: Int, height: Int): Boolean {
        if (!librariesLoaded || sessionPtr == 0L) return false
        return nativeResizeSurface(sessionPtr, surface, width, height)
    }

    fun getDiagnosticsJson(sessionPtr: Long): JSONObject {
        if (!librariesLoaded || sessionPtr == 0L) {
            return JSONObject().put("error", "Session not active")
        }
        return runCatching { JSONObject(nativeGetDiagnosticsJson(sessionPtr)) }.getOrElse {
            JSONObject().put("error", it.message ?: "Diagnostics exception")
        }
    }

    fun runBenchmark(sessionPtr: Long, warmupFrames: Int, measureFrames: Int): JSONObject {
        if (!librariesLoaded || sessionPtr == 0L) {
            return JSONObject().put("ok", false).put("error", "Session not active")
        }
        return runCatching { JSONObject(nativeRunBenchmark(sessionPtr, warmupFrames, measureFrames)) }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: "Benchmark exception")
        }
    }

    fun destroySession(sessionPtr: Long) {
        if (!librariesLoaded || sessionPtr == 0L) return
        nativeDestroySession(sessionPtr)
    }

    @JvmStatic private external fun nativeVerifyLibraries(): String
    @JvmStatic private external fun nativeProbeSystemGraphics(): String
    @JvmStatic private external fun nativeGetLastInitError(): String
    @JvmStatic private external fun nativeCreateSession(preferredBackend: String, surface: Surface?, width: Int, height: Int): Long
    @JvmStatic private external fun nativeRenderFrame(sessionPtr: Long, angleRad: Float): Long
    @JvmStatic private external fun nativeCaptureOffscreenFrame(sessionPtr: Long, angleRad: Float, outPpmPath: String): String
    @JvmStatic private external fun nativeResizeSurface(sessionPtr: Long, surface: Surface?, width: Int, height: Int): Boolean
    @JvmStatic private external fun nativeGetDiagnosticsJson(sessionPtr: Long): String
    @JvmStatic private external fun nativeRunBenchmark(sessionPtr: Long, warmupFrames: Int, measureFrames: Int): String
    @JvmStatic private external fun nativeDestroySession(sessionPtr: Long)
}
