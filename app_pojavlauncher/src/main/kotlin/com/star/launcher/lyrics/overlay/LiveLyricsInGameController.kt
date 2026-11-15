package com.star.launcher.lyrics.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.star.launcher.lyrics.config.AnimationQuality
import com.star.launcher.lyrics.config.IslandDisplayMode
import com.star.launcher.lyrics.config.LiveLyricsConfig
import com.star.launcher.lyrics.config.LiveLyricsConfigStore
import com.star.launcher.lyrics.config.LiveLyricsDisplayMode
import com.star.launcher.lyrics.config.LiveLyricsSessionState
import com.star.launcher.lyrics.engine.LyricsDocument
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.LyricsLookupOutcome
import com.star.launcher.lyrics.engine.LyricsSynchronizer
import com.star.launcher.lyrics.engine.SyncedLyricsFrame
import com.star.launcher.lyrics.media.UnifiedMediaSourceCoordinator
import com.star.launcher.lyrics.media.UnifiedMediaState
import com.star.launcher.lyrics.provider.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * In-game lifecycle & synchronization controller for STAR Live Lyrics inside `:game` (`MainActivity`).
 *
 * Ensures:
 * - Zero continuous polling of media sessions (event-driven via [UnifiedMediaSourceCoordinator])
 * - Lightweight sync tick that automatically stops when hidden, paused, or disabled
 * - Immediate cleanup when feature is disabled or Activity pauses/destroys
 */
