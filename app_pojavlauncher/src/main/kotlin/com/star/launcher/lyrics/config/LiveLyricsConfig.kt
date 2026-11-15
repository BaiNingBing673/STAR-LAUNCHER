package com.star.launcher.lyrics.config

/**
 * Display state of the in-game Dynamic Island overlay.
 */
enum class IslandDisplayMode {
    COMPACT,
    EXPANDED,
    HIDDEN
}

/**
 * Anchor alignment for the floating pill inside Minecraft's safe bounds.
 */
enum class IslandPosition {
    TOP_CENTER,
    TOP_LEFT,
    TOP_RIGHT
}

/**
 * Preferred music source selection.
 */
enum class MusicSourceMode {
    AUTO,
    EXTERNAL_ONLY,
    STAR_PLAYER_ONLY
}

/**
 * Lyrics lookup provider mode.
 */
enum class LyricsProviderType {
    LRCLIB,
    LOCAL_ONLY
}

/**
 * High-level presentation mode for STAR Live Lyrics.
 * - [FULL_MUSIC]: Expanded music card with artwork, track/artist, prev/curr/next lyrics, progress & controls.
 * - [COMPACT_MUSIC]: Sleek Dynamic Island pill with mini visualizer, track title, active lyric & quick tap-to-expand.
 * - [LYRICS_ONLY]: Minimal gameplay mode displaying ONLY the synchronized lyric + moving musical note.
 */
enum class LiveLyricsDisplayMode {
    FULL_MUSIC,
    COMPACT_MUSIC,
    LYRICS_ONLY
}

/**
 * Motion and render quality tiers to balance polish and battery/thermal budget.
 */
enum class AnimationQuality {
    HIGH,
    BALANCED,
    BATTERY_SAVER
}

/**
 * Visual animation intensity for lyric transitions and moving musical note motion.
 */
enum class AnimationIntensity {
    SUBTLE,
    NORMAL,
    DYNAMIC
}

/**
 * Persistent global configuration for STAR Live Lyrics.
 * Shared atomically between `:launcher` and `:game` via [LiveLyricsConfigStore].
 */
