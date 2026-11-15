package com.star.launcher.lyrics.media

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Event-driven monitor for active Android [MediaController] sessions via [MediaSessionManager].
 *
 * Uses zero continuous polling: relies exclusively on [MediaSessionManager.OnActiveSessionsChangedListener]
 * and [MediaController.Callback].
 */
class ExternalMediaSessionMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionManager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val artworkCache = LruCache<String, Bitmap>(8)
    private val appLabelCache = HashMap<String, String>()

    private var started = false
    private var preferredPackage: String? = null
    private var activeControllers: List<MediaController> = emptyList()
    private var selectedController: MediaController? = null
    private var artworkJob: Job? = null

    private val _state = MutableStateFlow(
        UnifiedMediaState(hasNotificationPermission = StarMediaNotificationListener.hasNotificationAccess(appContext))
    )
    val state: StateFlow<UnifiedMediaState> = _state.asStateFlow()

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessionsChanged(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishSelectedState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishSelectedState()
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    fun start() {
        if (started) {
            refreshSessions()
            return
        }
        val manager = sessionManager ?: return
        val hasPerm = StarMediaNotificationListener.hasNotificationAccess(appContext)
        if (!hasPerm) {
            _state.value = UnifiedMediaState(hasNotificationPermission = false)
            return
        }
        try {
            val comp = StarMediaNotificationListener.componentName(appContext)
            manager.addOnActiveSessionsChangedListener(activeSessionsListener, comp, mainHandler)
            started = true
            val current = manager.getActiveSessions(comp)
            onSessionsChanged(current)
        } catch (se: SecurityException) {
            Log.w(TAG, "Missing notification listener access for MediaSessionManager", se)
            started = false
            _state.value = UnifiedMediaState(hasNotificationPermission = false)
        }
    }

    fun stop() {
        if (started) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
            started = false
        }
        detachSelectedCallback()
        artworkJob?.cancel()
        artworkJob = null
        activeControllers = emptyList()
        _state.value = UnifiedMediaState(
            hasNotificationPermission = StarMediaNotificationListener.hasNotificationAccess(appContext)
        )
    }

    fun releaseResources() {
        stop()
        artworkCache.evictAll()
        appLabelCache.clear()
    }

    fun refreshSessions() {
        val manager = sessionManager ?: return
        val hasPerm = StarMediaNotificationListener.hasNotificationAccess(appContext)
        if (!hasPerm) {
            if (started) stop() else _state.value = UnifiedMediaState(hasNotificationPermission = false)
            return
        }
        if (!started) {
            start()
            return
        }
        try {
            val current = manager.getActiveSessions(StarMediaNotificationListener.componentName(appContext))
            onSessionsChanged(current)
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification access revoked", se)
            stop()
        }
    }

    fun setPreferredPackage(packageName: String?) {
        val clean = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (preferredPackage == clean) return
        preferredPackage = clean
        if (started) {
            onSessionsChanged(activeControllers)
        }
    }

    fun play(): Boolean = dispatchTransport { it.play() }
    fun pause(): Boolean = dispatchTransport { it.pause() }
    fun skipToPrevious(): Boolean = dispatchTransport { it.skipToPrevious() }
    fun skipToNext(): Boolean = dispatchTransport { it.skipToNext() }
    fun seekTo(positionMs: Long): Boolean = dispatchTransport { it.seekTo(positionMs.coerceAtLeast(0L)) }

    private fun dispatchTransport(action: (MediaController.TransportControls) -> Unit): Boolean {
        val controller = selectedController ?: return false
        return runCatching {
            action(controller.transportControls)
            true
        }.getOrElse {
            Log.w(TAG, "Transport command failed for ${controller.packageName}", it)
            false
        }
    }

    private fun onSessionsChanged(rawControllers: List<MediaController>) {
        // Filter out STAR's own internal player session; STAR player is managed by UnifiedMediaSourceCoordinator
        val external = rawControllers.filterNot { isStarInternalSession(it) }
        activeControllers = external

        val chosen = selectBestController(external, preferredPackage)
        if (chosen?.sessionToken != selectedController?.sessionToken) {
            detachSelectedCallback()
            selectedController = chosen
            chosen?.registerCallback(controllerCallback, mainHandler)
        }
        publishSelectedState()
    }

    private fun detachSelectedCallback() {
        selectedController?.let { prev ->
            runCatching { prev.unregisterCallback(controllerCallback) }
        }
        selectedController = null
    }

    private fun publishSelectedState() {
        val hasPerm = StarMediaNotificationListener.hasNotificationAccess(appContext)
        val apps = activeControllers.map { controller ->
            val pkg = controller.packageName
            val playing = isControllerPlaying(controller)
            val title = controller.metadata?.let {
                it.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            }.orEmpty()
            ActiveMediaApp(
                packageName = pkg,
                appLabel = resolveAppLabel(pkg),
                isPlaying = playing,
                trackTitle = title
            )
        }

        val controller = selectedController
        if (controller == null) {
            _state.value = UnifiedMediaState(
                hasActiveSession = false,
                hasNotificationPermission = hasPerm,
                activeApps = apps
            )
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

        val pkg = controller.packageName
        val artKey = "${pkg}|${title}|${artist}|${album}"
        val cachedArt = artworkCache.get(artKey)
        val cachedAccent = MediaTrackInfo.DEFAULT_ACCENT_COLOR

        val stateCode = playbackState?.state ?: PlaybackState.STATE_NONE
        val isPlaying = stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_FAST_FORWARDING
        val rawPos = playbackState?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val hasRealPos = rawPos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && rawPos >= 0L
        val lastUpdate = playbackState?.lastPositionUpdateTime ?: 0L
        val actions = playbackState?.actions ?: 0L

        val prevSnapshot = _state.value.playback
        val prevTrackKey = _state.value.track.trackKey
        val effectiveBasePos = when {
            hasRealPos -> rawPos
            prevTrackKey == MediaTrackInfo(title = title, artist = artist, durationMs = durationMs).trackKey ->
                prevSnapshot.currentPositionMs()
            else -> 0L
        }
        val effectiveElapsed = when {
            hasRealPos && lastUpdate > 0L -> lastUpdate
            else -> SystemClock.elapsedRealtime()
        }

        val snapshot = PlaybackSnapshot(
            isPlaying = isPlaying,
            stateCode = stateCode,
            positionMs = effectiveBasePos,
            positionUpdateElapsedMs = effectiveElapsed,
            playbackSpeed = (playbackState?.playbackSpeed ?: 1.0f).takeIf { it > 0f } ?: 1.0f,
            durationMs = durationMs,
            hasRealPosition = hasRealPos,
            capabilities = MediaControlCapability(
                canPlay = (actions and PlaybackState.ACTION_PLAY) != 0L ||
                    (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L,
                canPause = (actions and PlaybackState.ACTION_PAUSE) != 0L ||
                    (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L,
                canPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
                canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
                canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L
            )
        )

        val trackInfo = MediaTrackInfo(
            sourcePackage = pkg,
            sourceAppLabel = resolveAppLabel(pkg),
            isStarPlayer = false,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkKey = artKey,
            artworkBitmap = cachedArt,
            accentColor = cachedAccent
        )

        _state.value = UnifiedMediaState(
            hasActiveSession = true,
            hasNotificationPermission = hasPerm,
            track = trackInfo,
            playback = snapshot,
            activeApps = apps
        )

        // Decode/downscale artwork & extract accent color off the UI thread
        val rawArt = extractRawBitmap(metadata)
        if (cachedArt != null) {
            scope.launch {
                val accent = AlbumPaletteExtractor.extractAccentColor(artKey, cachedArt)
                updateAccentIfSameTrack(artKey, cachedArt, accent)
            }
        } else if (rawArt != null && !rawArt.isRecycled) {
            artworkJob?.cancel()
            artworkJob = scope.launch {
                val scaled = AlbumPaletteExtractor.downscaleArtwork(rawArt, 144)
                artworkCache.put(artKey, scaled)
                val accent = AlbumPaletteExtractor.extractAccentColor(artKey, scaled)
                updateAccentIfSameTrack(artKey, scaled, accent)
            }
        }
    }

    private fun updateAccentIfSameTrack(artKey: String, bitmap: Bitmap, accentColor: Int) {
        val cur = _state.value
        if (cur.hasActiveSession && cur.track.artworkKey == artKey) {
            _state.value = cur.copy(
                track = cur.track.copy(artworkBitmap = bitmap, accentColor = accentColor)
            )
        }
    }

    private fun extractRawBitmap(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        return metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun selectBestController(
        controllers: List<MediaController>,
        preferredPkg: String?
    ): MediaController? {
        if (controllers.isEmpty()) return null
        if (preferredPkg != null) {
            val preferredMatches = controllers.filter { it.packageName == preferredPkg }
            if (preferredMatches.isNotEmpty()) {
                return preferredMatches.firstOrNull { isControllerPlaying(it) } ?: preferredMatches.first()
            }
        }
        return controllers.firstOrNull { isControllerPlaying(it) }
            ?: controllers.firstOrNull { controllerHasTitle(it) }
            ?: controllers.first()
    }

    private fun isControllerPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun controllerHasTitle(controller: MediaController): Boolean {
        val md = controller.metadata ?: return false
        return !md.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
            !md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrBlank()
    }

    private fun isStarInternalSession(controller: MediaController): Boolean {
        return controller.packageName == appContext.packageName &&
            controller.tag == STAR_SESSION_TAG
    }

    private fun resolveAppLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val pm = appContext.packageManager
        val label = runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        }.getOrElse {
            packageName.substringAfterLast('.').replaceFirstChar { ch -> ch.uppercase() }
        }
        appLabelCache[packageName] = label
        return label
    }

    companion object {
        private const val TAG = "ExternalMediaMonitor"
        const val STAR_SESSION_TAG = "STAR_MUSIC_SESSION"
    }
}
