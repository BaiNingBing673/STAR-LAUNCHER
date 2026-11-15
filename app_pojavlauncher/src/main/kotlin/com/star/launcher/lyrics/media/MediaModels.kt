package com.star.launcher.lyrics.media

import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.SystemClock
import com.star.launcher.lyrics.engine.LyricsTrackKey

data class MediaControlCapability(
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canSeek: Boolean = false
)

data class MediaTrackInfo(
    val sourcePackage: String = "",
    val sourceAppLabel: String = "",
    val isStarPlayer: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkKey: String = "",
    val artworkBitmap: Bitmap? = null,
    val accentColor: Int = DEFAULT_ACCENT_COLOR
) {
    val trackKey: String
        get() = LyricsTrackKey.build(title, artist, durationMs)

    val hasTrack: Boolean
        get() = title.isNotBlank()

    companion object {
        const val DEFAULT_ACCENT_COLOR = 0xFF5EE2FF.toInt()
    }
}

data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val stateCode: Int = PlaybackState.STATE_NONE,
    val positionMs: Long = 0L,
    val positionUpdateElapsedMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val durationMs: Long = 0L,
    val hasRealPosition: Boolean = false,
    val capabilities: MediaControlCapability = MediaControlCapability()
) {
    fun currentPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!hasRealPosition) {
            return if (positionUpdateElapsedMs > 0L && isPlaying) {
                val delta = ((nowElapsedMs - positionUpdateElapsedMs).coerceAtLeast(0L) * playbackSpeed.coerceAtLeast(0.1f)).toLong()
                val est = positionMs + delta
                if (durationMs > 0L) est.coerceIn(0L, durationMs) else est.coerceAtLeast(0L)
            } else {
                positionMs.coerceAtLeast(0L)
            }
        }
        val base = positionMs.coerceAtLeast(0L)
        if (!isPlaying || positionUpdateElapsedMs <= 0L) {
            return if (durationMs > 0L) base.coerceIn(0L, durationMs) else base
        }
        val elapsedDelta = (nowElapsedMs - positionUpdateElapsedMs).coerceAtLeast(0L)
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1.0f
        val interpolated = base + (elapsedDelta * speed).toLong()
        return if (durationMs > 0L) interpolated.coerceIn(0L, durationMs) else interpolated.coerceAtLeast(0L)
    }
}

data class ActiveMediaApp(
    val packageName: String,
    val appLabel: String,
    val isPlaying: Boolean,
    val trackTitle: String
)

data class UnifiedMediaState(
    val hasActiveSession: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val track: MediaTrackInfo = MediaTrackInfo(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val activeApps: List<ActiveMediaApp> = emptyList()
)
