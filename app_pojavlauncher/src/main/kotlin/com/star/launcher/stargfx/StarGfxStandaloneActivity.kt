package com.star.launcher.stargfx

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class StarGfxStandaloneActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "StarGfxStandalone"
        const val EXTRA_BACKEND = "extra_backend"
        const val EXTRA_AUTO_VERIFY = "extra_auto_verify"
        const val EXTRA_AUTO_BENCH = "extra_auto_bench"
        const val EXTRA_SUSTAINED_FRAMES = "extra_sustained_frames"
        const val EXTRA_AUTO_FINISH = "extra_auto_finish"

        fun createIntent(
            context: Context,
            backend: String = "vulkan",
            autoVerify: Boolean = false,
            autoBench: Boolean = false,
            sustainedFrames: Int = 300,
            autoFinish: Boolean = false
        ): Intent = Intent(context, StarGfxStandaloneActivity::class.java).apply {
            putExtra(EXTRA_BACKEND, backend)
            putExtra(EXTRA_AUTO_VERIFY, autoVerify)
            putExtra(EXTRA_AUTO_BENCH, autoBench)
            putExtra(EXTRA_SUSTAINED_FRAMES, sustainedFrames)
            putExtra(EXTRA_AUTO_FINISH, autoFinish)
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var hudText: TextView

    private val sessionLock = Any()
    @Volatile
    private var sessionPtr: Long = 0L
    @Volatile
    private var renderThread: Thread? = null
    private val renderingActive = AtomicBoolean(false)
    private val autoVerifyDone = AtomicBoolean(false)

    private var requestedBackend: String = "vulkan"
    private var autoVerify: Boolean = false
    private var autoBench: Boolean = false
    private var sustainedFrames: Int = 300
    private var autoFinish: Boolean = false
    private val sessionId = UUID.randomUUID().toString()

    private var surfaceCreatedCount = 0
    private var surfaceChangedCount = 0
    private var surfaceDestroyedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedBackend = intent?.getStringExtra(EXTRA_BACKEND)
            ?: StarGfxFallbackController.getSelectedBackend(this).id
        autoVerify = intent?.getBooleanExtra(EXTRA_AUTO_VERIFY, false) ?: false
        autoBench = intent?.getBooleanExtra(EXTRA_AUTO_BENCH, false) ?: false
        sustainedFrames = intent?.getIntExtra(EXTRA_SUSTAINED_FRAMES, 300) ?: 300
        autoFinish = intent?.getBooleanExtra(EXTRA_AUTO_FINISH, false) ?: false

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(175, 10, 14, 24))
            setPadding(24, 24, 24, 24)
        }
        hudText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "STAR GFX Standalone Verification Initializing ($requestedBackend)..."
        }
        overlay.addView(hudText)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        val btnSwitch = Button(this).apply {
            text = "Switch Backend"
            setOnClickListener {
                requestedBackend = if (requestedBackend == "vulkan") "gles" else "vulkan"
                restartSessionOnCurrentSurface()
            }
        }
        val btnCapture = Button(this).apply {
            text = "Verify & Export"
            setOnClickListener {
                Thread { runVerificationAndExport(runBench = false) }.start()
            }
        }
        val btnBench = Button(this).apply {
            text = "Run Bench"
            setOnClickListener {
                Thread { runVerificationAndExport(runBench = true) }.start()
            }
        }
        val btnClose = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        controlsRow.addView(btnSwitch)
        controlsRow.addView(btnCapture)
        controlsRow.addView(btnBench)
        controlsRow.addView(btnClose)
        overlay.addView(controlsRow)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        root.addView(overlay, lp)
        setContentView(root)
    }

    private fun restartSessionOnCurrentSurface() {
        stopRenderThread()
        synchronized(sessionLock) {
            if (sessionPtr != 0L) {
                StarGfxNative.destroySession(sessionPtr)
                sessionPtr = 0L
            }
            val holder = surfaceView.holder
            if (holder.surface != null && holder.surface.isValid) {
                val rect = holder.surfaceFrame
                val w = rect.width().coerceAtLeast(1)
                val h = rect.height().coerceAtLeast(1)
                sessionPtr = StarGfxNative.createSession(requestedBackend, holder.surface, w, h)
                if (sessionPtr != 0L) {
                    startRenderThread()
                } else {
                    val err = StarGfxNative.getLastInitError().optString("message", "Unknown init error")
                    StarGfxFallbackController.recordFailureAndRestoreStable(this, err)
                    updateHud("FAILED to initialize $requestedBackend: $err")
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceCreatedCount++
        val rect = holder.surfaceFrame
        val w = rect.width().coerceAtLeast(surfaceView.width.coerceAtLeast(1))
        val h = rect.height().coerceAtLeast(surfaceView.height.coerceAtLeast(1))
        synchronized(sessionLock) {
            if (sessionPtr == 0L) {
                sessionPtr = StarGfxNative.createSession(requestedBackend, holder.surface, w, h)
            } else {
                StarGfxNative.resizeSurface(sessionPtr, holder.surface, w, h)
            }
        }
        if (sessionPtr == 0L) {
            val err = StarGfxNative.getLastInitError().optString("message", "createSession failed")
            Log.e(TAG, "STAR GFX init failed: $err")
            StarGfxFallbackController.recordFailureAndRestoreStable(this, err)
            updateHud("INIT FAILED ($requestedBackend): $err")
            if (autoVerify) {
                Thread {
                    runVerificationAndExport(runBench = false)
                    if (autoFinish) runOnUiThread { finish() }
                }.start()
            }
            return
        }
        startRenderThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceChangedCount++
        synchronized(sessionLock) {
            if (sessionPtr != 0L) {
                StarGfxNative.resizeSurface(sessionPtr, holder.surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceDestroyedCount++
        stopRenderThread()
        synchronized(sessionLock) {
            if (sessionPtr != 0L) {
                StarGfxNative.destroySession(sessionPtr)
                sessionPtr = 0L
            }
        }
    }

    override fun onDestroy() {
        stopRenderThread()
        synchronized(sessionLock) {
            if (sessionPtr != 0L) {
                StarGfxNative.destroySession(sessionPtr)
                sessionPtr = 0L
            }
        }
        super.onDestroy()
    }

    private fun startRenderThread() {
        if (renderingActive.getAndSet(true)) return
        renderThread = Thread({
            var frameCount = 0L
            var lastHudMs = System.currentTimeMillis()
            while (renderingActive.get()) {
                val ptr = sessionPtr
                if (ptr == 0L) break
                val angle = frameCount.toFloat() * 0.03f
                val ns = StarGfxNative.renderFrame(ptr, angle)
                if (ns < 0L) {
                    Log.e(TAG, "renderFrame failed code=$ns")
                    break
                }
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastHudMs >= 250L) {
                    lastHudMs = now
                    val diag = StarGfxNative.getDiagnosticsJson(ptr)
                    val summary = buildString {
                        append("STAR GFX ${diag.optString("starGfxVersion")} | Backend=${diag.optString("activeBackend")}")
                        append(" | API=${diag.optString("actualApiVersion")}\n")
                        append("GPU=${diag.optString("gpuRenderer")} | Driver=${diag.optString("gpuDriverVersion")}\n")
                        append("Swapchain=${diag.optInt("swapchainWidth")}x${diag.optInt("swapchainHeight")}")
                        append(" | Frames=${diag.optLong("framesPresented")} | AvgFPS=${"%.1f".format(diag.optDouble("avgFps"))}")
                        append(" | VmRSS=${diag.optLong("vmRssKb") / 1024L}MB\n")
                        append("Lifecycle: created=$surfaceCreatedCount changed=$surfaceChangedCount destroyed=$surfaceDestroyedCount")
                    }
                    updateHud(summary)
                }
                if (autoVerify && frameCount >= 30L && autoVerifyDone.compareAndSet(false, true)) {
                    runVerificationAndExport(runBench = autoBench)
                    if (autoFinish) {
                        renderingActive.set(false)
                        runOnUiThread { finish() }
                        break
                    }
                }
            }
        }, "StarGfxRenderThread").apply { start() }
    }

    private fun stopRenderThread() {
        renderingActive.set(false)
        renderThread?.let { t ->
            runCatching { t.join(1500L) }
        }
        renderThread = null
    }

    private fun runVerificationAndExport(runBench: Boolean) {
        val libVer = StarGfxNative.verifyLibraries()
        val sysProbe = StarGfxNative.probeSystemGraphics()
        val runtimeErrors = mutableListOf<String>()
        var diag = JSONObject()
        var proof: JSONObject? = null
        var benchShort: JSONObject? = null
        var benchSustained: JSONObject? = null
        var pngFile: File? = null

        synchronized(sessionLock) {
            val ptr = sessionPtr
            if (ptr == 0L) {
                val err = StarGfxNative.getLastInitError()
                runtimeErrors += "Session creation failed: ${err.optString("message")}"
            } else {
                val dir = StarGfxDiagnosticExporter.diagnosticsDir(this)
                val ppmFile = File(dir, "stargfx_capture_${requestedBackend}_${sessionId}.ppm")
                pngFile = File(dir, "stargfx_capture_${requestedBackend}_${sessionId}.png")
                proof = StarGfxNative.captureOffscreenFrame(ptr, 0.65f, ppmFile.absolutePath)
                if (proof?.optBoolean("ok") == true && ppmFile.isFile) {
                    if (!StarGfxDiagnosticExporter.convertPpmToPng(ppmFile, pngFile!!)) {
                        pngFile = null
                    }
                }
                if (runBench) {
                    benchShort = StarGfxNative.runBenchmark(ptr, 30, 120)
                    benchSustained = StarGfxNative.runBenchmark(ptr, 30, sustainedFrames)
                }
                diag = StarGfxNative.getDiagnosticsJson(ptr)
            }
        }

        val level = computeAcceptanceLevel(libVer, diag, proof, benchShort)
        val exported = StarGfxDiagnosticExporter.exportReport(
            context = this,
            sessionId = sessionId,
            backendSelected = requestedBackend,
            libVerification = libVer,
            systemProbe = sysProbe,
            runtimeDiag = diag,
            renderProof = proof,
            benchmarkShort = benchShort,
            benchmarkSustained = benchSustained,
            acceptanceLevel = level,
            runtimeErrors = runtimeErrors,
            crashInfo = null,
            capturedPng = pngFile
        )

        if (level.ordinal >= StarGfxAcceptanceLevel.RENDERING_VERIFIED.ordinal) {
            StarGfxFallbackController.recordSuccessfulRun(
                this,
                level,
                exported.jsonFile.absolutePath,
                exported.textFile.absolutePath,
                exported.pngFile?.absolutePath
            )
        } else {
            StarGfxFallbackController.recordFailureAndRestoreStable(
                this,
                runtimeErrors.firstOrNull() ?: "Verification did not reach RENDERING VERIFIED",
                exported.jsonFile.absolutePath,
                exported.textFile.absolutePath
            )
        }

        Log.i(
            TAG,
            "STAR_GFX_QA_RESULT|session=$sessionId|backend=${diag.optString("activeBackend")}|" +
                "level=${level.label}|proofOk=${proof?.optBoolean("ok")}|json=${exported.jsonFile.absolutePath}|" +
                "png=${exported.pngFile?.absolutePath ?: "none"}"
        )
        updateHud(
            "VERIFIED: ${level.label}\n" +
                "Backend=${diag.optString("activeBackend")} | API=${diag.optString("actualApiVersion")}\n" +
                "ProofOK=${proof?.optBoolean("ok")} | NonBgPx=${proof?.optInt("nonBackgroundPixels")} | Hash=${proof?.optString("fnv1aHash")}\n" +
                "Saved: ${exported.jsonFile.name}"
        )
    }

    private fun computeAcceptanceLevel(
        libVer: JSONObject,
        diag: JSONObject,
        proof: JSONObject?,
        bench: JSONObject?
    ): StarGfxAcceptanceLevel {
        if (!libVer.optBoolean("stargfxLoaded") || !libVer.optBoolean("bridgeLoaded")) {
            return StarGfxAcceptanceLevel.BUILT
        }
        if (!diag.optBoolean("instanceInitialized")) {
            return StarGfxAcceptanceLevel.NATIVE_LIBRARY_LOADED
        }
        if (!diag.optBoolean("deviceInitialized")) {
            return StarGfxAcceptanceLevel.RUNTIME_INITIALIZED
        }
        if (!diag.optBoolean("surfaceInitialized") || !diag.optBoolean("pipelineCreated") ||
            !diag.optBoolean("frameSubmitted") || proof?.optBoolean("ok") != true
        ) {
            return StarGfxAcceptanceLevel.BACKEND_INITIALIZED
        }
        if (bench != null && bench.optBoolean("ok")) {
            return StarGfxAcceptanceLevel.STAR_LAUNCHER_INTEGRATED
        }
        return StarGfxAcceptanceLevel.STAR_LAUNCHER_INTEGRATED
    }

    private fun updateHud(textValue: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                hudText.text = textValue
            }
        }
    }
}
