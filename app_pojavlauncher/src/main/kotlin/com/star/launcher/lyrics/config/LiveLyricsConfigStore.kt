package com.star.launcher.lyrics.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Cross-process file-backed persistence for [LiveLyricsConfig].
 *
 * STAR Launcher dashboard runs in `:launcher` while gameplay runs in `:game`.
 * Atomic JSON writes in app-private filesDir ensure immediate consistency across processes.
 */
object LiveLyricsConfigStore {
    private const val TAG = "LiveLyricsConfigStore"
    private const val CONFIG_FILE_NAME = "star_live_lyrics_config.json"

    private val _configFlow = MutableStateFlow(LiveLyricsConfig.defaults())
    val configFlow: StateFlow<LiveLyricsConfig> = _configFlow.asStateFlow()

    @Volatile
    private var lastObservedModifiedMs: Long = -2L

    @JvmStatic
    fun load(context: Context): LiveLyricsConfig {
        val file = configFile(context)
        val mod = if (file.isFile) file.lastModified() else -1L
        if (lastObservedModifiedMs != -2L && mod == lastObservedModifiedMs) {
            return _configFlow.value
        }
        val loaded = if (file.isFile) {
            runCatching { fromJson(file.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "Failed to parse Live Lyrics config, using defaults", it) }
                .getOrElse { LiveLyricsConfig.defaults() }
        } else {
            LiveLyricsConfig.defaults()
        }.sanitized()
        lastObservedModifiedMs = if (file.isFile) file.lastModified() else -1L
        _configFlow.value = loaded
        return loaded
    }

    @JvmStatic
    fun reloadIfChanged(context: Context): LiveLyricsConfig {
        val file = configFile(context)
        val mod = if (file.isFile) file.lastModified() else -1L
        if (mod != lastObservedModifiedMs) {
            return load(context)
        }
        return _configFlow.value
    }

    @JvmStatic
    fun save(context: Context, config: LiveLyricsConfig): LiveLyricsConfig {
        val clean = config.sanitized()
        val file = configFile(context)
        writeJsonAtomic(file, toJson(clean))
        lastObservedModifiedMs = if (file.isFile) file.lastModified() else System.currentTimeMillis()
        _configFlow.value = clean
        return clean
    }

    @JvmStatic
    fun update(context: Context, transform: (LiveLyricsConfig) -> LiveLyricsConfig): LiveLyricsConfig {
        val current = load(context)
        return save(context, transform(current))
    }

    @JvmStatic
    fun resetToDefault(context: Context): LiveLyricsConfig {
        return save(context, LiveLyricsConfig.defaults())
    }

    @JvmStatic
    fun savePosition(
        context: Context,
        normalizedX: Float,
        normalizedY: Float,
        displayMode: LiveLyricsDisplayMode? = null
    ): LiveLyricsConfig {
        return update(context) { current ->
            val updatedPos = current.withManualPosition(normalizedX, normalizedY)
            if (displayMode != null) updatedPos.copy(displayMode = displayMode) else updatedPos
        }
    }

    @JvmStatic
    fun resetPosition(context: Context): LiveLyricsConfig {
        return update(context) { current ->
            current.withDefaultPosition()
        }
    }

    @JvmStatic
    fun setPositionLocked(context: Context, locked: Boolean): LiveLyricsConfig {
        return update(context) { current ->
            current.withPositionLocked(locked)
        }
    }

    @JvmStatic
    fun toJson(config: LiveLyricsConfig): String {
        val clean = config.sanitized()
        return JSONObject().apply {
            put("version", LiveLyricsConfig.CONFIG_VERSION)
            put("positionConfigVersion", clean.positionConfigVersion)
            put("featureEnabled", clean.featureEnabled)
            put("overlayVisible", clean.overlayVisible)
            put("displayMode", clean.displayMode.name)
            put("lyricsEnabled", clean.lyricsEnabled)
            put("metadataEnabled", clean.metadataEnabled)
            put("showMusicalNote", clean.showMusicalNote)
            put("animationsEnabled", clean.animationsEnabled)
            put("animationQuality", clean.animationQuality.name)
            put("animationIntensity", clean.animationIntensity.name)
            put("animationSpeed", clean.animationSpeed.toDouble())
            put("noteAnimationSpeed", clean.noteAnimationSpeed.toDouble())
            put("defaultMode", clean.defaultMode.name)
            put("autoHudSafePositioning", clean.autoHudSafePositioning)
            put("positionAnchor", clean.positionAnchor.name)
            put("offsetXFraction", clean.offsetXFraction.toDouble())
            put("offsetYDp", clean.offsetYDp)
            put("manualPosXFraction", clean.manualPosXFraction.toDouble())
            put("manualPosYFraction", clean.manualPosYFraction.toDouble())
            put("normalizedX", clean.manualPosXFraction.toDouble())
            put("normalizedY", clean.manualPosYFraction.toDouble())
            put("positionLocked", clean.positionLocked)
            put("overlayScale", clean.overlayScale.toDouble())
            put("overlayAlpha", clean.overlayAlpha.toDouble())
            put("lyricsFontSizeSp", clean.lyricsFontSizeSp.toDouble())
            put("globalOffsetMs", clean.globalOffsetMs)
            put("autoShowOnMusicStart", clean.autoShowOnMusicStart)
            put("autoHideOnMusicStop", clean.autoHideOnMusicStop)
            put("autoHideTimeoutSeconds", clean.autoHideTimeoutSeconds)
            put("restoreCompactOnNewTrack", clean.restoreCompactOnNewTrack)
            put("showAlbumArtwork", clean.showAlbumArtwork)
            put("showPlaybackControls", clean.showPlaybackControls)
            put("decorativeVisualizerEnabled", clean.decorativeVisualizerEnabled)
            put("musicSourceMode", clean.musicSourceMode.name)
            if (clean.preferredAppPackage != null) {
                put("preferredAppPackage", clean.preferredAppPackage)
            }
            put("lyricsProvider", clean.lyricsProvider.name)
        }.toString(2)
    }

