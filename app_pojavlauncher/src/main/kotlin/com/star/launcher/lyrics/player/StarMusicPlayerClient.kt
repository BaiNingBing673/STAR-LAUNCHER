package com.star.launcher.lyrics.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.star.launcher.lyrics.media.AlbumPaletteExtractor
import com.star.launcher.lyrics.media.MediaControlCapability
import com.star.launcher.lyrics.media.MediaTrackInfo
import com.star.launcher.lyrics.media.PlaybackSnapshot
import com.star.launcher.lyrics.media.UnifiedMediaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-safe client for the optional [StarMusicPlayerService].
 * Works across `:launcher` and `:game` without requiring Notification Listener permission.
 */
class StarMusicPlayerClient(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val artCache = LruCache<String, Bitmap>(6)

    private val _snapshot = MutableStateFlow(StarPlayerStateBridge.read(appContext))
    val snapshot: StateFlow<StarPlayerSnapshot> = _snapshot.asStateFlow()

    private val _mediaState = MutableStateFlow(toUnifiedMediaState(_snapshot.value))
    val mediaState: StateFlow<UnifiedMediaState> = _mediaState.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshFromDisk()
        }
    }

    fun startObserving() {
        if (!registered) {
            val filter = IntentFilter(StarPlayerStateBridge.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
        }
        refreshFromDisk()
    }

    fun stopObserving() {
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    fun releaseResources() {
        stopObserving()
        artCache.evictAll()
    }

    fun refreshFromDisk() {
        val snap = StarPlayerStateBridge.read(appContext)
        _snapshot.value = snap
        val baseState = toUnifiedMediaState(snap)
        _mediaState.value = baseState

        val artPath = snap.currentTrack?.artworkPath
        val artKey = baseState.track.artworkKey
        if (!artPath.isNullOrBlank() && artCache.get(artPath) == null) {
            scope.launch {
                val bmp = runCatching { BitmapFactory.decodeFile(artPath) }.getOrNull() ?: return@launch
                artCache.put(artPath, bmp)
                val accent = AlbumPaletteExtractor.extractAccentColor(artKey, bmp)
                val cur = _mediaState.value
                if (cur.track.artworkKey == artKey) {
                    _mediaState.value = cur.copy(
                        track = cur.track.copy(artworkBitmap = bmp, accentColor = accent)
                    )
                }
            }
        }
    }

    fun playIndex(index: Int) {
        sendForegroundCommand(StarMusicPlayerService.ACTION_PLAY_INDEX) {
            putExtra(StarMusicPlayerService.EXTRA_INDEX, index)
        }
    }

    fun playTrackId(trackId: String) {
        sendForegroundCommand(StarMusicPlayerService.ACTION_PLAY_TRACK_ID) {
            putExtra(StarMusicPlayerService.EXTRA_TRACK_ID, trackId)
        }
    }

    fun togglePlayPause() = sendSimpleCommand(StarMusicPlayerService.ACTION_TOGGLE_PLAY_PAUSE)
    fun play() = sendSimpleCommand(StarMusicPlayerService.ACTION_PLAY)
    fun pause() = sendSimpleCommand(StarMusicPlayerService.ACTION_PAUSE)
    fun next() = sendSimpleCommand(StarMusicPlayerService.ACTION_NEXT)
    fun previous() = sendSimpleCommand(StarMusicPlayerService.ACTION_PREVIOUS)
    fun stop() = sendSimpleCommand(StarMusicPlayerService.ACTION_STOP)

    fun seekTo(positionMs: Long) {
        sendSimpleCommand(StarMusicPlayerService.ACTION_SEEK) {
            putExtra(StarMusicPlayerService.EXTRA_POSITION_MS, positionMs)
        }
    }

    fun notifyPlaylistChanged() {
        if (_snapshot.value.isServiceActive) {
            sendSimpleCommand(StarMusicPlayerService.ACTION_RELOAD_PLAYLIST)
        }
    }

    private fun sendForegroundCommand(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(appContext, StarMusicPlayerService::class.java).apply {
            this.action = action
            extras()
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun sendSimpleCommand(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(appContext, StarMusicPlayerService::class.java).apply {
            this.action = action
            extras()
        }
        appContext.startService(intent)
    }

    private fun toUnifiedMediaState(snap: StarPlayerSnapshot): UnifiedMediaState {
        val track = snap.currentTrack
        if (!snap.isServiceActive || track == null) {
            return UnifiedMediaState(hasActiveSession = false)
        }
        val artBmp = track.artworkPath?.let { artCache.get(it) }
        val artKey = "star_player|${track.id}|${track.title}"
        return UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = true,
            track = MediaTrackInfo(
                sourcePackage = appContext.packageName,
                sourceAppLabel = "STAR Music",
                isStarPlayer = true,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = snap.durationMs,
                artworkKey = artKey,
                artworkBitmap = artBmp,
                accentColor = MediaTrackInfo.DEFAULT_ACCENT_COLOR
            ),
            playback = PlaybackSnapshot(
                isPlaying = snap.isPlaying,
                stateCode = snap.stateCode,
                positionMs = snap.positionMs,
                positionUpdateElapsedMs = snap.positionUpdateElapsedMs,
                playbackSpeed = 1.0f,
                durationMs = snap.durationMs,
                hasRealPosition = true,
                capabilities = MediaControlCapability(
                    canPlay = true,
                    canPause = true,
                    canPrevious = true,
                    canNext = true,
                    canSeek = true
                )
            )
        )
    }
}
