package com.star.launcher.lyrics.engine

/**
 * Synchronizes a [LyricsDocument] against actual playback position using binary search.
 * Never invents synchronized timestamps for plain lyrics.
 */
object LyricsSynchronizer {

    fun synchronize(
        document: LyricsDocument?,
        effectivePositionMs: Long,
        durationMs: Long,
        hasRealPosition: Boolean,
        trackOffsetMs: Long = 0L,
        globalOffsetMs: Long = 0L
    ): SyncedLyricsFrame {
        if (document == null || document.format == LyricsFormat.NONE) {
            return SyncedLyricsFrame(
                format = LyricsFormat.NONE,
                syncConfidence = SyncConfidence.UNAVAILABLE,
                statusLabel = "No lyrics",
                effectivePositionMs = effectivePositionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L)
            )
        }

        if (document.format == LyricsFormat.PLAIN || !document.isSynchronized) {
            val previewLines = document.plainText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .toList()
            return SyncedLyricsFrame(
                trackKey = document.trackKey,
                format = LyricsFormat.PLAIN,
                syncConfidence = SyncConfidence.PLAIN_UNSYNCED,
                statusLabel = "Plain text (unsynced)",
                activeIndex = -1,
                previousLine = previewLines.getOrNull(0),
                currentLine = previewLines.getOrNull(1) ?: previewLines.getOrNull(0),
                nextLine = previewLines.getOrNull(2),
                effectivePositionMs = effectivePositionMs.coerceAtLeast(0L),
                durationMs = maxOf(durationMs, document.durationMs),
                attribution = document.attribution
            )
        }

        val lines = document.lines
        val totalOffset = trackOffsetMs + globalOffsetMs
        val shiftedPos = (effectivePositionMs + totalOffset).coerceAtLeast(0L)
        val totalDuration = maxOf(durationMs, document.durationMs)
        val confidence = if (hasRealPosition) SyncConfidence.EXACT_POSITION else SyncConfidence.APPROXIMATE_CLOCK
        val statusLabel = buildStatusLabel(document, confidence)

        val activeIndex = findActiveLineIndex(lines, shiftedPos)
        if (activeIndex < 0) {
            val firstLine = lines.firstOrNull()?.text
            return SyncedLyricsFrame(
                trackKey = document.trackKey,
                format = document.format,
                syncConfidence = confidence,
                statusLabel = statusLabel,
                activeIndex = -1,
                previousLine = null,
                currentLine = "•••",
                nextLine = firstLine,
                lineProgress = 0f,
                effectivePositionMs = effectivePositionMs.coerceAtLeast(0L),
                durationMs = totalDuration,
                attribution = document.attribution
            )
        }

        val current = lines[activeIndex]
        val prevText = if (activeIndex > 0) lines[activeIndex - 1].text else null
        val nextText = if (activeIndex + 1 < lines.size) lines[activeIndex + 1].text else null

        val lineStart = current.startTimeMs
        val lineEnd = current.endTimeMs ?: if (totalDuration > lineStart) totalDuration else (lineStart + 4_000L)
        val span = (lineEnd - lineStart).coerceAtLeast(1L)
        val lineProgress = ((shiftedPos - lineStart).toFloat() / span.toFloat()).coerceIn(0f, 1f)

        var activeWordIdx = -1
        var wordProgress = 0f
        if (current.words.isNotEmpty()) {
            activeWordIdx = findActiveWordIndex(current.words, shiftedPos)
            if (activeWordIdx >= 0) {
                val word = current.words[activeWordIdx]
                val wEnd = word.endTimeMs ?: lineEnd
                val wSpan = (wEnd - word.startTimeMs).coerceAtLeast(1L)
                wordProgress = ((shiftedPos - word.startTimeMs).toFloat() / wSpan.toFloat()).coerceIn(0f, 1f)
            }
        }

        return SyncedLyricsFrame(
            trackKey = document.trackKey,
            format = document.format,
            syncConfidence = confidence,
            statusLabel = statusLabel,
            activeIndex = activeIndex,
            previousLine = prevText,
            currentLine = current.text,
            nextLine = nextText,
            activeWords = current.words,
            activeWordIndex = activeWordIdx,
            currentWordProgress = wordProgress,
            lineProgress = lineProgress,
            effectivePositionMs = effectivePositionMs.coerceAtLeast(0L),
            durationMs = totalDuration,
            attribution = document.attribution
        )
    }

    /**
     * Returns the index of the last line whose `startTimeMs <= positionMs`, or `-1` before line 0.
     */
    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines[0].startTimeMs) return -1
        var low = 0
        var high = lines.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = lines[mid].startTimeMs
            if (start <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun findActiveWordIndex(words: List<LyricWordSpan>, positionMs: Long): Int {
        if (words.isEmpty() || positionMs < words[0].startTimeMs) return -1
        var low = 0
        var high = words.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (words[mid].startTimeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun buildStatusLabel(document: LyricsDocument, confidence: SyncConfidence): String {
        val origin = when {
            document.isCustom -> "Custom LRC"
            document.format == LyricsFormat.WORD_SYNCED -> "Word Sync"
            document.sourceName.isNotBlank() -> document.sourceName
            else -> "Synced"
        }
        return if (confidence == SyncConfidence.APPROXIMATE_CLOCK) {
            "$origin · Approx. Sync"
        } else {
            origin
        }
    }
}
