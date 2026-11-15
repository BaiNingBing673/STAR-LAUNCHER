package com.star.launcher.lyrics

import com.star.launcher.lyrics.engine.LrcParser
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.LyricsSynchronizer
import com.star.launcher.lyrics.engine.LyricsTrackKey
import com.star.launcher.lyrics.engine.SyncConfidence
import com.star.launcher.lyrics.media.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserAndSyncTest {

    @Test
    fun `parses standard and multi-timestamp LRC sorted by timestamp`() {
        val raw = """
            [ti:Starlight]
            [ar:STAR Band]
            [00:12.50][00:40.00]Chorus repeats here
            [00:05.00]Opening verse
        """.trimIndent()

        val key = LyricsTrackKey.build("Starlight", "STAR Band", 90_000L)
        val doc = LrcParser.parse(raw, key, "Fallback", "Artist", durationMs = 90_000L, sourceName = "LRCLIB")

        assertEquals(LyricsFormat.LINE_SYNCED, doc.format)
        assertTrue(doc.isSynchronized)
        assertEquals("Starlight", doc.title)
        assertEquals("STAR Band", doc.artist)
        assertEquals(3, doc.lines.size)
        assertEquals(5_000L, doc.lines[0].startTimeMs)
        assertEquals("Opening verse", doc.lines[0].text)
        assertEquals(12_500L, doc.lines[1].startTimeMs)
        assertEquals(40_000L, doc.lines[2].startTimeMs)
    }

    @Test
    fun `parses enhanced word-level LRC and tracks active word progress`() {
        val raw = """
            [00:10.00]<00:10.00>Hello <00:11.00>world <00:12.00>tonight
            [00:14.00]<00:14.00>Next <00:15.00>line
        """.trimIndent()

        val doc = LrcParser.parse(raw, "k", "Title", "Artist", durationMs = 30_000L)
        assertEquals(LyricsFormat.WORD_SYNCED, doc.format)
        assertEquals(2, doc.lines.size)
        assertEquals(3, doc.lines[0].words.size)

        val frame = LyricsSynchronizer.synchronize(
            document = doc,
            effectivePositionMs = 11_500L,
            durationMs = 30_000L,
            hasRealPosition = true
        )
        assertEquals(SyncConfidence.EXACT_POSITION, frame.syncConfidence)
        assertEquals("Hello world tonight", frame.currentLine)
        assertEquals("Next line", frame.nextLine)
        assertEquals(1, frame.activeWordIndex)
        assertEquals(0.5f, frame.currentWordProgress, 0.02f)

        val serialized = LrcParser.toLrc(doc)
        assertTrue(serialized.contains("<00:10.00>Hello"))
    }

    @Test
    fun `plain text lyrics never fabricate synchronized timestamps`() {
        val plain = "Line one without time\nLine two without time\nLine three"
        val doc = LrcParser.parse(plain, "k", "Song", "Artist")
        assertEquals(LyricsFormat.PLAIN, doc.format)
        assertFalse(doc.isSynchronized)
        assertTrue(doc.lines.isEmpty())

        val frame = LyricsSynchronizer.synchronize(
            document = doc,
            effectivePositionMs = 15_000L,
            durationMs = 120_000L,
            hasRealPosition = true
        )
        assertEquals(SyncConfidence.PLAIN_UNSYNCED, frame.syncConfidence)
        assertTrue(frame.statusLabel.contains("unsynced", ignoreCase = true))
    }

    @Test
    fun `synchronizer supports seeking pause resume offsets and approximate mode`() {
        val raw = """
            [00:10.00]First line
            [00:20.00]Second line
            [00:30.00]Third line
        """.trimIndent()
        val doc = LrcParser.parse(raw, "k", "Song", "Artist", durationMs = 60_000L)

        // Seek forward to 25s
        val seekForward = LyricsSynchronizer.synchronize(doc, 25_000L, 60_000L, hasRealPosition = true)
        assertEquals(1, seekForward.activeIndex)
        assertEquals("First line", seekForward.previousLine)
        assertEquals("Second line", seekForward.currentLine)
        assertEquals("Third line", seekForward.nextLine)

        // Seek backward before first line
        val seekBack = LyricsSynchronizer.synchronize(doc, 4_000L, 60_000L, hasRealPosition = true)
        assertEquals(-1, seekBack.activeIndex)
        assertNull(seekBack.previousLine)
        assertEquals("First line", seekBack.nextLine)

        // Offset shifts position (+11s from 10s -> 21s -> Second line)
        val withOffset = LyricsSynchronizer.synchronize(
            document = doc,
            effectivePositionMs = 10_000L,
            durationMs = 60_000L,
            hasRealPosition = true,
            trackOffsetMs = 6_000L,
            globalOffsetMs = 5_000L
        )
        assertEquals("Second line", withOffset.currentLine)

        // Approximate sync warning when source lacks real playback position
        val approx = LyricsSynchronizer.synchronize(doc, 12_000L, 60_000L, hasRealPosition = false)
        assertEquals(SyncConfidence.APPROXIMATE_CLOCK, approx.syncConfidence)
        assertTrue(approx.statusLabel.contains("Approx", ignoreCase = true))

        // PlaybackSnapshot freezes when paused and advances when playing
        val pausedSnap = PlaybackSnapshot(
            isPlaying = false,
            positionMs = 12_000L,
            positionUpdateElapsedMs = 1_000L,
            playbackSpeed = 1.0f,
            durationMs = 60_000L,
            hasRealPosition = true
        )
        assertEquals(12_000L, pausedSnap.currentPositionMs(nowElapsedMs = 5_000L))

        val playingSnap = pausedSnap.copy(isPlaying = true)
        assertEquals(16_000L, playingSnap.currentPositionMs(nowElapsedMs = 5_000L))
    }
}