class LiveLyricsInGameController(
    context: Context,
    private val overlay: StarLiveLyricsOverlay
) : StarLiveLyricsOverlay.Listener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mediaCoordinator = UnifiedMediaSourceCoordinator(appContext)
    private val lyricsRepository = LyricsRepository(appContext)

    private var config: LiveLyricsConfig = LiveLyricsConfigStore.load(appContext)
    private var sessionState = LiveLyricsSessionState(
        activeMode = config.resolvedIslandMode(),
        activeDisplayMode = config.displayMode
    )

    private var isResumed = false
    private var lastTrackKey = ""
    private var wasPlaying = false
    private var activeDocument: LyricsDocument? = null
    private var activeLookupStatus: String = ""
    private var activeTrackOffsetMs: Long = 0L
    private var lookupJob: Job? = null
    private var mediaCollectJob: Job? = null
    private var syncScheduled = false
    private var lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()

    private val benchmark = LiveLyricsBenchmarkMonitor()

    private val syncTickRunnable = object : Runnable {
        override fun run() {
            syncScheduled = false
            if (!shouldRunSyncLoop()) return
            val t0 = SystemClock.elapsedRealtimeNanos()
            val media = mediaCoordinator.state.value
            checkInactivityTimeout(media)
            val frame = computeCurrentFrame(media)
            overlay.applyState(config, sessionState, media, frame, activeTrackOffsetMs)
            benchmark.recordSyncTickNs(SystemClock.elapsedRealtimeNanos() - t0)
            scheduleNextSyncTick(media)
        }
    }

    init {
        overlay.setListener(this)
        mediaCollectJob = scope.launch {
            mediaCoordinator.state.collect { media ->
                onMediaStateUpdated(media)
            }
        }
        renderCurrentState()
    }

    fun onResume() {
        isResumed = true
        val prevEnabled = config.featureEnabled
        config = LiveLyricsConfigStore.reloadIfChanged(appContext)
        if (!prevEnabled && config.featureEnabled) {
            sessionState = sessionState.copy(
                activeMode = config.resolvedIslandMode(),
                activeDisplayMode = config.displayMode,
                temporarilyHidden = false
            )
        } else {
            sessionState = sessionState.copy(
                activeDisplayMode = config.displayMode,
                activeMode = if (config.displayMode == LiveLyricsDisplayMode.LYRICS_ONLY) {
                    IslandDisplayMode.COMPACT
                } else {
                    config.resolvedIslandMode()
                }
            )
        }
        applyConfigAndMediaActivation()
        if (config.featureEnabled && !sessionState.sessionDisabled) {
            mediaCoordinator.refresh()
        }
        renderCurrentState()
        ensureSyncLoopIfNeeded()
    }

    fun onPause() {
        isResumed = false
        stopSyncLoop()
        overlay.stopAllAnimations()
        if (!config.featureEnabled || sessionState.sessionDisabled) {
            mediaCoordinator.setActive(false, config.musicSourceMode, config.preferredAppPackage)
        }
    }

    fun onDestroy() {
        stopSyncLoop()
        lookupJob?.cancel()
        mediaCollectJob?.cancel()
        mediaCoordinator.releaseResources()
        overlay.setListener(null)
        overlay.stopAllAnimations()
        scope.cancel()
    }

    fun onConfigurationChanged() {
        overlay.relayoutOverlay()
    }

    fun updateSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        overlay.updateSafeInsets(left, top, right, bottom)
    }

    fun openQuickControls() {
        overlay.showQuickControlPanel()
    }

    fun toggleQuickControls() {
        overlay.toggleQuickControlPanel()
    }

    fun isQuickControlsOpen(): Boolean = overlay.isQuickControlPanelVisible() || overlay.isPositionEditingMode()

    fun getBenchmarkSnapshot(): LiveLyricsBenchmarkSnapshot = overlay.getBenchmarkSnapshot()

    override fun onToggleExpandCollapse() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        val current = sessionState.effectiveMode(config)
        val next = when (current) {
            IslandDisplayMode.COMPACT -> IslandDisplayMode.EXPANDED
            IslandDisplayMode.EXPANDED -> IslandDisplayMode.COMPACT
            IslandDisplayMode.HIDDEN -> config.defaultMode
        }
        sessionState = sessionState.copy(temporarilyHidden = false, activeMode = next)
        renderCurrentState()
        ensureSyncLoopIfNeeded()
    }

    override fun onHideRequested() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        sessionState = sessionState.copy(temporarilyHidden = true)
        stopSyncLoop()
        renderCurrentState()
    }

    override fun onShowRequested() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        sessionState = sessionState.copy(
            temporarilyHidden = false,
            sessionDisabled = false,
            activeMode = if (sessionState.activeMode == IslandDisplayMode.HIDDEN) config.defaultMode else sessionState.activeMode
        )
        applyConfigAndMediaActivation()
        renderCurrentState()
        ensureSyncLoopIfNeeded()
    }

    override fun onPlayPauseClicked() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        mediaCoordinator.togglePlayPause()
    }

    override fun onPreviousClicked() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        mediaCoordinator.skipToPrevious()
    }

    override fun onNextClicked() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        mediaCoordinator.skipToNext()
    }

    override fun onAdjustTrackOffset(deltaMs: Long) {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        if (lastTrackKey.isBlank()) return
        activeTrackOffsetMs = lyricsRepository.setTrackOffsetMs(lastTrackKey, activeTrackOffsetMs + deltaMs)
        renderCurrentState()
    }

    override fun onResetTrackOffset() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        if (lastTrackKey.isBlank()) return
        activeTrackOffsetMs = lyricsRepository.setTrackOffsetMs(lastTrackKey, 0L)
        renderCurrentState()
    }

    override fun onToggleSessionDisabled(disabled: Boolean) {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        sessionState = sessionState.copy(
            sessionDisabled = disabled,
            temporarilyHidden = if (!disabled) false else sessionState.temporarilyHidden
        )
        applyConfigAndMediaActivation()
        if (disabled) {
            stopSyncLoop()
            overlay.stopAllAnimations()
        } else {
            ensureSyncLoopIfNeeded()
        }
        renderCurrentState()
    }

    override fun onModeTransitioned(mode: IslandDisplayMode) {
        benchmark.setMode(mode)
    }

    override fun onSelectDisplayMode(mode: LiveLyricsDisplayMode) {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        config = LiveLyricsConfigStore.save(
            appContext,
            config.copy(displayMode = mode)
        )
        val nextIsland = when (mode) {
            LiveLyricsDisplayMode.FULL_MUSIC -> IslandDisplayMode.EXPANDED
            LiveLyricsDisplayMode.COMPACT_MUSIC -> IslandDisplayMode.COMPACT
            LiveLyricsDisplayMode.LYRICS_ONLY -> IslandDisplayMode.COMPACT
        }
        sessionState = sessionState.copy(
            temporarilyHidden = false,
            activeMode = nextIsland,
            activeDisplayMode = mode
        )
        overlay.invalidateHudPlacementCache()
        renderCurrentState()
        ensureSyncLoopIfNeeded()
    }

    override fun onPositionChanged(normalizedX: Float, normalizedY: Float) {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        config = LiveLyricsConfigStore.savePosition(
            context = appContext,
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            displayMode = sessionState.effectiveDisplayMode(config)
        )
        overlay.invalidateHudPlacementCache()
        renderCurrentState()
    }

    override fun onPositionResetRequested() {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        config = LiveLyricsConfigStore.resetPosition(appContext)
        overlay.invalidateHudPlacementCache()
        renderCurrentState()
    }

    override fun onPositionLockChanged(locked: Boolean) {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        config = LiveLyricsConfigStore.setPositionLocked(appContext, locked)
        renderCurrentState()
    }

    override fun onPositionEditingModeChanged(editing: Boolean) {
        lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
        renderCurrentState()
    }

    private fun applyConfigAndMediaActivation() {
        val active = config.featureEnabled && !sessionState.sessionDisabled
        mediaCoordinator.setActive(active, config.musicSourceMode, config.preferredAppPackage)
        if (!active) {
            lookupJob?.cancel()
            activeDocument = null
            activeLookupStatus = ""
            lastTrackKey = ""
        }
    }

    private fun onMediaStateUpdated(media: UnifiedMediaState) {
        if (!config.featureEnabled || sessionState.sessionDisabled) return

        val track = media.track
        val newTrackKey = if (media.hasActiveSession && track.hasTrack) track.trackKey else ""
        val trackChanged = newTrackKey != lastTrackKey

        if (trackChanged) {
            lastTrackKey = newTrackKey
            activeDocument = null
            activeLookupStatus = if (newTrackKey.isNotEmpty() && config.lyricsEnabled) "Searching lyrics…" else ""
            activeTrackOffsetMs = if (newTrackKey.isNotEmpty()) lyricsRepository.getTrackOffsetMs(newTrackKey) else 0L
            lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()

            if (newTrackKey.isNotEmpty() && config.restoreCompactOnNewTrack && !sessionState.temporarilyHidden) {
                sessionState = sessionState.copy(activeMode = IslandDisplayMode.COMPACT)
            }
            if (newTrackKey.isNotEmpty() && config.lyricsEnabled) {
                startLyricsLookup(track.title, track.artist, track.album, track.durationMs, newTrackKey)
            } else {
                lookupJob?.cancel()
            }
        }

        val isPlayingNow = media.hasActiveSession && media.playback.isPlaying
        if (!wasPlaying && isPlayingNow && config.autoShowOnMusicStart) {
            lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
            sessionState = sessionState.copy(temporarilyHidden = false)
        } else if (wasPlaying && !isPlayingNow && !media.hasActiveSession && config.autoHideOnMusicStop) {
            sessionState = sessionState.copy(temporarilyHidden = true)
        } else if (!media.hasActiveSession && config.autoHideOnMusicStop) {
            sessionState = sessionState.copy(temporarilyHidden = true)
        }
        wasPlaying = isPlayingNow

        renderCurrentState()
        ensureSyncLoopIfNeeded()
    }

    private fun startLyricsLookup(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        expectedTrackKey: String
    ) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val outcome = lyricsRepository.loadLyrics(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                provider = config.lyricsProvider
            )
            if (expectedTrackKey != lastTrackKey) return@launch
            when (outcome) {
                is LyricsLookupOutcome.Found -> {
                    activeDocument = outcome.document
                    activeLookupStatus = ""
                }
                is LyricsLookupOutcome.NotFound -> {
                    activeDocument = null
                    activeLookupStatus = "No lyrics found"
                }
                is LyricsLookupOutcome.RateLimited -> {
                    activeDocument = null
                    activeLookupStatus = "LRCLIB cooldown (${outcome.retryAfterSeconds}s)"
                }
                is LyricsLookupOutcome.NetworkFailure -> {
                    activeDocument = null
                    activeLookupStatus = "Lyrics offline"
                }
            }
            renderCurrentState()
            ensureSyncLoopIfNeeded()
        }
    }

    private fun checkInactivityTimeout(media: UnifiedMediaState) {
        val timeoutSec = config.autoHideTimeoutSeconds
        if (timeoutSec <= 0) return
        if (media.playback.isPlaying) {
            lastUserInteractionElapsedMs = SystemClock.elapsedRealtime()
            return
        }
        val elapsedSec = (SystemClock.elapsedRealtime() - lastUserInteractionElapsedMs) / 1000L
        if (elapsedSec >= timeoutSec && !sessionState.temporarilyHidden) {
            sessionState = sessionState.copy(temporarilyHidden = true)
            stopSyncLoop()
            renderCurrentState()
        }
    }

    private fun computeCurrentFrame(media: UnifiedMediaState): SyncedLyricsFrame {
        val lyricsWanted = config.lyricsEnabled ||
            sessionState.effectiveDisplayMode(config) == LiveLyricsDisplayMode.LYRICS_ONLY
        if (!lyricsWanted) {
            return SyncedLyricsFrame(
                trackKey = lastTrackKey,
                statusLabel = "Lyrics Off",
                effectivePositionMs = media.playback.currentPositionMs(),
                durationMs = media.playback.durationMs
            )
        }
        val doc = activeDocument
        if (doc == null) {
            return SyncedLyricsFrame(
                trackKey = lastTrackKey,
                statusLabel = activeLookupStatus.ifBlank { "No lyrics" },
                effectivePositionMs = media.playback.currentPositionMs(),
                durationMs = media.playback.durationMs
            )
        }
        return LyricsSynchronizer.synchronize(
            document = doc,
            effectivePositionMs = media.playback.currentPositionMs(),
            durationMs = media.playback.durationMs,
            hasRealPosition = media.playback.hasRealPosition,
            trackOffsetMs = activeTrackOffsetMs,
            globalOffsetMs = config.globalOffsetMs
        )
    }

    private fun renderCurrentState() {
        val media = mediaCoordinator.state.value
        val frame = computeCurrentFrame(media)
        overlay.applyState(config, sessionState, media, frame, activeTrackOffsetMs)
    }

    private fun shouldRunSyncLoop(): Boolean {
        if (!isResumed) return false
        if (!config.featureEnabled || sessionState.sessionDisabled) return false
        if (sessionState.effectiveMode(config) == IslandDisplayMode.HIDDEN) return false
        val media = mediaCoordinator.state.value
        return media.hasActiveSession && (media.playback.isPlaying || config.autoHideTimeoutSeconds > 0)
    }

    private fun ensureSyncLoopIfNeeded() {
        if (!shouldRunSyncLoop()) {
            stopSyncLoop()
            return
        }
        if (!syncScheduled) {
            scheduleNextSyncTick(mediaCoordinator.state.value)
        }
    }

    private fun scheduleNextSyncTick(media: UnifiedMediaState) {
        if (!shouldRunSyncLoop()) return
        val perfSpec = com.star.launcher.compat.UiPerformanceProfileManager.currentSpec()
        val intervalMs = when {
            !media.playback.isPlaying -> 500L
            !config.lyricsEnabled -> 400L
            activeDocument?.format == LyricsFormat.WORD_SYNCED &&
                config.animationQuality == AnimationQuality.HIGH -> perfSpec.lyricsWordSyncIntervalMs
            activeDocument?.isSynchronized == true &&
                config.animationQuality != AnimationQuality.BATTERY_SAVER -> perfSpec.lyricsLineSyncIntervalMs
            else -> 300L
        }
        syncScheduled = true
        mainHandler.postDelayed(syncTickRunnable, intervalMs)
    }

    private fun stopSyncLoop() {
        syncScheduled = false
        mainHandler.removeCallbacks(syncTickRunnable)
    }
}
