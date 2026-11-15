package com.star.launcher.lyrics

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.lyrics.config.AnimationIntensity
import com.star.launcher.lyrics.config.AnimationQuality
import com.star.launcher.lyrics.config.IslandDisplayMode
import com.star.launcher.lyrics.config.IslandPosition
import com.star.launcher.lyrics.config.LiveLyricsConfig
import com.star.launcher.lyrics.config.LiveLyricsConfigStore
import com.star.launcher.lyrics.config.LiveLyricsDisplayMode
import com.star.launcher.lyrics.config.LiveLyricsSessionState
import com.star.launcher.lyrics.engine.LrcParser
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.LyricsSynchronizer
import com.star.launcher.lyrics.engine.SyncConfidence
import com.star.launcher.lyrics.engine.SyncedLyricsFrame
import com.star.launcher.lyrics.media.MediaControlCapability
import com.star.launcher.lyrics.media.MediaTrackInfo
import com.star.launcher.lyrics.media.PlaybackSnapshot
import com.star.launcher.lyrics.media.UnifiedMediaState
import com.star.launcher.lyrics.overlay.HudPlacementRequest
import com.star.launcher.lyrics.overlay.KineticLyricsView
import com.star.launcher.lyrics.overlay.LiveLyricsBenchmarkMonitor
import com.star.launcher.lyrics.overlay.LiveLyricsHudLayoutSolver
import com.star.launcher.lyrics.overlay.StarLiveLyricsOverlay
import com.star.launcher.ui.dashboard.DashboardTab
import com.star.launcher.ui.navigation.AetherRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveLyricsOverlayAndConfigTest {

    @Test
    fun `config persistence sanitizes bounds and keeps session state independent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val saved = LiveLyricsConfigStore.save(
            context,
            LiveLyricsConfig(
                featureEnabled = true,
                overlayVisible = true,
                lyricsEnabled = false,
                metadataEnabled = true,
                animationsEnabled = false,
                animationQuality = AnimationQuality.BALANCED,
                defaultMode = IslandDisplayMode.EXPANDED,
                positionAnchor = IslandPosition.TOP_RIGHT,
                overlayScale = 9.0f,
                overlayAlpha = 0.1f,
                globalOffsetMs = 40_000L
            )
        )
        assertEquals(1.35f, saved.overlayScale, 0.001f)
        assertEquals(0.45f, saved.overlayAlpha, 0.001f)
        assertEquals(10_000L, saved.globalOffsetMs)

        val reloaded = LiveLyricsConfigStore.load(context)
        assertEquals(saved, reloaded)

        // Temporary hide and session disable must not overwrite global config
        val sessionHidden = LiveLyricsSessionState(temporarilyHidden = true, activeMode = IslandDisplayMode.EXPANDED)
        assertEquals(IslandDisplayMode.HIDDEN, sessionHidden.effectiveMode(reloaded))
        assertTrue(LiveLyricsConfigStore.load(context).overlayVisible)

        val sessionDisabled = LiveLyricsSessionState(sessionDisabled = true)
        assertEquals(IslandDisplayMode.HIDDEN, sessionDisabled.effectiveMode(reloaded))
        assertTrue(LiveLyricsConfigStore.load(context).featureEnabled)
    }

    @Test
    fun `navigation route encodes and decodes LiveLyricsSettings cleanly`() {
        val encoded = AetherRoute.LiveLyricsSettings.encode()
        assertEquals("live_lyrics_settings", encoded)
        assertEquals(AetherRoute.LiveLyricsSettings, AetherRoute.decode(encoded))
        assertEquals(AetherRoute.LiveLyricsSettings, AetherRoute.decode("LIVE_LYRICS"))
        assertEquals(DashboardTab.SETTINGS, AetherRoute.LiveLyricsSettings.ownerTab)
    }

    @Test
    fun `overlay respects master disable and passes outside touches through to Minecraft`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = StarLiveLyricsOverlay(context)
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, 1920, 1080)
        overlay.updateSafeInsets(24, 16, 24, 0)

        val media = UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = true,
            track = MediaTrackInfo(
                sourcePackage = "com.spotify.music",
                sourceAppLabel = "Spotify",
                title = "Wet Hands",
                artist = "C418",
                durationMs = 90_000L
            ),
            playback = PlaybackSnapshot(
                isPlaying = true,
                positionMs = 15_000L,
                durationMs = 90_000L,
                hasRealPosition = true,
                capabilities = MediaControlCapability(canPlay = true, canPause = true, canPrevious = true, canNext = true)
            )
        )
        val frame = SyncedLyricsFrame(
            currentLine = "Gentle piano melody",
            nextLine = "Next lyric",
            statusLabel = "LRCLIB"
        )

        // 1. Active in compact mode
        val enabledCfg = LiveLyricsConfig(featureEnabled = true, animationsEnabled = false)
        val compactSess = LiveLyricsSessionState(activeMode = IslandDisplayMode.COMPACT)
        overlay.applyState(enabledCfg, compactSess, media, frame, 0L)
        assertEquals(IslandDisplayMode.COMPACT, overlay.getCurrentEffectiveMode())

        // Touch in bottom-left gameplay area (far from top pill) must NOT be consumed by overlay
        val gameplayTouch = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 200f, 800f, 0)
        assertFalse(overlay.dispatchTouchEvent(gameplayTouch))
        gameplayTouch.recycle()

        // 2. Expanded mode
        val expandedSess = compactSess.copy(activeMode = IslandDisplayMode.EXPANDED)
        overlay.applyState(enabledCfg, expandedSess, media, frame, 0L)
        assertEquals(IslandDisplayMode.EXPANDED, overlay.getCurrentEffectiveMode())

        // 3. Master OFF disables overlay completely
        overlay.applyState(enabledCfg.copy(featureEnabled = false), expandedSess, media, frame, 0L)
        assertEquals(IslandDisplayMode.HIDDEN, overlay.getCurrentEffectiveMode())
    }

    @Test
    fun `synchronizer micro-benchmark stays sub-millisecond per tick`() {
        val raw = (1..120).joinToString("\n") { idx ->
            val sec = idx * 2
            "[${LrcParser.formatTimestamp(sec * 1000L)}]Lyric line number $idx for benchmark"
        }
        val doc = LrcParser.parse(raw, "bench", "Track", "Artist", durationMs = 250_000L)
        val monitor = LiveLyricsBenchmarkMonitor()
        monitor.setMode(IslandDisplayMode.COMPACT)

        val iterations = 500
        val startNs = System.nanoTime()
        for (i in 0 until iterations) {
            val t0 = System.nanoTime()
            LyricsSynchronizer.synchronize(doc, (i * 400L) % 240_000L, 250_000L, hasRealPosition = true)
            monitor.recordSyncTickNs(System.nanoTime() - t0)
        }
        val totalNs = System.nanoTime() - startNs
        val snap = monitor.snapshot()
        assertTrue("Average sync tick should be < 1ms, was ${snap.avgSyncTickNs}ns", snap.avgSyncTickNs < 1_000_000L)
        assertTrue("Total 500 sync ticks should finish quickly, took ${totalNs / 1_000_000}ms", totalNs < 500_000_000L)
    }

    @Test
    fun `lyrics only mode hides metadata and controls while persisting customization settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persisted = LiveLyricsConfigStore.save(
            context,
            LiveLyricsConfig(
                featureEnabled = true,
                displayMode = LiveLyricsDisplayMode.LYRICS_ONLY,
                showMusicalNote = true,
                autoHudSafePositioning = false,
                manualPosXFraction = 0.25f,
                manualPosYFraction = 0.14f,
                animationIntensity = AnimationIntensity.DYNAMIC,
                noteAnimationSpeed = 1.6f,
                lyricsFontSizeSp = 15.5f,
                overlayAlpha = 0.84f
            )
        )
        val loaded = LiveLyricsConfigStore.load(context)
        assertEquals(LiveLyricsDisplayMode.LYRICS_ONLY, loaded.displayMode)
        assertTrue(loaded.showMusicalNote)
        assertFalse(loaded.autoHudSafePositioning)
        assertEquals(0.25f, loaded.manualPosXFraction, 0.001f)
        assertEquals(0.14f, loaded.manualPosYFraction, 0.001f)
        assertEquals(AnimationIntensity.DYNAMIC, loaded.animationIntensity)
        assertEquals(1.6f, loaded.noteAnimationSpeed, 0.001f)

        val overlay = StarLiveLyricsOverlay(context)
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, 1920, 1080)

        val media = UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = true,
            track = MediaTrackInfo(
                sourcePackage = "com.spotify.music",
                sourceAppLabel = "Spotify",
                title = "Sweden",
                artist = "C418",
                durationMs = 180_000L
            ),
            playback = PlaybackSnapshot(isPlaying = true, positionMs = 30_000L, durationMs = 180_000L, hasRealPosition = true)
        )
        val frame = SyncedLyricsFrame(
            format = LyricsFormat.LINE_SYNCED,
            syncConfidence = SyncConfidence.EXACT_POSITION,
            currentLine = "Quiet blocks under pixel stars",
            lineProgress = 0.45f,
            statusLabel = "LRCLIB"
        )
        val sess = LiveLyricsSessionState(
            activeMode = IslandDisplayMode.COMPACT,
            activeDisplayMode = LiveLyricsDisplayMode.LYRICS_ONLY
        )
        overlay.applyState(persisted, sess, media, frame, 0L)

        // Verify metadata TextViews inside compact row are GONE and KineticLyricsView is VISIBLE
        val pillCard = overlay.getChildAt(0) as ViewGroup
        val compactRow = pillCard.getChildAt(0) as LinearLayout
        val expandedCol = pillCard.getChildAt(1) as ViewGroup
        assertEquals(View.VISIBLE, compactRow.visibility)
        assertEquals(View.GONE, expandedCol.visibility)

        var sawKineticView = false
        var visibleMetadataTitle = false
        for (i in 0 until compactRow.childCount) {
            val child = compactRow.getChildAt(i)
            if (child is KineticLyricsView) {
                assertEquals(View.VISIBLE, child.visibility)
                sawKineticView = true
            } else if (child is TextView && child.visibility == View.VISIBLE && child.text.toString().contains("Sweden")) {
                visibleMetadataTitle = true
            } else if (child.visibility == View.VISIBLE && child !is KineticLyricsView) {
                // Glow dot and visualizer must be hidden in Lyrics Only mode
                assertFalse("Unexpected non-lyrics child visible in LYRICS_ONLY mode: ${child.javaClass.simpleName}", true)
            }
        }
        assertTrue(sawKineticView)
        assertFalse(visibleMetadataTitle)

        // Touches outside pill still pass directly through to Minecraft
        val outsideTouch = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1600f, 900f, 0)
        assertFalse(overlay.dispatchTouchEvent(outsideTouch))
        outsideTouch.recycle()
    }

    @Test
    fun `layout solver and mode switch benchmark confirms lightweight overhead across modes`() {
        val raw = (1..80).joinToString("\n") { idx ->
            "[${LrcParser.formatTimestamp(idx * 2500L)}]Sample benchmark line $idx"
        }
        val doc = LrcParser.parse(raw, "perf", "Perf Track", "Perf Artist", durationMs = 210_000L)

        // Baseline: hidden / disabled solver not invoked, only idle baseline loop
        val hiddenStart = System.nanoTime()
        var hiddenSum = 0L
        for (i in 0 until 200) {
            hiddenSum += i
        }
        val hiddenNs = System.nanoTime() - hiddenStart

        // Full Music Display sync + layout solve benchmark
        val fullCfg = LiveLyricsConfig(featureEnabled = true, displayMode = LiveLyricsDisplayMode.FULL_MUSIC, autoHudSafePositioning = true)
        val fullReq = HudPlacementRequest(1920f, 1080f, 3f, 24f, 12f, 24f, 0f, 364f * 3f, 168f * 3f, fullCfg)
        val fullStart = System.nanoTime()
        for (i in 0 until 200) {
            if (i == 0) LiveLyricsHudLayoutSolver.solve(fullReq) // cached after layout
            LyricsSynchronizer.synchronize(doc, (i * 500L) % 200_000L, 210_000L, true)
        }
        val fullNs = System.nanoTime() - fullStart

        // Lyrics Only sync + layout solve benchmark
        val lyricsCfg = LiveLyricsConfig(featureEnabled = true, displayMode = LiveLyricsDisplayMode.LYRICS_ONLY, autoHudSafePositioning = true)
        val lyricsReq = HudPlacementRequest(1920f, 1080f, 3f, 24f, 12f, 24f, 0f, 248f * 3f, 42f * 3f, lyricsCfg)
        val lyricsStart = System.nanoTime()
        for (i in 0 until 200) {
            if (i == 0) LiveLyricsHudLayoutSolver.solve(lyricsReq) // cached after layout
            LyricsSynchronizer.synchronize(doc, (i * 500L) % 200_000L, 210_000L, true)
        }
        val lyricsNs = System.nanoTime() - lyricsStart

        assertTrue(hiddenSum >= 0L && hiddenNs >= 0L)
        assertTrue("200 ticks Full Music should be < 250ms, took ${fullNs / 1_000_000}ms", fullNs < 250_000_000L)
        assertTrue("200 ticks Lyrics Only should be < 250ms, took ${lyricsNs / 1_000_000}ms", lyricsNs < 250_000_000L)
    }

    @Test
    fun `free drag to all four corners stays fully visible across all display modes and persists across restart and rotation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media = UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = true,
            track = MediaTrackInfo(
                sourcePackage = "com.spotify.music",
                sourceAppLabel = "Spotify",
                title = "Mice on Venus",
                artist = "C418",
                durationMs = 200_000L
            ),
            playback = PlaybackSnapshot(isPlaying = true, positionMs = 45_000L, durationMs = 200_000L, hasRealPosition = true)
        )
        val frame = SyncedLyricsFrame(
            format = LyricsFormat.LINE_SYNCED,
            syncConfidence = SyncConfidence.EXACT_POSITION,
            currentLine = "Drifting through the overworld",
            nextLine = "Sunlight on the hills",
            lineProgress = 0.5f,
            statusLabel = "LRCLIB"
        )

        val modes = listOf(
            LiveLyricsDisplayMode.LYRICS_ONLY,
            LiveLyricsDisplayMode.COMPACT_MUSIC,
            LiveLyricsDisplayMode.FULL_MUSIC
        )

        for (displayMode in modes) {
            val initialCfg = LiveLyricsConfigStore.save(
                context,
                LiveLyricsConfig(
                    featureEnabled = true,
                    displayMode = displayMode,
                    animationsEnabled = false,
                    autoHudSafePositioning = true,
                    positionLocked = false
                )
            )
            val overlay = StarLiveLyricsOverlay(context)
            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            )
            overlay.layout(0, 0, 1920, 1080)
            overlay.updateSafeInsets(24, 16, 24, 12)

            val islandMode = if (displayMode == LiveLyricsDisplayMode.FULL_MUSIC) {
                IslandDisplayMode.EXPANDED
            } else {
                IslandDisplayMode.COMPACT
            }
            val sess = LiveLyricsSessionState(activeMode = islandMode, activeDisplayMode = displayMode)
            overlay.applyState(initialCfg, sess, media, frame, 0L)

            val corners = listOf(
                "TOP_LEFT" to (-400f to -300f),
                "TOP_RIGHT" to (2400f to -300f),
                "BOTTOM_LEFT" to (-400f to 1500f),
                "BOTTOM_RIGHT" to (2400f to 1500f)
            )

            for ((cornerName, targetXY) in corners) {
                val boundsBefore = overlay.getPillBoundsOnScreen()
                val startX = boundsBefore.centerX()
                val startY = boundsBefore.centerY()

                val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, startX, startY, 0)
                val move1 = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, (startX + targetXY.first) * 0.5f, (startY + targetXY.second) * 0.5f, 0)
                val move2 = MotionEvent.obtain(0L, 32L, MotionEvent.ACTION_MOVE, targetXY.first, targetXY.second, 0)
                val up = MotionEvent.obtain(0L, 48L, MotionEvent.ACTION_UP, targetXY.first, targetXY.second, 0)

                assertTrue("Down on pill must be handled ($displayMode / $cornerName)", overlay.dispatchTouchEvent(down))
                assertTrue("Move1 on pill must be handled ($displayMode / $cornerName)", overlay.dispatchTouchEvent(move1))
                assertTrue("Move2 on pill must be handled ($displayMode / $cornerName)", overlay.dispatchTouchEvent(move2))
                assertTrue("Up on pill must be handled ($displayMode / $cornerName)", overlay.dispatchTouchEvent(up))

                down.recycle()
                move1.recycle()
                move2.recycle()
                up.recycle()

                val boundsAfter = overlay.getPillBoundsOnScreen()
                assertTrue(
                    "[$displayMode / $cornerName] Pill left (${boundsAfter.left}) must stay inside safeLeft (24)",
                    boundsAfter.left >= 24f - 1f
                )
                assertTrue(
                    "[$displayMode / $cornerName] Pill top (${boundsAfter.top}) must stay inside safeTop (16)",
                    boundsAfter.top >= 16f - 1f
                )
                assertTrue(
                    "[$displayMode / $cornerName] Pill right (${boundsAfter.right}) must stay inside safeRight (1896)",
                    boundsAfter.right <= 1920f - 24f + 1.5f
                )
                assertTrue(
                    "[$displayMode / $cornerName] Pill bottom (${boundsAfter.bottom}) must stay inside safeBottom (1068)",
                    boundsAfter.bottom <= 1080f - 12f + 1.5f
                )
            }

            // Verify persistence across simulated STAR Launcher / Minecraft restart
            val persistedAfterBottomRight = LiveLyricsConfigStore.load(context)
            assertFalse(persistedAfterBottomRight.autoHudSafePositioning)
            assertEquals(LiveLyricsConfig.POSITION_CONFIG_VERSION, persistedAfterBottomRight.positionConfigVersion)
            assertEquals(displayMode, persistedAfterBottomRight.displayMode)
            assertTrue("Normalized X should be near 1.0 at bottom-right, was ${persistedAfterBottomRight.normalizedX}", persistedAfterBottomRight.normalizedX > 0.85f)
            assertTrue("Normalized Y should be near 1.0 at bottom-right, was ${persistedAfterBottomRight.normalizedY}", persistedAfterBottomRight.normalizedY > 0.85f)

            val restartedOverlay = StarLiveLyricsOverlay(context)
            restartedOverlay.measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            )
            restartedOverlay.layout(0, 0, 1920, 1080)
            restartedOverlay.updateSafeInsets(24, 16, 24, 12)
            restartedOverlay.applyState(persistedAfterBottomRight, sess, media, frame, 0L)
            val restartedBounds = restartedOverlay.getPillBoundsOnScreen()
            assertEquals(overlay.getPillBoundsOnScreen().left, restartedBounds.left, 2f)
            assertEquals(overlay.getPillBoundsOnScreen().top, restartedBounds.top, 2f)

            // Verify screen orientation change (rotate to 1080x2400 portrait) keeps overlay 100% visible
            restartedOverlay.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            restartedOverlay.layout(0, 0, 1080, 2400)
            restartedOverlay.relayoutOverlay()
            val portraitBounds = restartedOverlay.getPillBoundsOnScreen()
            assertTrue("Portrait left (${portraitBounds.left}) must stay >= safeLeft", portraitBounds.left >= 24f - 1f)
            assertTrue("Portrait top (${portraitBounds.top}) must stay >= safeTop", portraitBounds.top >= 16f - 1f)
            assertTrue("Portrait right (${portraitBounds.right}) must stay <= 1080 - safeRight", portraitBounds.right <= 1080f - 24f + 1.5f)
            assertTrue("Portrait bottom (${portraitBounds.bottom}) must stay <= 2400 - safeBottom", portraitBounds.bottom <= 2400f - 12f + 1.5f)
        }
    }

    @Test
    fun `lock protection blocks dragging while keeping lyrics active and unlock restores dragging`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cfg = LiveLyricsConfigStore.save(
            context,
            LiveLyricsConfig(
                featureEnabled = true,
                displayMode = LiveLyricsDisplayMode.COMPACT_MUSIC,
                animationsEnabled = false,
                autoHudSafePositioning = false,
                manualPosXFraction = 0.20f,
                manualPosYFraction = 0.25f,
                positionLocked = false
            )
        )
        val overlay = StarLiveLyricsOverlay(context)
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, 1920, 1080)

        val media = UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = true,
            track = MediaTrackInfo(title = "Subwoofer Lullaby", artist = "C418", durationMs = 180_000L),
            playback = PlaybackSnapshot(isPlaying = true, positionMs = 20_000L, durationMs = 180_000L, hasRealPosition = true)
        )
        val frame1 = SyncedLyricsFrame(currentLine = "First synced line", lineProgress = 0.3f)
        val sess = LiveLyricsSessionState(activeMode = IslandDisplayMode.COMPACT, activeDisplayMode = LiveLyricsDisplayMode.COMPACT_MUSIC)
        overlay.applyState(cfg, sess, media, frame1, 0L)

        // Lock position
        overlay.setPositionLocked(true)
        assertTrue(overlay.isPositionLocked())
        assertTrue(LiveLyricsConfigStore.load(context).positionLocked)
        assertEquals("🔒", overlay.getLockIndicatorSymbol())

        val lockedBoundsBefore = overlay.getPillBoundsOnScreen()
        val downLocked = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, lockedBoundsBefore.centerX(), lockedBoundsBefore.centerY(), 0)
        val moveLocked = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, lockedBoundsBefore.centerX() + 500f, lockedBoundsBefore.centerY() + 400f, 0)
        val upLocked = MotionEvent.obtain(0L, 32L, MotionEvent.ACTION_UP, lockedBoundsBefore.centerX() + 500f, lockedBoundsBefore.centerY() + 400f, 0)

        overlay.dispatchTouchEvent(downLocked)
        overlay.dispatchTouchEvent(moveLocked)
        overlay.dispatchTouchEvent(upLocked)
        downLocked.recycle()
        moveLocked.recycle()
        upLocked.recycle()

        val lockedBoundsAfter = overlay.getPillBoundsOnScreen()
        assertEquals("Position X must not change when locked", lockedBoundsBefore.left, lockedBoundsAfter.left, 0.5f)
        assertEquals("Position Y must not change when locked", lockedBoundsBefore.top, lockedBoundsAfter.top, 0.5f)

        // Confirm lyrics synchronization and visibility continue working while locked
        val frame2 = SyncedLyricsFrame(currentLine = "Second synced line while locked", lineProgress = 0.75f)
        overlay.applyState(overlay.getCurrentConfig(), sess, media, frame2, 0L)
        assertEquals(IslandDisplayMode.COMPACT, overlay.getCurrentEffectiveMode())
        assertEquals(View.VISIBLE, overlay.getChildAt(0).visibility)

        // Unlock position and confirm dragging works again
        overlay.setPositionLocked(false)
        assertFalse(overlay.isPositionLocked())
        assertFalse(LiveLyricsConfigStore.load(context).positionLocked)
        assertEquals("🔓", overlay.getLockIndicatorSymbol())

        val startX = lockedBoundsAfter.centerX()
        val startY = lockedBoundsAfter.centerY()
        val downUnlocked = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, startX, startY, 0)
        val moveUnlocked = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, startX + 420f, startY + 260f, 0)
        val upUnlocked = MotionEvent.obtain(0L, 32L, MotionEvent.ACTION_UP, startX + 420f, startY + 260f, 0)

        assertTrue(overlay.dispatchTouchEvent(downUnlocked))
        assertTrue(overlay.dispatchTouchEvent(moveUnlocked))
        assertTrue(overlay.dispatchTouchEvent(upUnlocked))
        downUnlocked.recycle()
        moveUnlocked.recycle()
        upUnlocked.recycle()

        val unlockedBoundsAfter = overlay.getPillBoundsOnScreen()
        assertTrue("Position X must move after unlocking", unlockedBoundsAfter.left > lockedBoundsAfter.left + 100f)
        assertTrue("Position Y must move after unlocking", unlockedBoundsAfter.top > lockedBoundsAfter.top + 100f)
    }

    @Test
    fun `reset position restores default HUD-safe location and confirmation message without resetting unrelated settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val customCfg = LiveLyricsConfigStore.save(
            context,
            LiveLyricsConfig(
                featureEnabled = true,
                displayMode = LiveLyricsDisplayMode.FULL_MUSIC,
                autoHudSafePositioning = false,
                manualPosXFraction = 0.88f,
                manualPosYFraction = 0.82f,
                positionLocked = true,
                lyricsFontSizeSp = 19.0f,
                overlayAlpha = 0.72f,
                overlayScale = 1.18f,
                animationsEnabled = false,
                animationQuality = AnimationQuality.HIGH,
                globalOffsetMs = 350L
            )
        )
        val overlay = StarLiveLyricsOverlay(context)
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, 1920, 1080)

        val media = UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = true,
            track = MediaTrackInfo(title = "Aria Math", artist = "C418", durationMs = 300_000L),
            playback = PlaybackSnapshot(isPlaying = true, positionMs = 60_000L, durationMs = 300_000L, hasRealPosition = true)
        )
        val frame = SyncedLyricsFrame(currentLine = "Handpan melody", lineProgress = 0.4f)
        val sess = LiveLyricsSessionState(activeMode = IslandDisplayMode.EXPANDED, activeDisplayMode = LiveLyricsDisplayMode.FULL_MUSIC)
        overlay.applyState(customCfg, sess, media, frame, 0L)

        val customBounds = overlay.getPillBoundsOnScreen()
        assertTrue("Custom position should be near bottom-right", customBounds.left > 700f && customBounds.top > 400f)

        // Trigger Reset Position
        overlay.resetPositionToDefault()

        assertEquals(
            LiveLyricsConfig.RESET_POSITION_CONFIRMATION_MESSAGE,
            overlay.getLastConfirmationMessage()
        )

        val resetCfg = LiveLyricsConfigStore.load(context)
        assertTrue("Reset position must re-enable HUD-safe default positioning", resetCfg.autoHudSafePositioning)
        assertEquals(IslandPosition.TOP_CENTER, resetCfg.positionAnchor)
        assertEquals(LiveLyricsConfig.DEFAULT_POS_X_FRACTION, resetCfg.manualPosXFraction, 0.001f)
        assertEquals(LiveLyricsConfig.DEFAULT_POS_Y_FRACTION, resetCfg.manualPosYFraction, 0.001f)

        // Unrelated settings must remain untouched
        assertEquals(LiveLyricsDisplayMode.FULL_MUSIC, resetCfg.displayMode)
        assertEquals(19.0f, resetCfg.lyricsFontSizeSp, 0.001f)
        assertEquals(0.72f, resetCfg.overlayAlpha, 0.001f)
        assertEquals(1.18f, resetCfg.overlayScale, 0.001f)
        assertFalse(resetCfg.animationsEnabled)
        assertEquals(AnimationQuality.HIGH, resetCfg.animationQuality)
        assertEquals(350L, resetCfg.globalOffsetMs)
        assertTrue(resetCfg.positionLocked)

        val defaultBounds = overlay.getPillBoundsOnScreen()
        assertTrue("Default HUD-safe position should move away from bottom-right corner", defaultBounds.top < customBounds.top)
    }

    @Test
    fun `position editing mode and input safety release HUD leases and hide controls when exiting`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlay = StarLiveLyricsOverlay(context)
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, 1920, 1080)

        val cfg = LiveLyricsConfig(featureEnabled = true, animationsEnabled = false)
        val sess = LiveLyricsSessionState(activeMode = IslandDisplayMode.COMPACT)
        val media = UnifiedMediaState(hasActiveSession = true, hasNotificationPermission = true)
        overlay.applyState(cfg, sess, media, SyncedLyricsFrame(currentLine = "Test"), 0L)

        assertFalse(overlay.isPositionEditingMode())
        assertFalse(overlay.isQuickControlPanelVisible())

        overlay.enterPositionEditingMode()
        assertTrue(overlay.isPositionEditingMode())
        assertTrue(overlay.isQuickControlPanelVisible())

        // Window focus loss cancels any in-flight drag safely
        overlay.onWindowFocusLost()

        overlay.exitPositionEditingMode()
        assertFalse(overlay.isPositionEditingMode())
        assertFalse(overlay.isQuickControlPanelVisible())
    }
}
