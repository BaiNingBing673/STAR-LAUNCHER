package com.star.launcher.lyrics.media

import android.content.Context
import com.star.launcher.lyrics.config.MusicSourceMode
import com.star.launcher.lyrics.player.StarMusicPlayerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Combines external Android [MediaSessionManager] sessions and STAR's optional built-in
 * [StarMusicPlayerClient] according to user-selected [MusicSourceMode].
 */
class UnifiedMediaSourceCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val externalMonitor = ExternalMediaSessionMonitor(appContext)
    val starPlayerClient = StarMusicPlayerClient(appContext)

    private var active = false
    private var sourceMode: MusicSourceMode = MusicSourceMode.AUTO

    private val _state = MutableStateFlow(
        UnifiedMediaState(hasNotificationPermission = StarMediaNotificationListener.hasNotificationAccess(appContext))
    )
    val state: StateFlow<UnifiedMediaState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(externalMonitor.state, starPlayerClient.mediaState) { ext, star ->
                mergeStates(ext, star, sourceMode)
            }.collect { merged ->
                if (active) {
                    _state.value = merged
                }
            }
        }
    }

    fun setActive(enabled: Boolean, mode: MusicSourceMode, preferredExternalPackage: String?) {
        sourceMode = mode
        externalMonitor.setPreferredPackage(preferredExternalPackage)
        if (!enabled) {
            if (active) {
                active = false
                externalMonitor.stop()
                starPlayerClient.stopObserving()
                _state.value = UnifiedMediaState(
                    hasNotificationPermission = StarMediaNotificationListener.hasNotificationAccess(appContext)
                )
            }
            return
        }

        active = true
        when (mode) {
            MusicSourceMode.AUTO -> {
                externalMonitor.start()
                starPlayerClient.startObserving()
            }
            MusicSourceMode.EXTERNAL_ONLY -> {
                starPlayerClient.stopObserving()
                externalMonitor.start()
            }
            MusicSourceMode.STAR_PLAYER_ONLY -> {
                externalMonitor.stop()
                starPlayerClient.startObserving()
            }
        }
        _state.value = mergeStates(externalMonitor.state.value, starPlayerClient.mediaState.value, mode)
    }

    fun refresh() {
        if (!active) return
        if (sourceMode != MusicSourceMode.STAR_PLAYER_ONLY) {
            externalMonitor.refreshSessions()
        }
        if (sourceMode != MusicSourceMode.EXTERNAL_ONLY) {
            starPlayerClient.refreshFromDisk()
        }
    }

    fun releaseResources() {
        active = false
        externalMonitor.releaseResources()
        starPlayerClient.releaseResources()
        _state.value = UnifiedMediaState(
            hasNotificationPermission = StarMediaNotificationListener.hasNotificationAccess(appContext)
        )
    }

    fun togglePlayPause(): Boolean {
        val current = _state.value
        if (!current.hasActiveSession) return false
        return if (current.track.isStarPlayer) {
            starPlayerClient.togglePlayPause()
            true
        } else {
            if (current.playback.isPlaying) externalMonitor.pause() else externalMonitor.play()
        }
    }

    fun skipToPrevious(): Boolean {
        val current = _state.value
        if (!current.hasActiveSession) return false
        return if (current.track.isStarPlayer) {
            starPlayerClient.previous()
            true
        } else {
            externalMonitor.skipToPrevious()
        }
    }

    fun skipToNext(): Boolean {
        val current = _state.value
        if (!current.hasActiveSession) return false
        return if (current.track.isStarPlayer) {
            starPlayerClient.next()
            true
        } else {
            externalMonitor.skipToNext()
        }
    }

    fun seekTo(positionMs: Long): Boolean {
        val current = _state.value
        if (!current.hasActiveSession) return false
        return if (current.track.isStarPlayer) {
            starPlayerClient.seekTo(positionMs)
            true
        } else {
            externalMonitor.seekTo(positionMs)
        }
    }

    private fun mergeStates(
        ext: UnifiedMediaState,
        star: UnifiedMediaState,
        mode: MusicSourceMode
    ): UnifiedMediaState {
        return when (mode) {
            MusicSourceMode.EXTERNAL_ONLY -> ext
            MusicSourceMode.STAR_PLAYER_ONLY -> star.copy(hasNotificationPermission = true)
            MusicSourceMode.AUTO -> {
                val chosen = when {
                    star.hasActiveSession && star.playback.isPlaying -> star
                    ext.hasActiveSession && ext.playback.isPlaying -> ext
                    star.hasActiveSession && !ext.hasActiveSession -> star
                    ext.hasActiveSession -> ext
                    else -> ext
                }
                chosen.copy(
                    hasNotificationPermission = ext.hasNotificationPermission,
                    activeApps = ext.activeApps
                )
            }
        }
    }
}
