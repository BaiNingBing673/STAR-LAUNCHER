package com.star.launcher.diagnostics

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.star.launcher.compat.UiPerformanceProfileManager
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kdt.pojavlaunch.BuildConfig
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Snapshot of live launcher telemetry (Section 12).
 */
data class PerformanceTelemetrySnapshot(
    val fps: Int = 60,
    val avgFrameMs: Float = 0f,
    val p95FrameMs: Float = 0f,
    val uiWorkloadPercent: Int = 0,
    val jankFrameCount: Int = 0,
    val javaHeapUsedMb: Int = 0,
    val nativeHeapUsedMb: Int = 0,
    val gcCount: Long = 0L,
    val startupDurationMs: Long = 0L,
    val lastNavRoute: String = "home",
    val lastNavTransitionMs: Long = 0L,
    val lastBgTaskName: String = "idle",
    val lastBgTaskDurationMs: Long = 0L,
    val activeProfileLabel: String = "BALANCED"
)

/**
 * Lightweight, development-only telemetry tracker and overlay (Section 12).
 *
 * Strictly gated behind [BuildConfig.DEBUG] and a developer toggle so production builds
 * incur zero runtime or allocation overhead.
 */
object PerformanceTelemetryTracker {
    private const val PREFS_NAME = "star_dev_telemetry_prefs"
    private const val KEY_OVERLAY_ENABLED = "dev_perf_overlay_enabled"
    private const val SAMPLE_WINDOW_FRAMES = 60
    private const val SNAPSHOT_PUBLISH_INTERVAL_NS = 500_000_000L // 2 Hz UI update to avoid recomposition noise

    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    private val _snapshotFlow = MutableStateFlow(PerformanceTelemetrySnapshot())
    val snapshotFlow: StateFlow<PerformanceTelemetrySnapshot> = _snapshotFlow.asStateFlow()

    private val frameSamplesNs = LongArray(SAMPLE_WINDOW_FRAMES)
    private var sampleWriteIndex = 0
    private var sampleCount = 0
    private var lastFrameTimeNs = 0L
    private var lastPublishTimeNs = 0L
    private var totalJankFrames = 0

    @Volatile
    private var processStartRealtimeMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var startupDurationMs: Long = 0L

    @Volatile
    private var navStartRealtimeMs: Long = 0L

    @Volatile
    private var pendingNavRoute: String? = null

    @Volatile
    private var lastNavRoute: String = "home"

    @Volatile
    private var lastNavTransitionMs: Long = 0L

    @Volatile
    private var lastBgTaskName: String = "idle"

    @Volatile
    private var lastBgTaskDurationMs: Long = 0L

    val isAvailableInBuild: Boolean
        get() = BuildConfig.DEBUG

