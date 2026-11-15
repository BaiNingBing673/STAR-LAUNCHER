package com.star.launcher.lyrics

import com.star.launcher.hud.StarHudConfig
import com.star.launcher.lyrics.config.IslandDisplayMode
import com.star.launcher.lyrics.config.LiveLyricsConfig
import com.star.launcher.lyrics.config.LiveLyricsDisplayMode
import com.star.launcher.lyrics.engine.LyricWordSpan
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.SyncConfidence
import com.star.launcher.lyrics.overlay.HudExclusionRect
import com.star.launcher.lyrics.overlay.HudPlacementRequest
import com.star.launcher.lyrics.overlay.HudZoneCategory
import com.star.launcher.lyrics.overlay.KineticLyricsView
import com.star.launcher.lyrics.overlay.LiveLyricsHudLayoutSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLyricsHudLayoutSolverTest {

    @Test
    fun `auto positioning avoids drawer pull, top hud buttons, and hotbar on 1080p landscape`() {
        val starCfg = StarHudConfig.defaults()
        val cfg = LiveLyricsConfig(
            featureEnabled = true,
            autoHudSafePositioning = true,
            displayMode = LiveLyricsDisplayMode.COMPACT_MUSIC
        )
        val req = HudPlacementRequest(
            containerWidthPx = 1920f,
            containerHeightPx = 1080f,
            density = 3.0f,
            safeLeftPx = 48f,
            safeTopPx = 12f,
            safeRightPx = 24f,
            safeBottomPx = 0f,
            desiredWidthPx = 286f * 3f,
            desiredHeightPx = 42f * 3f,
            config = cfg,
            starHudConfig = starCfg
        )
        val result = LiveLyricsHudLayoutSolver.solve(req)
        assertFalse("Overlay should fit cleanly on 1080p landscape", result.shouldTemporarilyHide)
        assertTrue("Colliding zones must be empty, was ${result.collidingZones.map { it.label }}", result.collidingZones.isEmpty())
        for (zone in result.allExclusionRects) {
            assertFalse(
                "Overlay (${result.leftPx},${result.topPx},${result.leftPx + result.widthPx},${result.topPx + result.heightPx}) overlapped ${zone.label}",
                zone.overlaps(result.leftPx, result.topPx, result.leftPx + result.widthPx, result.topPx + result.heightPx)
            )
        }
    }

    @Test
    fun `auto positioning handles portrait, wide aspect ratio, and 720p screens cleanly`() {
        val sizes = listOf(
            2400f to 1080f, // 20:9 landscape phone
            1280f to 720f,  // small 720p landscape
            1080f to 2280f  // portrait orientation
        )
        val cfg = LiveLyricsConfig(
            featureEnabled = true,
            autoHudSafePositioning = true,
            displayMode = LiveLyricsDisplayMode.LYRICS_ONLY
        )
        for ((w, h) in sizes) {
            val result = LiveLyricsHudLayoutSolver.solve(
                HudPlacementRequest(
                    containerWidthPx = w,
                    containerHeightPx = h,
                    density = 2.5f,
                    safeLeftPx = 30f,
                    safeTopPx = 18f,
                    safeRightPx = 30f,
                    safeBottomPx = 12f,
                    desiredWidthPx = 248f * 2.5f,
                    desiredHeightPx = 42f * 2.5f,
                    config = cfg
                )
            )
            assertFalse("Screen ${w}x${h} should find safe spot without hiding", result.shouldTemporarilyHide)
            assertTrue("Screen ${w}x${h} should stay inside safe bounds", result.leftPx >= 30f && result.leftPx + result.widthPx <= w - 30f)
            assertTrue("Screen ${w}x${h} should stay below top inset", result.topPx >= 18f)
        }
    }

    @Test
    fun `manual positioning over hotbar or top inventory triggers exact collision warnings`() {
        val starCfg = StarHudConfig.defaults()
        // Over bottom-center hotbar
        val overHotbarCfg = LiveLyricsConfig(
            featureEnabled = true,
            autoHudSafePositioning = false,
            manualPosXFraction = 0.50f,
            manualPosYFraction = 0.94f
        )
        val hotbarResult = LiveLyricsHudLayoutSolver.solve(
            HudPlacementRequest(
                containerWidthPx = 1920f,
                containerHeightPx = 1080f,
                density = 3.0f,
                safeLeftPx = 0f,
                safeTopPx = 0f,
                safeRightPx = 0f,
                safeBottomPx = 0f,
                desiredWidthPx = 248f * 3f,
                desiredHeightPx = 42f * 3f,
                config = overHotbarCfg,
                starHudConfig = starCfg
            )
        )
        assertTrue(
            "Expected Hotbar collision warning, got ${hotbarResult.collidingZones.map { it.label }}",
            hotbarResult.collidingZones.any { it.category == HudZoneCategory.HOTBAR_AND_VITALS }
        )

        // Over top-right INVENTORY/CHAT buttons
        val overTopButtonsCfg = LiveLyricsConfig(
            featureEnabled = true,
            autoHudSafePositioning = false,
            manualPosXFraction = 0.78f,
            manualPosYFraction = 0.04f
        )
        val topBtnResult = LiveLyricsHudLayoutSolver.solve(
            HudPlacementRequest(
                containerWidthPx = 1920f,
                containerHeightPx = 1080f,
                density = 3.0f,
                safeLeftPx = 0f,
                safeTopPx = 0f,
                safeRightPx = 0f,
                safeBottomPx = 0f,
                desiredWidthPx = 286f * 3f,
                desiredHeightPx = 42f * 3f,
                config = overTopButtonsCfg,
                starHudConfig = starCfg
            )
        )
        assertTrue(
            "Expected TOP_HUD_ACTION collision warning, got ${topBtnResult.collidingZones.map { it.label }}",
            topBtnResult.collidingZones.any { it.category == HudZoneCategory.TOP_HUD_ACTION }
        )
    }

    @Test
    fun `temporarily hides overlay instead of obstructing controls when entire screen is blocked`() {
        val fullWall = listOf(
            HudExclusionRect("wall", "Blocked HUD", HudZoneCategory.LEGACY_CONTROL, 0f, 0f, 800f, 480f)
        )
        val result = LiveLyricsHudLayoutSolver.solve(
            HudPlacementRequest(
                containerWidthPx = 800f,
                containerHeightPx = 480f,
                density = 2.0f,
                safeLeftPx = 0f,
                safeTopPx = 0f,
                safeRightPx = 0f,
                safeBottomPx = 0f,
                desiredWidthPx = 480f,
                desiredHeightPx = 84f,
                config = LiveLyricsConfig(featureEnabled = true, autoHudSafePositioning = true),
                liveControlRects = fullWall
            )
        )
        assertTrue("Overlay must hide temporarily if zero non-overlapping zones remain", result.shouldTemporarilyHide)
    }

    @Test
    fun `moving musical note computation honors word sync, line sync, and plain unsynced honestly`() {
        val words = listOf(
            LyricWordSpan(0L, 400L, "Star"),
            LyricWordSpan(400L, 900L, "light")
        )
        val wordFrac = KineticLyricsView.computeTargetNoteFraction(
            format = LyricsFormat.WORD_SYNCED,
            confidence = SyncConfidence.EXACT_POSITION,
            words = words,
            activeWordIndex = 1,
            wordProgress = 0.5f,
            lineProgress = 0.1f
        )
        // "Star" (4) + 0.5 * "light" (2.5) = 6.5 / 9 = ~0.722
        assertTrue("Word-synced fraction should match word cursor (~0.72), was $wordFrac", wordFrac > 0.65f && wordFrac < 0.80f)

        val lineFrac = KineticLyricsView.computeTargetNoteFraction(
            format = LyricsFormat.LINE_SYNCED,
            confidence = SyncConfidence.EXACT_POSITION,
            words = emptyList(),
            activeWordIndex = -1,
            wordProgress = 0f,
            lineProgress = 0.42f
        )
        assertEquals(0.42f, lineFrac, 0.001f)

        val plainFrac = KineticLyricsView.computeTargetNoteFraction(
            format = LyricsFormat.PLAIN,
            confidence = SyncConfidence.PLAIN_UNSYNCED,
            words = words,
            activeWordIndex = 1,
            wordProgress = 0.8f,
            lineProgress = 0.9f
        )
        assertEquals("Unsynced plain lyrics must never fabricate sync timeline", 0f, plainFrac, 0.001f)
    }

    @Test
    fun `clampManualPosition keeps overlay 100 percent visible at all four corners and respects cutouts`() {
        val corners = listOf(
            -500f to -500f,   // Far beyond top-left
            5000f to -500f,   // Far beyond top-right
            -500f to 5000f,   // Far beyond bottom-left
            5000f to 5000f    // Far beyond bottom-right
        )
        val screenW = 1920f
        val screenH = 1080f
        val safeLeft = 48f
        val safeTop = 24f
        val safeRight = 36f
        val safeBottom = 16f
        val pillW = 286f * 3f
        val pillH = 42f * 3f

        for ((rawX, rawY) in corners) {
            val clamped = LiveLyricsHudLayoutSolver.clampManualPosition(
                containerWidthPx = screenW,
                containerHeightPx = screenH,
                density = 3.0f,
                safeLeftPx = safeLeft,
                safeTopPx = safeTop,
                safeRightPx = safeRight,
                safeBottomPx = safeBottom,
                pillWidthPx = pillW,
                pillHeightPx = pillH,
                desiredLeftPx = rawX,
                desiredTopPx = rawY
            )
            assertTrue("Left (${clamped.clampedLeftPx}) must respect safeLeft ($safeLeft)", clamped.clampedLeftPx >= safeLeft)
            assertTrue("Top (${clamped.clampedTopPx}) must respect safeTop ($safeTop)", clamped.clampedTopPx >= safeTop)
            assertTrue(
                "Right (${clamped.clampedLeftPx + pillW}) must stay inside safeRight (${screenW - safeRight})",
                clamped.clampedLeftPx + pillW <= screenW - safeRight + 0.5f
            )
            assertTrue(
                "Bottom (${clamped.clampedTopPx + pillH}) must stay inside safeBottom (${screenH - safeBottom})",
                clamped.clampedTopPx + pillH <= screenH - safeBottom + 0.5f
            )
            assertTrue("normalizedX must be within [0,1]", clamped.normalizedX in 0f..1f)
            assertTrue("normalizedY must be within [0,1]", clamped.normalizedY in 0f..1f)
        }
    }
}
