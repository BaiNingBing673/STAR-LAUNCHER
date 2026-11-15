package com.star.launcher.lyrics.engine

import java.util.Locale

/**
 * Parses standard LRC (`[mm:ss.xx]`), multi-timestamp LRC, Enhanced word-level LRC (`<mm:ss.xx>`),
 * and plain text lyrics without ever fabricating timestamps for unsynchronized text.
 */
object LrcParser {
    private val LINE_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIMESTAMP_REGEX = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val METADATA_TAG_REGEX = Regex("""^\[([a-zA-Z]+):(.*)]$""")

    fun parse(
        rawContent: String,
        trackKey: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String = "",
        durationMs: Long = 0L,
        sourceName: String = "",
        attribution: String = "",
        isCustom: Boolean = false
    ): LyricsDocument {
        val normalized = rawContent.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) {
            return LyricsDocument(
                trackKey = trackKey,
                title = fallbackTitle,
                artist = fallbackArtist,
                album = fallbackAlbum,
                durationMs = durationMs,
                format = LyricsFormat.NONE,
                sourceName = sourceName,
                attribution = attribution,
                isCustom = isCustom
            )
        }

        var parsedTitle = fallbackTitle
        var parsedArtist = fallbackArtist
        var parsedAlbum = fallbackAlbum
        var embeddedOffsetMs = 0L
        var hasWordTimestamps = false

        val rawLines = mutableListOf<LyricLine>()
        val plainCollector = StringBuilder()

        for (rawLine in normalized.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val timestampMatches = LINE_TIMESTAMP_REGEX.findAll(line).toList()
            if (timestampMatches.isEmpty()) {
                val metaMatch = METADATA_TAG_REGEX.matchEntire(line)
                if (metaMatch != null) {
                    val tag = metaMatch.groupValues[1].lowercase(Locale.US)
                    val value = metaMatch.groupValues[2].trim()
                    when (tag) {
                        "ti" -> if (value.isNotEmpty()) parsedTitle = value
                        "ar" -> if (value.isNotEmpty()) parsedArtist = value
                        "al" -> if (value.isNotEmpty()) parsedAlbum = value
                        "offset" -> embeddedOffsetMs = value.toLongOrNull() ?: embeddedOffsetMs
                    }
                    continue
                }
                if (plainCollector.isNotEmpty()) plainCollector.append('\n')
                plainCollector.append(line)
                continue
            }

            // Strip all leading/inline line timestamps to recover lyric body
            val body = LINE_TIMESTAMP_REGEX.replace(line, "").trim()
            val wordMatches = WORD_TIMESTAMP_REGEX.findAll(body).toList()

            for (tsMatch in timestampMatches) {
                val lineStartMs = parseTimestampMs(
                    tsMatch.groupValues[1],
                    tsMatch.groupValues[2],
                    tsMatch.groupValues[3]
                ) ?: continue

                if (wordMatches.isNotEmpty()) {
                    val words = extractWords(body, wordMatches)
                    if (words.isNotEmpty()) {
                        hasWordTimestamps = true
                        val cleanText = words.joinToString("") { it.text }.trim()
                        if (cleanText.isNotEmpty()) {
                            rawLines.add(
                                LyricLine(
                                    startTimeMs = (lineStartMs - embeddedOffsetMs).coerceAtLeast(0L),
                                    endTimeMs = null,
                                    text = cleanText,
                                    words = words.map { w ->
                                        w.copy(
                                            startTimeMs = (w.startTimeMs - embeddedOffsetMs).coerceAtLeast(0L),
                                            endTimeMs = w.endTimeMs?.let { (it - embeddedOffsetMs).coerceAtLeast(0L) }
                                        )
                                    }
                                )
                            )
                        }
                        continue
                    }
                }

                val cleanBody = WORD_TIMESTAMP_REGEX.replace(body, "").trim()
                // Keep instrumental markers or non-empty lyric lines
                if (cleanBody.isNotEmpty()) {
                    rawLines.add(
                        LyricLine(
                            startTimeMs = (lineStartMs - embeddedOffsetMs).coerceAtLeast(0L),
                            endTimeMs = null,
                            text = cleanBody
                        )
                    )
                } else {
                    rawLines.add(
                        LyricLine(
                            startTimeMs = (lineStartMs - embeddedOffsetMs).coerceAtLeast(0L),
                            endTimeMs = null,
                            text = "♪"
                        )
                    )
                }
            }
        }

