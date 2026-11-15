package com.star.launcher.diagnostics

import android.content.Context
import android.os.Debug
import com.star.launcher.compat.AndroidDeviceCapabilityProbe
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import java.io.File
import kotlin.math.ceil

data class FrameStatsSummary(
    val sampleCount: Int,
    val avgFrameTimeMs: Double,
    val p50FrameTimeMs: Double,
    val p95FrameTimeMs: Double,
    val p99FrameTimeMs: Double,
    val onePercentLowFrameTimeMs: Double,
    val avgFps: Double,
    val onePercentLowFps: Double
)

data class BenchmarkSnapshot(
    val capturedAtEpochMs: Long,
    val capabilities: DeviceCapabilitiesSnapshot,
    val cachedProbeDurationUs: Long,
    val javaHeapUsedMb: Long,
    val javaHeapMaxMb: Long,
    val nativeHeapAllocatedMb: Long,
    val processPssMb: Long,
    val kgslGpuBusyPercent: Int?,
    val thermalCelsiusMax: Float?,
    val startupSamples: List<StartupProfiler.StageSample>
)

/**
 * Reproducible on-device performance & telemetry harness for STAR Launcher.
 * Captures real hardware metrics (RAM, native PSS, Qualcomm KGSL busy %, thermal zones,
 * startup profiler stage timings, and frame-time statistics) without fabricating values.
 */
object BenchmarkHarness {

    fun parseKgslBusyPercent(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removeSuffix("%").trim()
        val value = cleaned.toIntOrNull() ?: return null
        return value.coerceIn(0, 100)
    }

    fun parseThermalCelsius(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val num = raw.trim().toFloatOrNull() ?: return null
        val celsius = if (num > 200f) num / 1000f else num
        return if (celsius in 10f..110f) celsius else null
    }

    /**
     * Computes average FPS, 1% low FPS, and frame-time percentiles from real frame durations (ms).
     * 1% low FPS is derived from the mean duration of the slowest 1% of frames (at least 1 frame).
     */
    fun computeFrameStats(frameTimesMs: List<Double>): FrameStatsSummary? {
        val valid = frameTimesMs.filter { it.isFinite() && it > 0.0 }
        if (valid.isEmpty()) return null
        val sortedAsc = valid.sorted()
        val count = sortedAsc.size
        val avgMs = valid.sum() / count.toDouble()
        val p50 = percentile(sortedAsc, 0.50)
        val p95 = percentile(sortedAsc, 0.95)
        val p99 = percentile(sortedAsc, 0.99)
        val slowestCount = ceil(count * 0.01).toInt().coerceAtLeast(1)
        val slowestSlice = sortedAsc.takeLast(slowestCount)
        val onePercentLowMs = slowestSlice.sum() / slowestSlice.size.toDouble()
        return FrameStatsSummary(
            sampleCount = count,
            avgFrameTimeMs = avgMs,
            p50FrameTimeMs = p50,
            p95FrameTimeMs = p95,
            p99FrameTimeMs = p99,
            onePercentLowFrameTimeMs = onePercentLowMs,
            avgFps = if (avgMs > 0.0) 1000.0 / avgMs else 0.0,
            onePercentLowFps = if (onePercentLowMs > 0.0) 1000.0 / onePercentLowMs else 0.0
        )
    }

    private fun percentile(sortedAsc: List<Double>, fraction: Double): Double {
        if (sortedAsc.isEmpty()) return 0.0
        val idx = ((sortedAsc.size - 1) * fraction).toInt().coerceIn(0, sortedAsc.size - 1)
        return sortedAsc[idx]
    }

    fun readKgslBusyPercent(): Int? {
        val node = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        if (!node.isFile || !node.canRead()) return null
        return runCatching { parseKgslBusyPercent(node.readText()) }.getOrNull()
    }

    fun readMaxThermalCelsius(): Float? {
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        var maxCelsius: Float? = null
        for (zone in zones.take(16)) {
            val tempFile = File(zone, "temp")
            if (!tempFile.isFile || !tempFile.canRead()) continue
            val parsed = runCatching { parseThermalCelsius(tempFile.readText()) }.getOrNull() ?: continue
            maxCelsius = if (maxCelsius == null) parsed else maxOf(maxCelsius, parsed)
        }
        return maxCelsius
    }

