package com.star.launcher.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkHarnessTest {

    @Test
    fun `parseKgslBusyPercent handles percentages whitespace and invalid input`() {
        assertEquals(42, BenchmarkHarness.parseKgslBusyPercent(" 42 % \n"))
        assertEquals(0, BenchmarkHarness.parseKgslBusyPercent("-5"))
        assertEquals(100, BenchmarkHarness.parseKgslBusyPercent("130%"))
        assertNull(BenchmarkHarness.parseKgslBusyPercent("abc"))
        assertNull(BenchmarkHarness.parseKgslBusyPercent(null))
    }

    @Test
    fun `parseThermalCelsius normalizes millidegrees and filters invalid values`() {
        assertEquals(45.5f, BenchmarkHarness.parseThermalCelsius("45500") ?: -1f, 0.01f)
        assertEquals(39.0f, BenchmarkHarness.parseThermalCelsius("39.0") ?: -1f, 0.01f)
        assertNull(BenchmarkHarness.parseThermalCelsius("0"))
        assertNull(BenchmarkHarness.parseThermalCelsius("bad"))
    }

    @Test
    fun `computeFrameStats calculates average FPS and 1 percent low accurately`() {
        // 99 frames at 10.0 ms (100 FPS) + 1 spike frame at 50.0 ms (20 FPS)
        val frames = List(99) { 10.0 } + listOf(50.0)
        val stats = BenchmarkHarness.computeFrameStats(frames)
        assertNotNull(stats)
        stats!!
        assertEquals(100, stats.sampleCount)
        assertEquals(10.4, stats.avgFrameTimeMs, 0.05)
        assertEquals(96.15, stats.avgFps, 0.5)
        assertEquals(50.0, stats.onePercentLowFrameTimeMs, 0.01)
        assertEquals(20.0, stats.onePercentLowFps, 0.1)
    }
}