        if (rawLines.isNotEmpty()) {
            val sorted = rawLines.sortedBy { it.startTimeMs }
            val resolved = sorted.mapIndexed { index, current ->
                val nextStart = sorted.getOrNull(index + 1)?.startTimeMs
                val endMs = nextStart ?: if (durationMs > current.startTimeMs) durationMs else null
                val resolvedWords = if (current.words.isNotEmpty()) {
                    current.words.mapIndexed { wIdx, word ->
                        val nextWordStart = current.words.getOrNull(wIdx + 1)?.startTimeMs ?: endMs
                        word.copy(endTimeMs = nextWordStart)
                    }
                } else {
                    emptyList()
                }
                current.copy(endTimeMs = endMs, words = resolvedWords)
            }
            return LyricsDocument(
                trackKey = trackKey,
                title = parsedTitle,
                artist = parsedArtist,
                album = parsedAlbum,
                durationMs = durationMs,
                format = if (hasWordTimestamps) LyricsFormat.WORD_SYNCED else LyricsFormat.LINE_SYNCED,
                lines = resolved,
                plainText = resolved.joinToString("\n") { it.text },
                sourceName = sourceName,
                attribution = attribution,
                isCustom = isCustom,
                embeddedOffsetMs = embeddedOffsetMs
            )
        }

        val plain = plainCollector.toString().trim()
        return if (plain.isNotEmpty()) {
            LyricsDocument(
                trackKey = trackKey,
                title = parsedTitle,
                artist = parsedArtist,
                album = parsedAlbum,
                durationMs = durationMs,
                format = LyricsFormat.PLAIN,
                lines = emptyList(),
                plainText = plain,
                sourceName = sourceName,
                attribution = attribution,
                isCustom = isCustom
            )
        } else {
            LyricsDocument(
                trackKey = trackKey,
                title = parsedTitle,
                artist = parsedArtist,
                album = parsedAlbum,
                durationMs = durationMs,
                format = LyricsFormat.NONE,
                sourceName = sourceName,
                attribution = attribution,
                isCustom = isCustom
            )
        }
    }

    fun fromPlainText(
        plainText: String,
        trackKey: String,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        sourceName: String = "",
        attribution: String = "",
        isCustom: Boolean = false
    ): LyricsDocument {
        val cleaned = plainText.replace("\r\n", "\n").replace('\r', '\n').trim()
        return LyricsDocument(
            trackKey = trackKey,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            format = if (cleaned.isEmpty()) LyricsFormat.NONE else LyricsFormat.PLAIN,
            lines = emptyList(),
            plainText = cleaned,
            sourceName = sourceName,
            attribution = attribution,
            isCustom = isCustom
        )
    }

    fun toLrc(document: LyricsDocument): String {
        val sb = StringBuilder()
        if (document.title.isNotBlank()) sb.append("[ti:").append(document.title).append("]\n")
        if (document.artist.isNotBlank()) sb.append("[ar:").append(document.artist).append("]\n")
        if (document.album.isNotBlank()) sb.append("[al:").append(document.album).append("]\n")
        if (document.lines.isEmpty()) {
            sb.append(document.plainText)
            return sb.toString()
        }
        for (line in document.lines) {
            sb.append('[').append(formatTimestamp(line.startTimeMs)).append(']')
            if (line.words.isNotEmpty()) {
                for (w in line.words) {
                    sb.append('<').append(formatTimestamp(w.startTimeMs)).append('>').append(w.text)
                }
            } else {
                sb.append(line.text)
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun formatTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centis = (clamped % 1000L) / 10L
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centis)
    }

    private fun extractWords(body: String, matches: List<MatchResult>): List<LyricWordSpan> {
        val words = ArrayList<LyricWordSpan>(matches.size)
        for (i in matches.indices) {
            val m = matches[i]
            val startMs = parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: continue
            val textStart = m.range.last + 1
            val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            if (textStart <= textEnd && textStart <= body.length) {
                val segment = body.substring(textStart, textEnd)
                if (segment.isNotEmpty()) {
                    words.add(LyricWordSpan(startTimeMs = startMs, endTimeMs = null, text = segment))
                }
            }
        }
        return words
    }

    private fun parseTimestampMs(minRaw: String, secRaw: String, fracRaw: String): Long? {
        val minutes = minRaw.toLongOrNull() ?: return null
        val seconds = secRaw.toLongOrNull() ?: return null
        if (seconds >= 60L) return null
        val millis = when (fracRaw.length) {
            0 -> 0L
            1 -> (fracRaw.toLongOrNull() ?: return null) * 100L
            2 -> (fracRaw.toLongOrNull() ?: return null) * 10L
            else -> fracRaw.take(3).toLongOrNull() ?: return null
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }
}