    fun captureSnapshot(context: Context): BenchmarkSnapshot {
        val probe = AndroidDeviceCapabilityProbe(context.applicationContext)
        val t0 = System.nanoTime()
        val caps = probe.probe()
        val probeUs = ((System.nanoTime() - t0) / 1_000L).coerceAtLeast(0L)

        val rt = Runtime.getRuntime()
        val javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val javaMaxMb = rt.maxMemory() / (1024L * 1024L)
        val nativeAllocatedMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        val memInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val totalPssMb = memInfo.totalPss.toLong() / 1024L

        return BenchmarkSnapshot(
            capturedAtEpochMs = System.currentTimeMillis(),
            capabilities = caps,
            cachedProbeDurationUs = probeUs,
            javaHeapUsedMb = javaUsedMb,
            javaHeapMaxMb = javaMaxMb,
            nativeHeapAllocatedMb = nativeAllocatedMb,
            processPssMb = totalPssMb,
            kgslGpuBusyPercent = readKgslBusyPercent(),
            thermalCelsiusMax = readMaxThermalCelsius() ?: caps.thermalCelsiusMax,
            startupSamples = StartupProfiler.snapshotSamples()
        )
    }

    fun formatMarkdownReport(snapshot: BenchmarkSnapshot): String {
        val caps = snapshot.capabilities
        return buildString {
            appendLine("# STAR Launcher On-Device Benchmark Snapshot")
            appendLine()
            appendLine("- **Captured Epoch Ms:** `${snapshot.capturedAtEpochMs}`")
            appendLine("- **SoC / CPU:** `${caps.cpuHardwareModel}` (${caps.cpuCoreCount} cores, max ${caps.cpuMaxFreqMhz} MHz, ABI `${caps.cpuAbiPrimary}`)")
            appendLine("- **GPU / APIs:** `${caps.gpuVendor}` / `${caps.gpuRenderer}` | `${caps.glVersion}` | Vulkan `${caps.vulkanVersion ?: "Unsupported"}`")
            appendLine("- **Display:** `${caps.screenWidthPx}x${caps.screenHeightPx}` @ `${caps.supportedRefreshRatesHz.joinToString("/")}` Hz")
            appendLine("- **Device RAM:** `${caps.availableRamMb}` MB free / `${caps.totalRamMb}` MB total")
            appendLine("- **Probe Latency (cached):** `${snapshot.cachedProbeDurationUs}` us")
            appendLine("- **Process Memory:** Java `${snapshot.javaHeapUsedMb}/${snapshot.javaHeapMaxMb}` MB | Native Alloc `${snapshot.nativeHeapAllocatedMb}` MB | Total PSS `${snapshot.processPssMb}` MB")
            appendLine("- **GPU Busy (KGSL):** `${snapshot.kgslGpuBusyPercent?.let { "$it%" } ?: "Unavailable"}`")
            appendLine("- **Max Thermal Sensor:** `${snapshot.thermalCelsiusMax?.let { "%.1f C".format(it) } ?: "Unavailable"}`")
            appendLine()
            appendLine("## Recorded Startup & Launch Stages")
            if (snapshot.startupSamples.isEmpty()) {
                appendLine("_No stage samples recorded yet._")
            } else {
                appendLine("| Elapsed | Stage | Duration | Detail |")
                appendLine("|---|---|---|---|")
                for (s in snapshot.startupSamples) {
                    appendLine("| +${s.elapsedSinceAppStartMs}ms | `${s.stage}` | ${s.durationMs}ms | ${s.detail ?: "-"} |")
                }
            }
        }
    }

    fun writeSnapshotReport(context: Context, outputDir: File? = null): File {
        val dir = outputDir ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "star_benchmarks")
        if (!dir.exists()) dir.mkdirs()
        val snapshot = captureSnapshot(context)
        val outFile = File(dir, "benchmark-${snapshot.capturedAtEpochMs}.md")
        outFile.writeText(formatMarkdownReport(snapshot))
        return outFile
    }
}
