package com.star.launcher.lyrics.engine

import java.security.MessageDigest
import java.util.Locale

enum class LyricsFormat {
    WORD_SYNCED,
    LINE_SYNCED,
    PLAIN,
    NONE
}

enum class SyncConfidence {
    /** Synchronized directly against playback position reported by the media source. */
    EXACT_POSITION,
    /** Source did not report live playback position; using clearly labeled elapsed fallback. */
    APPROXIMATE_CLOCK,
    /** Only plain/unsynced text is available; timing is not fabricated. */
    PLAIN_UNSYNCED,
    /** No lyrics available for the active track. */
    UNAVAILABLE
}

data class LyricWordSpan(
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val text: String
)

data class LyricLine(
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val text: String,
    val words: List<LyricWordSpan> = emptyList()
)

data class LyricsDocument(
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val format: LyricsFormat,
    val lines: List<LyricLine> = emptyList(),
    val plainText: String = "",
    val sourceName: String = "",
    val attribution: String = "",
    val isCustom: Boolean = false,
    val embeddedOffsetMs: Long = 0L
) {
    val isSynchronized: Boolean
        get() = (format == LyricsFormat.LINE_SYNCED || format == LyricsFormat.WORD_SYNCED) && lines.isNotEmpty()
}

data class SyncedLyricsFrame(
    val trackKey: String = "",
    val format: LyricsFormat = LyricsFormat.NONE,
    val syncConfidence: SyncConfidence = SyncConfidence.UNAVAILABLE,
    val statusLabel: String = "No lyrics",
    val activeIndex: Int = -1,
    val previousLine: String? = null,
    val currentLine: String? = null,
    val nextLine: String? = null,
    val activeWords: List<LyricWordSpan> = emptyList(),
    val activeWordIndex: Int = -1,
    val currentWordProgress: Float = 0f,
    val lineProgress: Float = 0f,
    val effectivePositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val attribution: String = ""
)

sealed interface LyricsLookupOutcome {
    data class Found(val document: LyricsDocument, val fromCache: Boolean) : LyricsLookupOutcome
    data class NotFound(val reason: String, val fromCache: Boolean = false) : LyricsLookupOutcome
    data class RateLimited(val retryAfterSeconds: Long) : LyricsLookupOutcome
    data class NetworkFailure(val message: String) : LyricsLookupOutcome
}

object LyricsTrackKey {
    fun build(title: String, artist: String, durationMs: Long = 0L): String {
        val normTitle = normalize(title)
        val normArtist = normalize(artist)
        val bucketSec = if (durationMs > 0L) (durationMs / 1000L) else 0L
        return "$normArtist|$normTitle|$bucketSec"
    }

    fun sha256(trackKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(trackKey.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    fun normalize(raw: String): String {
        return raw.lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