    fun ensureInitialized(context: Context) {
        if (!isAvailableInBuild) {
            _enabledFlow.value = false
            return
        }
        val appContext = context.applicationContext ?: context
        val saved = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_ENABLED, false)
        _enabledFlow.value = saved
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        if (!isAvailableInBuild) return
        val appContext = context.applicationContext ?: context
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_ENABLED, enabled)
            .apply()
        _enabledFlow.value = enabled
    }

    fun markStartupCompleted() {
        if (startupDurationMs == 0L) {
            startupDurationMs = (SystemClock.elapsedRealtime() - processStartRealtimeMs).coerceAtLeast(1L)
        }
    }

    fun onNavigationStarted(targetRoute: String) {
        if (!isAvailableInBuild) return
        navStartRealtimeMs = SystemClock.elapsedRealtime()
        pendingNavRoute = targetRoute
    }

    fun onNavigationSettled(route: String) {
        if (!isAvailableInBuild) return
        val start = navStartRealtimeMs
        if (start > 0L && (pendingNavRoute == null || pendingNavRoute == route)) {
            lastNavRoute = route
            lastNavTransitionMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            navStartRealtimeMs = 0L
            pendingNavRoute = null
        }
    }

    fun recordBackgroundTask(taskName: String, durationMs: Long) {
        if (!isAvailableInBuild) return
        lastBgTaskName = taskName
        lastBgTaskDurationMs = durationMs.coerceAtLeast(0L)
    }

    internal fun onFrameRendered(frameTimeNanos: Long) {
        if (!isAvailableInBuild || !_enabledFlow.value) return
        val pendingRoute = pendingNavRoute
        if (pendingRoute != null) {
            onNavigationSettled(pendingRoute)
        }
        val prev = lastFrameTimeNs
        lastFrameTimeNs = frameTimeNanos
        if (prev == 0L) {
            lastPublishTimeNs = frameTimeNanos
            return
        }
        val deltaNs = (frameTimeNanos - prev).coerceAtLeast(1_000_000L)
        if (deltaNs < 250_000_000L) { // Ignore idle gaps > 250ms when UI is static
            frameSamplesNs[sampleWriteIndex] = deltaNs
            sampleWriteIndex = (sampleWriteIndex + 1) % SAMPLE_WINDOW_FRAMES
            if (sampleCount < SAMPLE_WINDOW_FRAMES) sampleCount++
            if (deltaNs > 17_500_000L) {
                totalJankFrames++
            }
        }

        if (frameTimeNanos - lastPublishTimeNs >= SNAPSHOT_PUBLISH_INTERVAL_NS) {
            lastPublishTimeNs = frameTimeNanos
            publishSnapshot()
        }
    }

    internal fun resetFrameClock() {
        lastFrameTimeNs = 0L
    }

    private fun publishSnapshot() {
        val count = sampleCount
        var sumNs = 0L
        val sortedScratch = LongArray(count)
        for (i in 0 until count) {
            val v = frameSamplesNs[i]
            sumNs += v
            sortedScratch[i] = v
        }
        sortedScratch.sort()
        val avgNs = if (count > 0) sumNs / count else 16_666_667L
        val p95Ns = if (count > 0) {
            val idx = ((count - 1) * 0.95f).roundToInt().coerceIn(0, count - 1)
            sortedScratch[idx]
        } else {
            16_666_667L
        }
        val avgMs = avgNs / 1_000_000f
        val p95Ms = p95Ns / 1_000_000f
        val fps = if (avgMs > 0.1f) (1000f / avgMs).roundToInt().coerceIn(1, 144) else 60
        val workload = ((avgMs / 16.67f) * 100f).roundToInt().coerceIn(1, 100)

        val runtime = Runtime.getRuntime()
        val javaUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).toInt()
        val nativeUsedMb = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()
        val gcCount = runCatching {
            Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)

        _snapshotFlow.value = PerformanceTelemetrySnapshot(
            fps = fps,
            avgFrameMs = avgMs,
            p95FrameMs = p95Ms,
            uiWorkloadPercent = workload,
            jankFrameCount = totalJankFrames,
            javaHeapUsedMb = javaUsedMb,
            nativeHeapUsedMb = nativeUsedMb,
            gcCount = gcCount,
            startupDurationMs = startupDurationMs,
            lastNavRoute = lastNavRoute,
            lastNavTransitionMs = lastNavTransitionMs,
            lastBgTaskName = lastBgTaskName,
            lastBgTaskDurationMs = lastBgTaskDurationMs,
            activeProfileLabel = UiPerformanceProfileManager.currentSpec().profile.label
        )
    }
}

/**
 * Compact developer-only performance overlay pill (Section 12).
 */
@Composable
fun PerformanceMonitorOverlay(
    modifier: Modifier = Modifier
) {
    if (!PerformanceTelemetryTracker.isAvailableInBuild) return
    val enabled by PerformanceTelemetryTracker.enabledFlow.collectAsState()
    if (!enabled) return

    val snapshot by PerformanceTelemetryTracker.snapshotFlow.collectAsState()

    DisposableEffect(enabled) {
        val choreographer = Choreographer.getInstance()
        var active = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                PerformanceTelemetryTracker.onFrameRendered(frameTimeNanos)
                choreographer.postFrameCallback(this)
            }
        }
        PerformanceTelemetryTracker.resetFrameClock()
        choreographer.postFrameCallback(callback)
        onDispose {
            active = false
            choreographer.removeFrameCallback(callback)
        }
    }

    val fpsColor = when {
        snapshot.fps >= 55 -> AetherColors.TierVerifiedFg
        snapshot.fps >= 40 -> Color(0xFFFFB74D)
        else -> AetherColors.TierBlockedFg
    }

    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xD90B101B),
        border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${snapshot.fps} FPS",
                    style = AetherMonoStyle.copy(color = fpsColor, fontSize = 11.sp)
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1fms (P95 %.1fms) · UI %d%% · [%s]",
                        snapshot.avgFrameMs,
                        snapshot.p95FrameMs,
                        snapshot.uiWorkloadPercent,
                        snapshot.activeProfileLabel
                    ),
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark, fontSize = 10.sp)
                )
            }
            Text(
                text = "Heap J:${snapshot.javaHeapUsedMb}M N:${snapshot.nativeHeapUsedMb}M · GC:${snapshot.gcCount} · Jank:${snapshot.jankFrameCount}",
                style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark, fontSize = 9.5.sp)
            )
            Text(
                text = "Start:${snapshot.startupDurationMs}ms · Nav(${snapshot.lastNavRoute}):${snapshot.lastNavTransitionMs}ms · BG(${snapshot.lastBgTaskName}):${snapshot.lastBgTaskDurationMs}ms",
                style = AetherMonoStyle.copy(color = AetherColors.AccentCyan, fontSize = 9.5.sp)
            )
        }
    }
}