    @JvmStatic
    fun fromJson(json: String): LiveLyricsConfig {
        val root = JSONObject(json)
        val defaults = LiveLyricsConfig.defaults()
        val legacyDefaultMode = parseEnum(root.optString("defaultMode"), defaults.defaultMode)
        val legacyMetadataEnabled = root.optBoolean("metadataEnabled", defaults.metadataEnabled)
        val legacyArtwork = root.optBoolean("showAlbumArtwork", defaults.showAlbumArtwork)
        val legacyControls = root.optBoolean("showPlaybackControls", defaults.showPlaybackControls)
        val inferredDisplayMode = when {
            root.has("displayMode") -> parseEnum(root.optString("displayMode"), defaults.displayMode)
            legacyDefaultMode == IslandDisplayMode.EXPANDED -> LiveLyricsDisplayMode.FULL_MUSIC
            !legacyMetadataEnabled && !legacyArtwork && !legacyControls -> LiveLyricsDisplayMode.LYRICS_ONLY
            else -> LiveLyricsDisplayMode.COMPACT_MUSIC
        }
        val parsedX = when {
            root.has("manualPosXFraction") -> root.optDouble("manualPosXFraction", defaults.manualPosXFraction.toDouble()).toFloat()
            root.has("normalizedX") -> root.optDouble("normalizedX", defaults.manualPosXFraction.toDouble()).toFloat()
            else -> defaults.manualPosXFraction
        }
        val parsedY = when {
            root.has("manualPosYFraction") -> root.optDouble("manualPosYFraction", defaults.manualPosYFraction.toDouble()).toFloat()
            root.has("normalizedY") -> root.optDouble("normalizedY", defaults.manualPosYFraction.toDouble()).toFloat()
            else -> defaults.manualPosYFraction
        }
        return LiveLyricsConfig(
            featureEnabled = root.optBoolean("featureEnabled", defaults.featureEnabled),
            overlayVisible = root.optBoolean("overlayVisible", defaults.overlayVisible),
            displayMode = inferredDisplayMode,
            lyricsEnabled = root.optBoolean("lyricsEnabled", defaults.lyricsEnabled),
            metadataEnabled = legacyMetadataEnabled,
            showMusicalNote = root.optBoolean("showMusicalNote", defaults.showMusicalNote),
            animationsEnabled = root.optBoolean("animationsEnabled", defaults.animationsEnabled),
            animationQuality = parseEnum(root.optString("animationQuality"), defaults.animationQuality),
            animationIntensity = parseEnum(root.optString("animationIntensity"), defaults.animationIntensity),
            animationSpeed = root.optDouble("animationSpeed", defaults.animationSpeed.toDouble()).toFloat(),
            noteAnimationSpeed = root.optDouble("noteAnimationSpeed", defaults.noteAnimationSpeed.toDouble()).toFloat(),
            defaultMode = legacyDefaultMode,
            autoHudSafePositioning = root.optBoolean("autoHudSafePositioning", defaults.autoHudSafePositioning),
            positionAnchor = parseEnum(root.optString("positionAnchor"), defaults.positionAnchor),
            offsetXFraction = root.optDouble("offsetXFraction", defaults.offsetXFraction.toDouble()).toFloat(),
            offsetYDp = root.optInt("offsetYDp", defaults.offsetYDp),
            manualPosXFraction = parsedX,
            manualPosYFraction = parsedY,
            positionLocked = root.optBoolean("positionLocked", defaults.positionLocked),
            positionConfigVersion = root.optInt("positionConfigVersion", defaults.positionConfigVersion),
            overlayScale = root.optDouble("overlayScale", defaults.overlayScale.toDouble()).toFloat(),
            overlayAlpha = root.optDouble("overlayAlpha", defaults.overlayAlpha.toDouble()).toFloat(),
            lyricsFontSizeSp = root.optDouble("lyricsFontSizeSp", defaults.lyricsFontSizeSp.toDouble()).toFloat(),
            globalOffsetMs = root.optLong("globalOffsetMs", defaults.globalOffsetMs),
            autoShowOnMusicStart = root.optBoolean("autoShowOnMusicStart", defaults.autoShowOnMusicStart),
            autoHideOnMusicStop = root.optBoolean("autoHideOnMusicStop", defaults.autoHideOnMusicStop),
            autoHideTimeoutSeconds = root.optInt("autoHideTimeoutSeconds", defaults.autoHideTimeoutSeconds),
            restoreCompactOnNewTrack = root.optBoolean("restoreCompactOnNewTrack", defaults.restoreCompactOnNewTrack),
            showAlbumArtwork = legacyArtwork,
            showPlaybackControls = legacyControls,
            decorativeVisualizerEnabled = root.optBoolean("decorativeVisualizerEnabled", defaults.decorativeVisualizerEnabled),
            musicSourceMode = parseEnum(root.optString("musicSourceMode"), defaults.musicSourceMode),
            preferredAppPackage = root.optString("preferredAppPackage", "").takeIf { it.isNotBlank() },
            lyricsProvider = parseEnum(root.optString("lyricsProvider"), defaults.lyricsProvider)
        ).sanitized()
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    private fun configFile(context: Context): File =
        File(context.applicationContext.filesDir, CONFIG_FILE_NAME)

    private fun writeJsonAtomic(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${target.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists()) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write Live Lyrics config", e)
            tmp.delete()
        }
    }
}
