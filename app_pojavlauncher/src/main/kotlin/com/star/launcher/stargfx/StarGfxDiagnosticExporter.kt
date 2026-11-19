package com.star.launcher.stargfx

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import com.star.launcher.diagnostics.BenchmarkHarness
import com.star.launcher.diagnostics.SensitiveLogRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ExportedStarGfxReports(
    val sessionId: String,
    val jsonFile: File,
    val textFile: File,
    val pngFile: File?
)

object StarGfxDiagnosticExporter {

    fun diagnosticsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "stargfx_diagnostics").apply { mkdirs() }
    }

    fun convertPpmToPng(ppmFile: File, pngFile: File): Boolean {
        if (!ppmFile.isFile) return false
        return runCatching {
            ppmFile.inputStream().buffered().use { ins ->
                fun readToken(): String {
                    val sb = StringBuilder()
                    var b = ins.read()
                    while (b != -1 && b <= 32) b = ins.read()
                    while (b > 32) {
                        sb.append(b.toChar())
                        b = ins.read()
                    }
                    return sb.toString()
                }
                val magic = readToken()
                if (magic != "P6") return false
                val w = readToken().toInt()
                val h = readToken().toInt()
                val maxVal = readToken().toInt()
                if (w <= 0 || h <= 0 || maxVal <= 0) return false
                val rgb = ByteArray(w * h * 3)
                var off = 0
                while (off < rgb.size) {
                    val n = ins.read(rgb, off, rgb.size - off)
                    if (n <= 0) break
                    off += n
                }
                val pixels = IntArray(w * h)
                for (i in 0 until w * h) {
                    val r = rgb[i * 3].toInt() and 0xFF
                    val g = rgb[i * 3 + 1].toInt() and 0xFF
                    val b = rgb[i * 3 + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                FileOutputStream(pngFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bmp.recycle()
                true
            }
        }.getOrDefault(false)
    }

    fun exportReport(
        context: Context,
        sessionId: String = UUID.randomUUID().toString(),
        backendSelected: String,
        libVerification: JSONObject,
        systemProbe: JSONObject,
        runtimeDiag: JSONObject,
        renderProof: JSONObject?,
        benchmarkShort: JSONObject?,
        benchmarkSustained: JSONObject?,
        acceptanceLevel: StarGfxAcceptanceLevel,
        runtimeErrors: List<String>,
        crashInfo: String?,
        capturedPng: File?
    ): ExportedStarGfxReports {
        val dir = diagnosticsDir(context)
        val jsonFile = File(dir, "star_gfx_diagnostics_${sessionId}.json")
        val textFile = File(dir, "star_gfx_diagnostics_${sessionId}.txt")

        val nowMs = System.currentTimeMillis()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val rt = Runtime.getRuntime()
        val dbgMem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        val root = JSONObject().apply {
            put("sessionId", sessionId)
            put("timestampIso", iso)
            put("timestampEpochMs", nowMs)
            put("acceptanceLevel", acceptanceLevel.label)
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("androidVersion", Build.VERSION.RELEASE)
                put("androidApiLevel", Build.VERSION.SDK_INT)
                put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("cpuCores", Runtime.getRuntime().availableProcessors())
                put("cpuHardware", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE)
            })
            put("starGfxVersion", runtimeDiag.optString("starGfxVersion", libVerification.optString("apiVersion", "0.1.0")))
            put("libraryVerification", libVerification)
            put("backendSelected", backendSelected)
            put("backendActuallyInitialized", runtimeDiag.optString("activeBackend", "NONE"))
            put("systemGraphicsProbe", systemProbe)
            put("runtimeDiagnostics", runtimeDiag)
            if (renderProof != null) put("renderVerificationProof", renderProof)
            if (capturedPng != null) put("capturedImagePath", capturedPng.absolutePath)
            if (benchmarkShort != null) put("benchmarkShort", benchmarkShort)
            if (benchmarkSustained != null) put("benchmarkSustained", benchmarkSustained)
            put("memoryConsumption", JSONObject().apply {
                put("javaHeapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L))
                put("javaHeapMaxMb", rt.maxMemory() / (1024L * 1024L))
                put("nativeHeapAllocatedMb", Debug.getNativeHeapAllocatedSize() / (1024L * 1024L))
                put("totalPssMb", dbgMem.totalPss.toLong() / 1024L)
                put("vmRssKb", runtimeDiag.optLong("vmRssKb", 0L))
                put("systemAvailRamMb", memInfo.availMem / (1024L * 1024L))
                put("systemTotalRamMb", memInfo.totalMem / (1024L * 1024L))
                put("kgslGpuBusyPct", BenchmarkHarness.readKgslBusyPercent() ?: -1)
                put("maxThermalCelsius", BenchmarkHarness.readMaxThermalCelsius() ?: -1f)
            })
            put("runtimeErrors", JSONArray(runtimeErrors.map { SensitiveLogRedactor.redact(it) }))
            put("crashInfo", crashInfo?.let { SensitiveLogRedactor.redact(it) } ?: JSONObject.NULL)
        }

        val redactedJson = SensitiveLogRedactor.redact(root.toString(2))
        jsonFile.writeText(redactedJson)

        val txt = buildString {
            appendLine("=== STAR GFX VERIFICATION & DIAGNOSTIC REPORT ===")
            appendLine("Session ID:          $sessionId")
            appendLine("Timestamp (UTC):     $iso")
            appendLine("Acceptance Level:    ${acceptanceLevel.label}")
            appendLine("Device:              ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("ABI / CPU:           ${Build.SUPPORTED_ABIS.joinToString()} | ${Runtime.getRuntime().availableProcessors()} cores")
            appendLine("STAR GFX Version:    ${root.optString("starGfxVersion")}")
            appendLine("libstargfx.so:       ${libVerification.optBoolean("stargfxLoaded")} (${libVerification.optString("stargfxPath")})")
            appendLine("libstargfx_bridge:   ${libVerification.optBoolean("bridgeLoaded")} (${libVerification.optString("bridgePath")})")
            appendLine("Backend Selected:    $backendSelected")
            appendLine("Backend Initialized: ${runtimeDiag.optString("activeBackend", "NONE")}")
            appendLine("Actual API Version:  ${runtimeDiag.optString("actualApiVersion", "N/A")}")
            appendLine("GPU Vendor:          ${runtimeDiag.optString("gpuVendor", "N/A")}")
            appendLine("GPU Renderer:        ${runtimeDiag.optString("gpuRenderer", "N/A")}")
            appendLine("GPU Driver Version:  ${runtimeDiag.optString("gpuDriverVersion", "N/A")}")
            appendLine("Init Flags:          instance=${runtimeDiag.optBoolean("instanceInitialized")} device=${runtimeDiag.optBoolean("deviceInitialized")} surface=${runtimeDiag.optBoolean("surfaceInitialized")}")
            appendLine("Pipeline Flags:      shader=${runtimeDiag.optBoolean("shaderCompiled")} (${runtimeDiag.optLong("lastShaderCompileUs")} us) pipeline=${runtimeDiag.optBoolean("pipelineCreated")} (${runtimeDiag.optLong("lastPipelineCreateUs")} us)")
            appendLine("Submission Flags:    submitted=${runtimeDiag.optBoolean("frameSubmitted")} presented=${runtimeDiag.optBoolean("framePresented")} draws=${runtimeDiag.optLong("drawCallsSubmitted")} framesPresented=${runtimeDiag.optLong("framesPresented")}")
            appendLine("Swapchain:           ${runtimeDiag.optInt("swapchainWidth")}x${runtimeDiag.optInt("swapchainHeight")} images=${runtimeDiag.optInt("swapchainImageCount")}")
            if (renderProof != null) {
                appendLine("Render Proof:        ok=${renderProof.optBoolean("ok")} nonBgPixels=${renderProof.optInt("nonBackgroundPixels")} buckets=${renderProof.optInt("uniqueColorBuckets")} hash=${renderProof.optString("fnv1aHash")}")
            }
            if (capturedPng != null) {
                appendLine("Captured PNG:        ${capturedPng.absolutePath}")
            }
            if (benchmarkShort != null && benchmarkShort.optBoolean("ok")) {
                appendLine("Short Benchmark:     avgFps=${"%.2f".format(benchmarkShort.optDouble("avgFps"))} low1=${"%.2f".format(benchmarkShort.optDouble("low1Fps"))} p50=${"%.3f".format(benchmarkShort.optDouble("p50Ms"))}ms p95=${"%.3f".format(benchmarkShort.optDouble("p95Ms"))}ms p99=${"%.3f".format(benchmarkShort.optDouble("p99Ms"))}ms")
            }
            if (benchmarkSustained != null && benchmarkSustained.optBoolean("ok")) {
                appendLine("Sustained Benchmark: avgFps=${"%.2f".format(benchmarkSustained.optDouble("avgFps"))} low1=${"%.2f".format(benchmarkSustained.optDouble("low1Fps"))} p50=${"%.3f".format(benchmarkSustained.optDouble("p50Ms"))}ms p95=${"%.3f".format(benchmarkSustained.optDouble("p95Ms"))}ms p99=${"%.3f".format(benchmarkSustained.optDouble("p99Ms"))}ms")
            }
            appendLine("Memory:              VmRSS=${runtimeDiag.optLong("vmRssKb")} KB | PSS=${dbgMem.totalPss / 1024} MB | Java=${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} MB")
            if (runtimeErrors.isNotEmpty()) {
                appendLine("Runtime Errors:")
                runtimeErrors.forEach { appendLine("  - ${SensitiveLogRedactor.redact(it)}") }
            }
            if (!crashInfo.isNullOrBlank()) {
                appendLine("Crash Info:          ${SensitiveLogRedactor.redact(crashInfo)}")
            }
        }
        textFile.writeText(SensitiveLogRedactor.redact(txt))
        return ExportedStarGfxReports(sessionId, jsonFile, textFile, capturedPng)
    }
}
