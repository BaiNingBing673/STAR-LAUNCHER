package com.star.launcher.lyrics.overlay

import android.os.Debug
import com.star.launcher.lyrics.config.IslandDisplayMode
import java.util.Locale

data class LiveLyricsBenchmarkSnapshot(
    val mode: IslandDisplayMode,
    val sampleCount: Int,
    val avgDrawTimeNs: Long,
    val maxDrawTimeNs: Long,
    val avgSyncTickNs: Long,
    val estimatedOverlayFps: Float,
    val usedJavaHeapKb: Long,
    val nativeHeapKb: Long
) {
    fun formatSummary(): String {
        return String.format(
            Locale.US,
            "mode=%s samples=%d drawAvg=%.3fms drawMax=%.3fms syncAvg=%.3fms estFps=%.1f javaHeap=%dKB nativeHeap=%dKB",
            mode.name,
            sampleCount,
            avgDrawTimeNs / 1_000_000.0,
            maxDrawTimeNs / 1_000_000.0,
            avgSyncTickNs / 1_000_000.0,
            estimatedOverlayFps,
            usedJavaHeapKb,
            nativeHeapKb
        )
    }
}

/**
 * Lightweight runtime profiler for STAR Live Lyrics overlay draw cost, sync loop CPU time,
 * and heap footprint across Hidden / Compact / Expanded modes.
 */
class LiveLyricsBenchmarkMonitor {
    private var currentMode = IslandDisplayMode.HIDDEN
    private var drawCount = 0
    private var drawSumNs = 0L
    private var drawMaxNs = 0L

    private var syncCount = 0
    private var syncSumNs = 0L

    private var frameCount = 0
    private var firstFrameNs = 0L
    private var lastFrameNs = 0L

    @Synchronized
    fun setMode(mode: IslandDisplayMode) {
        if (currentMode != mode) {
            currentMode = mode
            resetCounters()
        }
    }

    @Synchronized
    fun recordDrawDurationNs(durationNs: Long) {
        if (durationNs < 0L) return
        drawCount++
        drawSumNs += durationNs
        if (durationNs > drawMaxNs) drawMaxNs = durationNs
    }

    @Synchronized
    fun recordSyncTickNs(durationNs: Long) {
        if (durationNs < 0L) return
        syncCount++
        syncSumNs += durationNs
    }

    @Synchronized
    fun recordFrameTimestampNs(frameTimeNs: Long) {
        if (firstFrameNs == 0L) {
            firstFrameNs = frameTimeNs
        }
        lastFrameNs = frameTimeNs
        frameCount++
    }

    @Synchronized
    fun snapshot(): LiveLyricsBenchmarkSnapshot {
        val avgDraw = if (drawCount > 0) drawSumNs / drawCount else 0L
        val avgSync = if (syncCount > 0) syncSumNs / syncCount else 0L
        val spanNs = (lastFrameNs - firstFrameNs).coerceAtLeast(0L)
        val fps = if (frameCount > 1 && spanNs > 0L) {
            ((frameCount - 1) * 1_000_000_000.0 / spanNs.toDouble()).toFloat()
        } else {
            0f
        }
        val runtime = Runtime.getRuntime()
        val usedJavaKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val nativeKb = runCatching { Debug.getNativeHeapAllocatedSize() / 1024L }.getOrDefault(0L)
        return LiveLyricsBenchmarkSnapshot(
            mode = currentMode,
            sampleCount = drawCount,
            avgDrawTimeNs = avgDraw,
            maxDrawTimeNs = drawMaxNs,
            avgSyncTickNs = avgSync,
            estimatedOverlayFps = fps,
            usedJavaHeapKb = usedJavaKb,
            nativeHeapKb = nativeKb
        )
    }

    @Synchronized
    fun resetCounters() {
        drawCount = 0
        drawSumNs = 0L
        drawMaxNs = 0L
        syncCount = 0
        syncSumNs = 0L
        frameCount = 0
        firstFrameNs = 0L
        lastFrameNs = 0L
    }
}