data class LiveLyricsConfig(
    val featureEnabled: Boolean = false,
    val overlayVisible: Boolean = true,
    val displayMode: LiveLyricsDisplayMode = LiveLyricsDisplayMode.COMPACT_MUSIC,
    val lyricsEnabled: Boolean = true,
    val metadataEnabled: Boolean = true,
    val showMusicalNote: Boolean = true,
    val animationsEnabled: Boolean = true,
    val animationQuality: AnimationQuality = AnimationQuality.HIGH,
    val animationIntensity: AnimationIntensity = AnimationIntensity.NORMAL,
    val animationSpeed: Float = 1.0f,
    val noteAnimationSpeed: Float = 1.0f,
    val defaultMode: IslandDisplayMode = IslandDisplayMode.COMPACT,
    val autoHudSafePositioning: Boolean = true,
    val positionAnchor: IslandPosition = IslandPosition.TOP_CENTER,
    val offsetXFraction: Float = 0f,
    val offsetYDp: Int = 26,
    val manualPosXFraction: Float = DEFAULT_POS_X_FRACTION,
    val manualPosYFraction: Float = DEFAULT_POS_Y_FRACTION,
    val positionLocked: Boolean = false,
    val positionConfigVersion: Int = POSITION_CONFIG_VERSION,
    val overlayScale: Float = 1.0f,
    val overlayAlpha: Float = 0.92f,
    val lyricsFontSizeSp: Float = 13.5f,
    val globalOffsetMs: Long = 0L,
    val autoShowOnMusicStart: Boolean = true,
    val autoHideOnMusicStop: Boolean = true,
    val autoHideTimeoutSeconds: Int = 0,
    val restoreCompactOnNewTrack: Boolean = true,
    val showAlbumArtwork: Boolean = true,
    val showPlaybackControls: Boolean = true,
    val decorativeVisualizerEnabled: Boolean = true,
    val musicSourceMode: MusicSourceMode = MusicSourceMode.AUTO,
    val preferredAppPackage: String? = null,
    val lyricsProvider: LyricsProviderType = LyricsProviderType.LRCLIB
) {
    val normalizedX: Float
        get() = manualPosXFraction

    val normalizedY: Float
        get() = manualPosYFraction

    fun isLyricsOnly(): Boolean = displayMode == LiveLyricsDisplayMode.LYRICS_ONLY

    fun isCustomPosition(): Boolean = !autoHudSafePositioning

    fun withManualPosition(normX: Float, normY: Float): LiveLyricsConfig = copy(
        autoHudSafePositioning = false,
        manualPosXFraction = normX.coerceIn(0.0f, 1.0f),
        manualPosYFraction = normY.coerceIn(0.0f, 1.0f),
        positionConfigVersion = POSITION_CONFIG_VERSION
    ).sanitized()

    fun withDefaultPosition(): LiveLyricsConfig {
        val def = defaults()
        return copy(
            autoHudSafePositioning = def.autoHudSafePositioning,
            positionAnchor = def.positionAnchor,
            offsetXFraction = def.offsetXFraction,
            offsetYDp = def.offsetYDp,
            manualPosXFraction = def.manualPosXFraction,
            manualPosYFraction = def.manualPosYFraction,
            positionConfigVersion = POSITION_CONFIG_VERSION
        ).sanitized()
    }

    fun withPositionLocked(locked: Boolean): LiveLyricsConfig = copy(
        positionLocked = locked
    )

    fun resolvedIslandMode(): IslandDisplayMode = when (displayMode) {
        LiveLyricsDisplayMode.FULL_MUSIC -> IslandDisplayMode.EXPANDED
        LiveLyricsDisplayMode.COMPACT_MUSIC ->
            if (defaultMode == IslandDisplayMode.EXPANDED) IslandDisplayMode.EXPANDED else IslandDisplayMode.COMPACT
        LiveLyricsDisplayMode.LYRICS_ONLY -> IslandDisplayMode.COMPACT
    }

    fun effectiveAnimationScale(): Float {
        if (!animationsEnabled || animationQuality == AnimationQuality.BATTERY_SAVER) return 0f
        return when (animationIntensity) {
            AnimationIntensity.SUBTLE -> 0.5f
            AnimationIntensity.NORMAL -> 1.0f
            AnimationIntensity.DYNAMIC -> 1.35f
        }
    }

    fun sanitized(): LiveLyricsConfig = copy(
        defaultMode = when (displayMode) {
            LiveLyricsDisplayMode.FULL_MUSIC -> IslandDisplayMode.EXPANDED
            LiveLyricsDisplayMode.LYRICS_ONLY -> IslandDisplayMode.COMPACT
            LiveLyricsDisplayMode.COMPACT_MUSIC ->
                if (defaultMode == IslandDisplayMode.HIDDEN) IslandDisplayMode.COMPACT else defaultMode
        },
        offsetXFraction = offsetXFraction.coerceIn(-0.40f, 0.40f),
        offsetYDp = offsetYDp.coerceIn(0, 140),
        manualPosXFraction = manualPosXFraction.coerceIn(0.0f, 1.0f),
        manualPosYFraction = manualPosYFraction.coerceIn(0.0f, 1.0f),
        positionConfigVersion = positionConfigVersion.coerceAtLeast(1),
        overlayScale = overlayScale.coerceIn(0.75f, 1.35f),
        overlayAlpha = overlayAlpha.coerceIn(0.45f, 1.0f),
        animationSpeed = animationSpeed.coerceIn(0.5f, 2.0f),
        noteAnimationSpeed = noteAnimationSpeed.coerceIn(0.4f, 2.2f),
        lyricsFontSizeSp = lyricsFontSizeSp.coerceIn(10.5f, 22.0f),
        globalOffsetMs = globalOffsetMs.coerceIn(-10_000L, 10_000L),
        autoHideTimeoutSeconds = autoHideTimeoutSeconds.coerceIn(0, 300),
        preferredAppPackage = preferredAppPackage?.trim()?.takeIf { it.isNotEmpty() }
    )

    companion object {
        const val CONFIG_VERSION = 3
        const val POSITION_CONFIG_VERSION = 1
        const val DEFAULT_POS_X_FRACTION = 0.5f
        const val DEFAULT_POS_Y_FRACTION = 0.08f
        const val RESET_POSITION_CONFIRMATION_MESSAGE = "Position restored to default."

        @JvmStatic
        fun defaults(): LiveLyricsConfig = LiveLyricsConfig()
    }
}

/**
 * Ephemeral in-game session visibility state, intentionally separated from [LiveLyricsConfig]
 * so temporarily hiding or disabling during a single Minecraft session does not overwrite
 * the user's global preferences.
 */
data class LiveLyricsSessionState(
    val sessionDisabled: Boolean = false,
    val temporarilyHidden: Boolean = false,
    val hudBlockedHidden: Boolean = false,
    val activeMode: IslandDisplayMode = IslandDisplayMode.COMPACT,
    val activeDisplayMode: LiveLyricsDisplayMode? = null
) {
    fun effectiveDisplayMode(config: LiveLyricsConfig): LiveLyricsDisplayMode =
        activeDisplayMode ?: config.displayMode

    fun effectiveMode(config: LiveLyricsConfig): IslandDisplayMode {
        if (!config.featureEnabled || !config.overlayVisible || sessionDisabled || temporarilyHidden || hudBlockedHidden) {
            return IslandDisplayMode.HIDDEN
        }
        return when (effectiveDisplayMode(config)) {
            LiveLyricsDisplayMode.LYRICS_ONLY -> IslandDisplayMode.COMPACT
            LiveLyricsDisplayMode.FULL_MUSIC ->
                if (activeMode == IslandDisplayMode.HIDDEN) IslandDisplayMode.EXPANDED else activeMode
            LiveLyricsDisplayMode.COMPACT_MUSIC ->
                if (activeMode == IslandDisplayMode.HIDDEN) config.resolvedIslandMode() else activeMode
        }
    }
}
