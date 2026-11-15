package com.star.launcher.diagnostics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight startup and launch stage profiler.
 * Records monotonic timing of launcher initialization and launch pipeline stages
 * without altering validation guarantees.
 */
object StartupProfiler {

    private const val TAG = "STAR-PROFILE"
    private const val MAX_SAMPLES = 64

    data class StageSample(
        val stage: String,
        val durationMs: Long,
        val elapsedSinceAppStartMs: Long,
        val threadName: String,
        val detail: String?,
        val recordedAtEpochMs: Long
    )

    private val appStartNanos = AtomicLong(0L)
    private val samples = CopyOnWriteArrayList<StageSample>()

    @JvmStatic
    fun markAppStart() {
        val now = elapsedNanos()
        appStartNanos.compareAndSet(0L, now)
    }

    @JvmStatic
    fun beginStage(): Long {
        markAppStart()
        return elapsedNanos()
    }

    @JvmStatic
    @JvmOverloads
    fun endStage(stage: String, startNanos: Long, detail: String? = null): Long {
        val now = elapsedNanos()
        val durationMs = ((now - startNanos).coerceAtLeast(0L)) / 1_000_000L
        recordStage(stage = stage, durationMs = durationMs, detail = detail)
        return durationMs
    }

    @JvmStatic
    @JvmOverloads
    fun recordStage(stage: String, durationMs: Long, detail: String? = null) {
        val start = appStartNanos.get().let { if (it == 0L) elapsedNanos().also { s -> appStartNanos.compareAndSet(0L, s) } else it }
        val elapsedMs = ((elapsedNanos() - start).coerceAtLeast(0L)) / 1_000_000L
        val sample = StageSample(
            stage = stage,
            durationMs = durationMs.coerceAtLeast(0L),
            elapsedSinceAppStartMs = elapsedMs,
            threadName = Thread.currentThread().name,
            detail = detail,
            recordedAtEpochMs = System.currentTimeMillis()
        )
        samples.add(sample)
        while (samples.size > MAX_SAMPLES) {
            runCatching { samples.removeAt(0) }
        }
        val suffix = if (!detail.isNullOrBlank()) " detail=$detail" else ""
        runCatching {
            Log.i(TAG, "stage=$stage durationMs=${sample.durationMs} elapsedSinceAppStartMs=$elapsedMs thread=${sample.threadName}$suffix")
        }
    }

    @JvmStatic
    fun snapshotSamples(): List<StageSample> = samples.toList()

    @JvmStatic
    fun resetForTest() {
        appStartNanos.set(0L)
        samples.clear()
    }

    @JvmStatic
    fun formatSummaryReport(): String {
        val list = samples.toList()
        if (list.isEmpty()) return "No startup profile samples recorded."
        return buildString {
            appendLine("=== STAR Launcher Startup & Launch Profile ===")
            for (s in list) {
                val detailPart = if (!s.detail.isNullOrBlank()) " (${s.detail})" else ""
                appendLine("  +${s.elapsedSinceAppStartMs}ms | ${s.stage}: ${s.durationMs}ms$detailPart")
            }
        }
    }

    private fun elapsedNanos(): Long {
        return try {
            SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }
}
