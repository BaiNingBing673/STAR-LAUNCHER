package com.star.launcher.lyrics.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.star.launcher.lyrics.media.ExternalMediaSessionMonitor
import com.star.launcher.ui.dashboard.AetherDashboardActivity
import net.kdt.pojavlaunch.R

/**
 * Optional lightweight background audio player integrated into STAR Launcher.
 * Publishes a standard Android [MediaSession] and media notification without forcing users to use it.
 */
class StarMusicPlayerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: Any? = null

    private var playlist: List<StarLocalTrack> = emptyList()
    private var currentIndex: Int = -1
    private var isPrepared: Boolean = false
    private var currentArtBitmap: Bitmap? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.35f, 0.35f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ensureNotificationChannel()
        initMediaSession()
        playlist = StarPlaylistStore.loadPlaylist(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        playlist = StarPlaylistStore.loadPlaylist(this)
        when (action) {
            ACTION_PLAY_INDEX -> {
                val idx = intent.getIntExtra(EXTRA_INDEX, 0)
                playAtIndex(idx)
            }
            ACTION_PLAY_TRACK_ID -> {
                val trackId = intent.getStringExtra(EXTRA_TRACK_ID)
                val idx = playlist.indexOfFirst { it.id == trackId }
                if (idx >= 0) playAtIndex(idx)
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (mediaPlayer?.isPlaying == true) pausePlayback() else resumeOrStart()
            }
            ACTION_PLAY -> resumeOrStart()
            ACTION_PAUSE -> pausePlayback()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_SEEK -> {
                val posMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
                seekTo(posMs)
            }
            ACTION_RELOAD_PLAYLIST -> {
                if (currentIndex >= playlist.size) {
                    currentIndex = if (playlist.isEmpty()) -1 else 0
                }
                publishStateAndNotification()
            }
            ACTION_STOP -> {
                stopPlaybackAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releasePlayer()
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        currentArtBitmap?.recycle()
        currentArtBitmap = null
        StarPlayerStateBridge.write(this, StarPlayerSnapshot(isServiceActive = false))
        broadcastStateChanged()
        super.onDestroy()
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, ExternalMediaSessionMonitor.STAR_SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumeOrStart()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stopPlaybackAndSelf()
            })
            isActive = true
        }
    }

    private fun playAtIndex(index: Int) {
        if (playlist.isEmpty()) {
            stopPlaybackAndSelf()
            return
        }
        val safeIdx = ((index % playlist.size) + playlist.size) % playlist.size
        val track = playlist[safeIdx]
        currentIndex = safeIdx
        isPrepared = false

        currentArtBitmap?.recycle()
        currentArtBitmap = track.artworkPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }

        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied")
        }

        releasePlayer()
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(this, Uri.parse(track.uriString))
            player.setOnPreparedListener { mp ->
                isPrepared = true
                mp.start()
                publishStateAndNotification()
            }
            player.setOnCompletionListener {
                if (playlist.size > 1) {
                    skipNext()
                } else {
                    isPrepared = false
                    publishStateAndNotification()
                }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra for ${track.title}")
                isPrepared = false
                publishStateAndNotification()
                true
            }
            player.prepareAsync()
            publishStateAndNotification()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start track ${track.title}", t)
            isPrepared = false
            publishStateAndNotification()
        }
    }

    private fun resumeOrStart() {
        val player = mediaPlayer
        if (player != null && isPrepared) {
            if (!player.isPlaying && requestAudioFocus()) {
                player.start()
                publishStateAndNotification()
            }
            return
        }
        if (playlist.isNotEmpty()) {
            playAtIndex(if (currentIndex in playlist.indices) currentIndex else 0)
        }
    }

    private fun pausePlayback() {
        val player = mediaPlayer
        if (player != null && isPrepared && player.isPlaying) {
            player.pause()
            publishStateAndNotification()
        }
    }

    private fun skipNext() {
        if (playlist.isEmpty()) return
        playAtIndex(if (currentIndex < 0) 0 else currentIndex + 1)
    }

    private fun skipPrevious() {
        if (playlist.isEmpty()) return
        val pos = if (isPrepared) (mediaPlayer?.currentPosition ?: 0) else 0
        if (pos > 3_500 && currentIndex in playlist.indices) {
            seekTo(0L)
        } else {
            playAtIndex(if (currentIndex < 0) 0 else currentIndex - 1)
        }
    }

    private fun seekTo(positionMs: Long) {
        val player = mediaPlayer
        if (player != null && isPrepared) {
            player.seekTo(positionMs.toInt())
            publishStateAndNotification()
        }
    }

    private fun stopPlaybackAndSelf() {
        releasePlayer()
        abandonAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        StarPlayerStateBridge.write(this, StarPlayerSnapshot(isServiceActive = false))
        broadcastStateChanged()
        stopSelf()
    }

    private fun releasePlayer() {
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        mediaPlayer = null
        isPrepared = false
    }

    private fun publishStateAndNotification() {
        val track = playlist.getOrNull(currentIndex)
        val playing = isPrepared && (mediaPlayer?.isPlaying == true)
        val posMs = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
        val durMs = if (isPrepared) {
            (mediaPlayer?.duration?.toLong() ?: track?.durationMs ?: 0L).coerceAtLeast(0L)
        } else {
            track?.durationMs ?: 0L
        }
        val stateCode = when {
            playing -> PlaybackState.STATE_PLAYING
            isPrepared -> PlaybackState.STATE_PAUSED
            track != null -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_STOPPED
        }
        val elapsedNow = SystemClock.elapsedRealtime()

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, posMs, 1.0f, elapsedNow)
                .build()
        )

        if (track != null) {
            val mdBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
            currentArtBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                mdBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            }
            mediaSession?.setMetadata(mdBuilder.build())
        }

        val snapshot = StarPlayerSnapshot(
            isServiceActive = track != null,
            isPlaying = playing,
            currentIndex = currentIndex,
            currentTrack = track,
            positionMs = posMs,
            positionUpdateElapsedMs = elapsedNow,
            durationMs = durMs,
            stateCode = stateCode,
            updatedAtMs = System.currentTimeMillis()
        )
        StarPlayerStateBridge.write(this, snapshot)
        broadcastStateChanged()

        if (track != null) {
            startForeground(NOTIFICATION_ID, buildMediaNotification(track, playing))
        }
    }

    private fun buildMediaNotification(track: StarLocalTrack, playing: Boolean): Notification {
        val openAppIntent = Intent(this, AetherDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(this, 10, openAppIntent, pendingFlags())

        val prevPending = serviceActionPending(ACTION_PREVIOUS, 11)
        val playPausePending = serviceActionPending(if (playing) ACTION_PAUSE else ACTION_PLAY, 12)
        val nextPending = serviceActionPending(ACTION_NEXT, 13)
        val stopPending = serviceActionPending(ACTION_STOP, 14)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setContentIntent(contentPending)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    prevPending
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    playPausePending
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextPending
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPending
                ).build()
            )

        currentArtBitmap?.takeIf { !it.isRecycled }?.let { builder.setLargeIcon(it) }

        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        mediaSession?.sessionToken?.let { style.setMediaSession(it) }
        builder.setStyle(style)

        return builder.build()
    }

    private fun serviceActionPending(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, StarMusicPlayerService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, reqCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "STAR Music Player",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Optional background playback controls for STAR Music Player"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest as? AudioFocusRequest
            if (req != null) {
                am.abandonAudioFocusRequest(req)
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun broadcastStateChanged() {
        sendBroadcast(
            Intent(StarPlayerStateBridge.ACTION_STATE_CHANGED).apply {
                setPackage(packageName)
            }
        )
    }

    companion object {
        private const val TAG = "StarMusicPlayerService"
        private const val CHANNEL_ID = "star_music_player_channel"
        private const val NOTIFICATION_ID = 42087

        const val ACTION_PLAY_INDEX = "com.star.launcher.music.PLAY_INDEX"
        const val ACTION_PLAY_TRACK_ID = "com.star.launcher.music.PLAY_TRACK_ID"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.star.launcher.music.TOGGLE_PLAY_PAUSE"
        const val ACTION_PLAY = "com.star.launcher.music.PLAY"
        const val ACTION_PAUSE = "com.star.launcher.music.PAUSE"
        const val ACTION_NEXT = "com.star.launcher.music.NEXT"
        const val ACTION_PREVIOUS = "com.star.launcher.music.PREVIOUS"
        const val ACTION_SEEK = "com.star.launcher.music.SEEK"
        const val ACTION_RELOAD_PLAYLIST = "com.star.launcher.music.RELOAD_PLAYLIST"
        const val ACTION_STOP = "com.star.launcher.music.STOP"

        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_POSITION_MS = "extra_position_ms"
    }
}
