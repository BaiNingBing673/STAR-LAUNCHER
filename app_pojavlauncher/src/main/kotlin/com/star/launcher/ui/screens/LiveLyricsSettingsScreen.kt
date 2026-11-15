package com.star.launcher.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.star.launcher.hud.StarHudConfigStore
import com.star.launcher.lyrics.config.AnimationIntensity
import com.star.launcher.lyrics.config.AnimationQuality
import com.star.launcher.lyrics.config.IslandDisplayMode
import com.star.launcher.lyrics.config.IslandPosition
import com.star.launcher.lyrics.config.LiveLyricsConfig
import com.star.launcher.lyrics.config.LiveLyricsConfigStore
import com.star.launcher.lyrics.config.LiveLyricsDisplayMode
import com.star.launcher.lyrics.config.LyricsProviderType
import com.star.launcher.lyrics.config.MusicSourceMode
import com.star.launcher.lyrics.engine.LrcParser
import com.star.launcher.lyrics.engine.LyricsTrackKey
import com.star.launcher.lyrics.media.StarMediaNotificationListener
import com.star.launcher.lyrics.media.UnifiedMediaSourceCoordinator
import com.star.launcher.lyrics.overlay.HudPlacementRequest
import com.star.launcher.lyrics.overlay.LiveLyricsHudLayoutSolver
import com.star.launcher.lyrics.player.StarLocalTrack
import com.star.launcher.lyrics.player.StarPlaylistStore
import com.star.launcher.lyrics.provider.LrcLibClient
import com.star.launcher.lyrics.provider.LyricsRepository
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveLyricsSettingsScreen(
    onBack: () -> Unit,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(LiveLyricsConfigStore.load(context)) }
    var hasNotificationAccess by remember {
        mutableStateOf(StarMediaNotificationListener.hasNotificationAccess(context))
    }
    var playlist by remember { mutableStateOf(StarPlaylistStore.loadPlaylist(context)) }
    val repository = remember(context) { LyricsRepository(context) }
    var cacheCounts by remember { mutableStateOf(repository.cacheStats()) }

    val coordinator = remember(context) { UnifiedMediaSourceCoordinator(context) }
    val mediaState by coordinator.state.collectAsState()
    val starSnapshot by coordinator.starPlayerClient.snapshot.collectAsState()

    var previewExpanded by remember { mutableStateOf(config.defaultMode == IslandDisplayMode.EXPANDED) }
    var showManualSyncDialog by remember { mutableStateOf(false) }

    fun saveConfig(updated: LiveLyricsConfig) {
        val clean = LiveLyricsConfigStore.save(context, updated)
        config = clean
        coordinator.setActive(clean.featureEnabled, clean.musicSourceMode, clean.preferredAppPackage)
    }

    LaunchedEffect(config.featureEnabled, config.musicSourceMode, config.preferredAppPackage) {
        coordinator.setActive(config.featureEnabled, config.musicSourceMode, config.preferredAppPackage)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = StarMediaNotificationListener.hasNotificationAccess(context)
                config = LiveLyricsConfigStore.load(context)
                playlist = StarPlaylistStore.loadPlaylist(context)
                cacheCounts = repository.cacheStats()
                coordinator.setActive(config.featureEnabled, config.musicSourceMode, config.preferredAppPackage)
                coordinator.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            coordinator.releaseResources()
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                playlist = StarPlaylistStore.addTracksFromUris(context, uris)
                coordinator.starPlayerClient.notifyPlaylistChanged()
                onStatusMessage("Added ${uris.size} track(s) to STAR Music Player")
            }
        }
    }

    val lrcImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val track = mediaState.track
                val title = track.title.ifBlank { "Custom Track" }
                val artist = track.artist.ifBlank { "Unknown Artist" }
                val trackKey = LyricsTrackKey.build(title, artist, track.durationMs)
                val res = repository.importCustomLyricsFromUri(
                    uri = uri,
                    trackKey = trackKey,
                    title = title,
                    artist = artist,
                    album = track.album,
                    durationMs = track.durationMs
                )
                res.onSuccess { doc ->
                    cacheCounts = repository.cacheStats()
                    onStatusMessage("Imported ${doc.format.name.lowercase()} lyrics for $title")
                }.onFailure { err ->
                    onStatusMessage("Import failed: ${err.message ?: "unknown error"}")
                }
            }
        }
    }

    if (showManualSyncDialog) {
        ManualLyricsSyncDialog(
            initialTitle = mediaState.track.title,
            initialArtist = mediaState.track.artist,
            initialDurationMs = mediaState.track.durationMs,
            currentPlaybackPositionMs = { mediaState.playback.currentPositionMs() },
            onDismiss = { showManualSyncDialog = false },
            onSave = { title, artist, durationMs, rawLrc ->
                scope.launch {
                    val key = LyricsTrackKey.build(title, artist, durationMs)
                    repository.saveCustomLyrics(
                        rawText = rawLrc,
                        trackKey = key,
                        title = title,
                        artist = artist,
                        durationMs = durationMs
                    )
                    cacheCounts = repository.cacheStats()
                    showManualSyncDialog = false
                    onStatusMessage("Saved custom lyrics locally for $title")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSectionHeader(
                title = "STAR Live Lyrics",
                subtitle = "In-game Dynamic Island lyrics & music overlay"
            ) {
                AetherOutlinedButton(text = "Back", onClick = onBack)
            }
        }

        // 1. Master Toggle & Live Preview
        item {
            AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable STAR Live Lyrics",
                            style = MaterialTheme.typography.titleLarge,
                            color = AetherColors.OnBackgroundDark
                        )
                        Text(
                            text = "Master switch. When OFF, media polling, sync loops, and overlay rendering stop completely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                    Switch(
                        checked = config.featureEnabled,
                        onCheckedChange = { enabled ->
                            saveConfig(config.copy(featureEnabled = enabled))
                            onStatusMessage(if (enabled) "STAR Live Lyrics enabled" else "STAR Live Lyrics disabled")
                        }
                    )
                }

                DynamicIslandPreviewCard(
                    config = config,
                    trackTitle = mediaState.track.title.ifBlank { "Resonance (Minecraft Mix)" },
                    trackArtist = mediaState.track.artist.ifBlank { "HOME · Spotify" },
                    onManualDragUpdate = { nx, ny ->
                        config = config.copy(
                            autoHudSafePositioning = false,
                            manualPosXFraction = nx,
                            manualPosYFraction = ny
                        )
                    },
                    onManualDragEnd = { saveConfig(config) },
                    onEnableAutoSafe = {
                        saveConfig(config.copy(autoHudSafePositioning = true))
                    }
                )
            }
        }

        // 2. Music Source & Notification Listener Permission
        item {
            AetherSurfaceCard {
                Text(
                    text = "Music Source & Permissions",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    MusicSourceMode.values().forEach { mode ->
                        val label = when (mode) {
                            MusicSourceMode.AUTO -> "Auto (Any Active)"
                            MusicSourceMode.EXTERNAL_ONLY -> "External Apps Only"
                            MusicSourceMode.STAR_PLAYER_ONLY -> "STAR Player Only"
                        }
                        FilterChip(
                            selected = config.musicSourceMode == mode,
                            onClick = { saveConfig(config.copy(musicSourceMode = mode)) },
                            label = { Text(label) }
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = AetherColors.SurfaceDark,
                    border = BorderStroke(
                        1.dp,
                        if (hasNotificationAccess) AetherColors.TierVerifiedFg.copy(alpha = 0.4f)
                        else AetherColors.TierTestedFg.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        Text(
                            text = if (hasNotificationAccess) {
                                "Notification Access: Granted (External MediaSession active)"
                            } else {
                                "Notification Access: Not Granted (Required for Spotify / YT Music / Apple Music)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (hasNotificationAccess) AetherColors.TierVerifiedFg else AetherColors.TierTestedFg
                        )
                        Text(
                            text = "Privacy guarantee: STAR only uses Android's MediaSessionManager to read active song metadata & playback position. Unrelated notifications are never read, logged, or stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.OnSurfaceVariantDark
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                            AetherPrimaryButton(
                                text = if (hasNotificationAccess) "Manage Access" else "Grant Notification Access",
                                onClick = {
                                    runCatching {
                                        context.startActivity(StarMediaNotificationListener.createSettingsIntent())
                                    }.onFailure {
                                        Toast.makeText(context, "Open Android Settings → Notification Access", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                            AetherOutlinedButton(
                                text = "Refresh",
                                onClick = {
                                    hasNotificationAccess = StarMediaNotificationListener.hasNotificationAccess(context)
                                    coordinator.refresh()
                                }
                            )
                        }
                    }
                }

                // Preferred app selector from detected active media sessions
                Text(
                    text = "Preferred Music App",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    FilterChip(
                        selected = config.preferredAppPackage == null,
                        onClick = { saveConfig(config.copy(preferredAppPackage = null)) },
                        label = { Text("Automatic") }
                    )
                    mediaState.activeApps.forEach { app ->
                        FilterChip(
                            selected = config.preferredAppPackage == app.packageName,
                            onClick = { saveConfig(config.copy(preferredAppPackage = app.packageName)) },
                            label = {
                                Text(if (app.isPlaying) "${app.appLabel} (Playing)" else app.appLabel)
                            }
                        )
                    }
                    if (config.preferredAppPackage != null &&
                        mediaState.activeApps.none { it.packageName == config.preferredAppPackage }
                    ) {
                        FilterChip(
                            selected = true,
                            onClick = { saveConfig(config.copy(preferredAppPackage = null)) },
                            label = { Text("${config.preferredAppPackage} (Saved)") }
                        )
                    }
                }
            }
        }

        // 3. Optional STAR Music Player
        item {
            AetherSurfaceCard {
                Text(
                    text = "STAR Music Player (Optional)",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Play local audio files in the background with MediaSession & notification controls—no external app or Notification Access required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Add Audio Files",
                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    if (playlist.isNotEmpty()) {
                        AetherOutlinedButton(
                            text = "Clear Playlist",
                            onClick = {
                                StarPlaylistStore.clearPlaylist(context)
                                playlist = emptyList()
                                coordinator.starPlayerClient.stop()
                            }
                        )
                    }
                }

                if (starSnapshot.isServiceActive && starSnapshot.currentTrack != null) {
                    val cur = starSnapshot.currentTrack!!
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = AetherColors.SurfaceElevatedDark,
                        border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                        ) {
                            Text(
                                text = "Now Playing: ${cur.title}",
                                style = MaterialTheme.typography.titleMedium,
                                color = AetherColors.OnSurfaceDark
                            )
                            Text(
                                text = cur.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherColors.OnSurfaceVariantDark
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                                AetherOutlinedButton(text = "Prev", onClick = { coordinator.starPlayerClient.previous() })
                                AetherPrimaryButton(
                                    text = if (starSnapshot.isPlaying) "Pause" else "Play",
                                    onClick = { coordinator.starPlayerClient.togglePlayPause() }
                                )
                                AetherOutlinedButton(text = "Next", onClick = { coordinator.starPlayerClient.next() })
                                AetherOutlinedButton(text = "Stop", onClick = { coordinator.starPlayerClient.stop() })
                            }
                        }
                    }
                }

                if (playlist.isEmpty()) {
                    Text(
                        text = "Playlist is empty. Add MP3/FLAC/OGG/M4A files or continue using Spotify / YouTube Music / any Android player.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                } else {
                    playlist.forEachIndexed { idx, track ->
                        StarPlaylistRow(
                            track = track,
                            isCurrent = starSnapshot.isServiceActive && starSnapshot.currentTrack?.id == track.id,
                            onPlay = { coordinator.starPlayerClient.playIndex(idx) },
                            onRemove = {
                                playlist = StarPlaylistStore.removeTrack(context, track.id)
                                coordinator.starPlayerClient.notifyPlaylistChanged()
                            }
                        )
                    }
                }
            }
        }

        // 4. Independent Visibility, Behavior & Auto Mode
        item {
            AetherSurfaceCard {
                Text(
                    text = "Visibility & Auto Mode",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                SettingsToggleRow(
                    title = "Overlay Visible",
                    subtitle = "Default visibility when entering Minecraft",
                    checked = config.overlayVisible,
                    onCheckedChange = { saveConfig(config.copy(overlayVisible = it)) }
                )
                SettingsToggleRow(
                    title = "Synchronized Lyrics",
                    subtitle = "Show live lyrics lines (disable to keep a clean music info pill)",
                    checked = config.lyricsEnabled,
                    onCheckedChange = { saveConfig(config.copy(lyricsEnabled = it)) }
                )
                SettingsToggleRow(
                    title = "Music Metadata",
                    subtitle = "Display track title and artist",
                    checked = config.metadataEnabled,
                    onCheckedChange = { saveConfig(config.copy(metadataEnabled = it)) }
                )
                SettingsToggleRow(
                    title = "Auto-Show on Music Start",
                    subtitle = "Reveal the Dynamic Island when playback begins",
                    checked = config.autoShowOnMusicStart,
                    onCheckedChange = { saveConfig(config.copy(autoShowOnMusicStart = it)) }
                )
                SettingsToggleRow(
                    title = "Auto-Hide on Music Stop",
                    subtitle = "Hide overlay when playback session ends",
                    checked = config.autoHideOnMusicStop,
                    onCheckedChange = { saveConfig(config.copy(autoHideOnMusicStop = it)) }
                )
                SettingsToggleRow(
                    title = "Restore Compact on New Track",
                    subtitle = "Collapse expanded panel to compact pill when song changes",
                    checked = config.restoreCompactOnNewTrack,
                    onCheckedChange = { saveConfig(config.copy(restoreCompactOnNewTrack = it)) }
                )

                Text(
                    text = if (config.autoHideTimeoutSeconds == 0) {
                        "Inactivity Auto-Hide: Off"
                    } else {
                        "Inactivity Auto-Hide: ${config.autoHideTimeoutSeconds}s when paused"
                    },
                    style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                )
                Slider(
                    value = config.autoHideTimeoutSeconds.toFloat(),
                    onValueChange = { config = config.copy(autoHideTimeoutSeconds = it.roundToInt()) },
                    onValueChangeFinished = { saveConfig(config) },
                    valueRange = 0f..120f
                )
            }
        }

        // 5. Display Mode, HUD-Safe Positioning & Motion
        item {
            AetherSurfaceCard {
                Text(
                    text = "Display Mode & HUD Positioning",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text("Display Mode", style = MaterialTheme.typography.titleMedium, color = AetherColors.OnSurfaceDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    listOf(
                        LiveLyricsDisplayMode.FULL_MUSIC to "Full Music Display",
                        LiveLyricsDisplayMode.COMPACT_MUSIC to "Compact Music Display",
                        LiveLyricsDisplayMode.LYRICS_ONLY to "Lyrics Only"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = config.displayMode == mode,
                            onClick = {
                                previewExpanded = mode == LiveLyricsDisplayMode.FULL_MUSIC
                                saveConfig(config.copy(displayMode = mode))
                            },
                            label = { Text(label) }
                        )
                    }
                }

                SettingsToggleRow(
                    title = "Lock Position (${if (config.positionLocked) "🔒 Locked" else "🔓 Unlocked"})",
                    subtitle = if (config.positionLocked) {
                        "Position locked — dragging and accidental touches are ignored while lyrics remain active"
                    } else {
                        "Position unlocked — drag STAR Lyrics anywhere on the Minecraft screen"
                    },
                    checked = config.positionLocked,
                    onCheckedChange = { locked ->
                        saveConfig(config.withPositionLocked(locked))
                        onStatusMessage(if (locked) "STAR Lyrics position locked" else "STAR Lyrics position unlocked")
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AetherOutlinedButton(
                        text = "Reset Position",
                        onClick = {
                            saveConfig(config.withDefaultPosition())
                            onStatusMessage(LiveLyricsConfig.RESET_POSITION_CONFIRMATION_MESSAGE)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = if (config.positionLocked) "Unlock Position" else "Lock Position",
                        onClick = {
                            val nextLocked = !config.positionLocked
                            saveConfig(config.withPositionLocked(nextLocked))
                            onStatusMessage(if (nextLocked) "STAR Lyrics position locked" else "STAR Lyrics position unlocked")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                SettingsToggleRow(
                    title = "Automatic HUD-Safe Positioning",
                    subtitle = "Detects hotbar, vitals, touch controls & cutouts and auto-avoids overlap",
                    checked = config.autoHudSafePositioning,
                    onCheckedChange = { saveConfig(config.copy(autoHudSafePositioning = it)) }
                )

                if (config.autoHudSafePositioning) {
                    Text("Preferred Anchor", style = MaterialTheme.typography.titleMedium, color = AetherColors.OnSurfaceDark)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                        listOf(
                            IslandPosition.TOP_LEFT to "Top Left",
                            IslandPosition.TOP_CENTER to "Top Center",
                            IslandPosition.TOP_RIGHT to "Top Right"
                        ).forEach { (pos, label) ->
                            FilterChip(
                                selected = config.positionAnchor == pos,
                                onClick = { saveConfig(config.copy(positionAnchor = pos)) },
                                label = { Text(label) }
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (config.positionLocked) {
                            "Manual Positioning saved (🔒 Locked — unlock position to drag or adjust):"
                        } else {
                            "Manual Positioning active — drag the pill directly in the HUD preview above, or fine-tune below:"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.AccentCyan
                    )
                    SliderSettingRow(
                        label = "Manual Horizontal Position: ${(config.manualPosXFraction * 100).roundToInt()}%",
                        value = config.manualPosXFraction,
                        range = 0.0f..1.0f,
                        onChange = {
                            if (!config.positionLocked) {
                                config = config.copy(manualPosXFraction = it)
                            }
                        },
                        onCommit = {
                            if (!config.positionLocked) {
                                saveConfig(config)
                            }
                        }
                    )
                    SliderSettingRow(
                        label = "Manual Vertical Position: ${(config.manualPosYFraction * 100).roundToInt()}%",
                        value = config.manualPosYFraction,
                        range = 0.0f..1.0f,
                        onChange = {
                            if (!config.positionLocked) {
                                config = config.copy(manualPosYFraction = it)
                            }
                        },
                        onCommit = {
                            if (!config.positionLocked) {
                                saveConfig(config)
                            }
                        }
                    )
                }

                SettingsToggleRow(
                    title = "Show Musical Note (♪)",
                    subtitle = "Gliding musical note indicator synchronized with current lyric progression",
                    checked = config.showMusicalNote,
                    onCheckedChange = { saveConfig(config.copy(showMusicalNote = it)) }
                )
                SettingsToggleRow(
                    title = "Show Album Artwork",
                    subtitle = "Displays artwork & ambient tint in Full / Compact modes (hidden in Lyrics Only)",
                    checked = config.showAlbumArtwork,
                    onCheckedChange = { saveConfig(config.copy(showAlbumArtwork = it)) }
                )
                SettingsToggleRow(
                    title = "Show Playback Controls",
                    subtitle = "Play/Pause, Previous & Next inside Full Music Display",
                    checked = config.showPlaybackControls,
                    onCheckedChange = { saveConfig(config.copy(showPlaybackControls = it)) }
                )
                SettingsToggleRow(
                    title = "Fluid Animations",
                    subtitle = "Smooth transitions & moving note progression",
                    checked = config.animationsEnabled,
                    onCheckedChange = { saveConfig(config.copy(animationsEnabled = it)) }
                )
                SettingsToggleRow(
                    title = "Decorative Waveform Visualizer",
                    subtitle = "Lightweight 4-bar playback animation in Compact Music Display",
                    checked = config.decorativeVisualizerEnabled,
                    onCheckedChange = { saveConfig(config.copy(decorativeVisualizerEnabled = it)) }
                )

                Text("Animation Intensity", style = MaterialTheme.typography.titleMedium, color = AetherColors.OnSurfaceDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    listOf(
                        AnimationIntensity.SUBTLE to "Subtle",
                        AnimationIntensity.NORMAL to "Normal",
                        AnimationIntensity.DYNAMIC to "Dynamic"
                    ).forEach { (level, label) ->
                        FilterChip(
                            selected = config.animationIntensity == level,
                            onClick = { saveConfig(config.copy(animationIntensity = level)) },
                            label = { Text(label) }
                        )
                    }
                }

                Text("Performance Quality Tier", style = MaterialTheme.typography.titleMedium, color = AetherColors.OnSurfaceDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    listOf(
                        AnimationQuality.HIGH to "High",
                        AnimationQuality.BALANCED to "Balanced",
                        AnimationQuality.BATTERY_SAVER to "Battery Saver"
                    ).forEach { (q, label) ->
                        FilterChip(
                            selected = config.animationQuality == q,
                            onClick = { saveConfig(config.copy(animationQuality = q)) },
                            label = { Text(label) }
                        )
                    }
                }

                SliderSettingRow(
                    label = "Overlay Scale: ${String.format("%.2fx", config.overlayScale)}",
                    value = config.overlayScale,
                    range = 0.75f..1.35f,
                    onChange = { config = config.copy(overlayScale = it) },
                    onCommit = { saveConfig(config) }
                )
                SliderSettingRow(
                    label = "Lyrics Opacity: ${(config.overlayAlpha * 100).roundToInt()}%",
                    value = config.overlayAlpha,
                    range = 0.45f..1.0f,
                    onChange = { config = config.copy(overlayAlpha = it) },
                    onCommit = { saveConfig(config) }
                )
                SliderSettingRow(
                    label = "Lyrics Font Size: ${String.format("%.1f sp", config.lyricsFontSizeSp)}",
                    value = config.lyricsFontSizeSp,
                    range = 10.5f..22.0f,
                    onChange = { config = config.copy(lyricsFontSizeSp = it) },
                    onCommit = { saveConfig(config) }
                )
                SliderSettingRow(
                    label = "Note Animation Speed: ${String.format("%.2fx", config.noteAnimationSpeed)}",
                    value = config.noteAnimationSpeed,
                    range = 0.4f..2.2f,
                    onChange = { config = config.copy(noteAnimationSpeed = it) },
                    onCommit = { saveConfig(config) }
                )
                SliderSettingRow(
                    label = "Transition Speed: ${String.format("%.2fx", config.animationSpeed)}",
                    value = config.animationSpeed,
                    range = 0.5f..2.0f,
                    onChange = { config = config.copy(animationSpeed = it) },
                    onCommit = { saveConfig(config) }
                )
                if (config.autoHudSafePositioning) {
                    SliderSettingRow(
                        label = "Preferred Vertical Offset: ${config.offsetYDp} dp",
                        value = config.offsetYDp.toFloat(),
                        range = 0f..140f,
                        onChange = { config = config.copy(offsetYDp = it.roundToInt()) },
                        onCommit = { saveConfig(config) }
                    )
                    SliderSettingRow(
                        label = "Preferred Horizontal Nudge: ${(config.offsetXFraction * 100).roundToInt()}%",
                        value = config.offsetXFraction,
                        range = -0.40f..0.40f,
                        onChange = { config = config.copy(offsetXFraction = it) },
                        onCommit = { saveConfig(config) }
                    )
                }
            }
        }

        // 6. Lyrics Provider, Timing Offset & Manual LRC Studio
        item {
            AetherSurfaceCard {
                Text(
                    text = "Lyrics Provider & Manual Sync",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    FilterChip(
                        selected = config.lyricsProvider == LyricsProviderType.LRCLIB,
                        onClick = { saveConfig(config.copy(lyricsProvider = LyricsProviderType.LRCLIB)) },
                        label = { Text("LRCLIB (lrclib.net)") }
                    )
                    FilterChip(
                        selected = config.lyricsProvider == LyricsProviderType.LOCAL_ONLY,
                        onClick = { saveConfig(config.copy(lyricsProvider = LyricsProviderType.LOCAL_ONLY)) },
                        label = { Text("Local / Custom Only") }
                    )
                }
                Text(
                    text = LrcLibClient.ATTRIBUTION_LRCLIB + " · Cached locally to minimize requests and respect rate limits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )

                SliderSettingRow(
                    label = "Global Timing Offset: ${if (config.globalOffsetMs >= 0) "+" else ""}${config.globalOffsetMs} ms",
                    value = config.globalOffsetMs.toFloat(),
                    range = -5000f..5000f,
                    onChange = { config = config.copy(globalOffsetMs = ((it / 50f).roundToInt() * 50L)) },
                    onCommit = { saveConfig(config) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "Import .LRC / .TXT",
                        onClick = { lrcImportLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "Manual Sync Editor",
                        onClick = { showManualSyncDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Local cache: ${cacheCounts.first} cached · ${cacheCounts.second} custom",
                        style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                    )
                    AetherOutlinedButton(
                        text = "Clear Cache",
                        onClick = {
                            scope.launch {
                                val removed = repository.clearRemoteCache()
                                cacheCounts = repository.cacheStats()
                                onStatusMessage("Cleared $removed cached lyrics file(s)")
                            }
                        }
                    )
                }

                AetherOutlinedButton(
                    text = "Reset Live Lyrics Settings to Default",
                    onClick = {
                        config = LiveLyricsConfigStore.resetToDefault(context)
                        onStatusMessage("Live Lyrics settings reset to default")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}

@Composable
private fun DynamicIslandPreviewCard(
    config: LiveLyricsConfig,
    trackTitle: String,
    trackArtist: String,
    onManualDragUpdate: (Float, Float) -> Unit,
    onManualDragEnd: () -> Unit,
    onEnableAutoSafe: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val starHudConfig = remember(context) { StarHudConfigStore.load(context) }

    val animTransition = rememberInfiniteTransition(label = "notePreview")
    val previewNoteProgress by animTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (2600f / config.noteAnimationSpeed.coerceIn(0.4f, 2.2f)).roundToInt(),
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "noteFraction"
    )
    val noteFrac = if (config.animationsEnabled && config.animationQuality != AnimationQuality.BATTERY_SAVER) {
        previewNoteProgress
    } else {
        0.38f
    }

    val modeTitle = when (config.displayMode) {
        LiveLyricsDisplayMode.FULL_MUSIC -> "Full Music Display"
        LiveLyricsDisplayMode.COMPACT_MUSIC -> "Compact Music Display"
        LiveLyricsDisplayMode.LYRICS_ONLY -> "Lyrics Only Mode"
    }

    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (config.positionLocked) {
                    "HUD-Safe Preview (🔒 Position Locked)"
                } else {
                    "HUD-Safe Preview (🔓 Unlocked — drag pill to position)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.OnSurfaceVariantDark
            )
            Text(
                text = modeTitle,
                style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A101D), Color(0xFF141E30))
                    )
                )
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(16.dp))
        ) {
            // Solve against a canonical 1920x1080 landscape reference frame then scale cleanly into preview box
            val refW = 1920f
            val refH = 1080f
            val refDensity = 3.0f
            val isFull = config.displayMode == LiveLyricsDisplayMode.FULL_MUSIC
            val isLyricsOnly = config.displayMode == LiveLyricsDisplayMode.LYRICS_ONLY
            val pillLogicalW = (when {
                isFull -> 364f
                isLyricsOnly -> 248f
                else -> 286f
            }) * config.overlayScale * refDensity
            val pillLogicalH = (if (isFull) 168f else 42f) * config.overlayScale * refDensity

            val placement = remember(config, starHudConfig) {
                LiveLyricsHudLayoutSolver.solve(
                    HudPlacementRequest(
                        containerWidthPx = refW,
                        containerHeightPx = refH,
                        density = refDensity,
                        safeLeftPx = 0f,
                        safeTopPx = 0f,
                        safeRightPx = 0f,
                        safeBottomPx = 0f,
                        desiredWidthPx = pillLogicalW,
                        desiredHeightPx = pillLogicalH,
                        config = config,
                        starHudConfig = starHudConfig,
                        includeVanillaBottomHud = true,
                        includeDrawerPull = true
                    )
                )
            }

            val boxWpx = with(density) { maxWidth.toPx() }
            val boxHpx = with(density) { maxHeight.toPx() }
            val scaleX = boxWpx / refW
            val scaleY = boxHpx / refH

            // Render HUD safe-zone boxes
            placement.allExclusionRects.forEach { zone ->
                val colliding = placement.collidingZones.any { it.zoneId == zone.id }
                val zoneColor = if (colliding) Color(0x45FF4D6D) else Color(0x1E7FA6FF)
                val strokeColor = if (colliding) Color(0xCCFF4D6D) else Color(0x487FA6FF)
                val zw = with(density) { (zone.width * scaleX).toDp() }
                val zh = with(density) { (zone.height * scaleY).toDp() }
                val zx = (zone.left * scaleX).roundToInt()
                val zy = (zone.top * scaleY).roundToInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(zx, zy) }
                        .size(zw, zh)
                        .clip(RoundedCornerShape(4.dp))
                        .background(zoneColor)
                        .border(1.dp, strokeColor, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = zone.label,
                        fontSize = 7.sp,
                        color = if (colliding) Color(0xFFFFB3C1) else Color(0x99CFE0FF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Draggable Live Lyrics Pill preview
            val pillPxW = placement.widthPx * scaleX
            val pillPxH = placement.heightPx * scaleY
            val pillDpW = with(density) { pillPxW.toDp() }
            val pillDpH = with(density) { pillPxH.toDp() }
            val pillOffsetX = (placement.leftPx * scaleX).roundToInt()
            val pillOffsetY = (placement.topPx * scaleY).roundToInt()
            val hasCollision = placement.collidingZones.isNotEmpty()
            val pillShape = RoundedCornerShape(if (isFull) 12.dp else 999.dp)

            Box(
                modifier = Modifier
                    .offset { IntOffset(pillOffsetX, pillOffsetY) }
                    .size(pillDpW, pillDpH)
                    .alpha(if (placement.shouldTemporarilyHide) 0.25f else config.overlayAlpha)
                    .clip(pillShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xE60B101C), Color(0xDD152038))
                        )
                    )
                    .border(
                        width = if (hasCollision) 1.5.dp else 1.dp,
                        color = if (hasCollision) Color(0xFFFF4D6D) else AetherColors.AccentCyan.copy(alpha = 0.55f),
                        shape = pillShape
                    )
                    .pointerInput(boxWpx, boxHpx, config.manualPosXFraction, config.manualPosYFraction, config.positionLocked) {
                        if (config.positionLocked) return@pointerInput
                        var dragXFrac = (placement.leftPx + placement.widthPx * 0.5f) / refW
                        var dragYFrac = (placement.topPx + placement.heightPx * 0.5f) / refH
                        detectDragGestures(
                            onDragStart = {
                                dragXFrac = (placement.leftPx + placement.widthPx * 0.5f) / refW
                                dragYFrac = (placement.topPx + placement.heightPx * 0.5f) / refH
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragXFrac = (dragXFrac + dragAmount.x / boxWpx.coerceAtLeast(1f)).coerceIn(0.0f, 1.0f)
                                dragYFrac = (dragYFrac + dragAmount.y / boxHpx.coerceAtLeast(1f)).coerceIn(0.0f, 1.0f)
                                onManualDragUpdate(dragXFrac, dragYFrac)
                            },
                            onDragEnd = { onManualDragEnd() }
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                when (config.displayMode) {
                    LiveLyricsDisplayMode.LYRICS_ONLY -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Floating above the blocks tonight",
                                color = Color(0xFFF5F8FF),
                                fontSize = (config.lyricsFontSizeSp * 0.62f).coerceIn(8f, 13f).sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (config.showMusicalNote) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.5.dp)
                                            .background(Color(0x33FFFFFF))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(noteFrac)
                                            .height(1.5.dp)
                                            .background(AetherColors.AccentCyan)
                                    )
                                    Text(
                                        text = "♪",
                                        color = AetherColors.AccentCyan,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = (78f * noteFrac).dp)
                                    )
                                }
                            }
                        }
                    }
                    LiveLyricsDisplayMode.COMPACT_MUSIC -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (config.showAlbumArtwork) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(AetherColors.AccentCyan)
                                )
                            }
                            if (config.metadataEnabled) {
                                Text(
                                    text = trackTitle,
                                    color = Color(0xFFEAF1FF),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(0.45f, fill = false)
                                )
                            }
                            val lyricNote = if (config.showMusicalNote) "♪ Floating above the blocks" else "Floating above the blocks"
                            Text(
                                text = if (config.lyricsEnabled) lyricNote else trackArtist,
                                color = AetherColors.AccentCyan,
                                fontSize = (config.lyricsFontSizeSp * 0.56f).coerceIn(7.5f, 11.5f).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    LiveLyricsDisplayMode.FULL_MUSIC -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (config.showAlbumArtwork) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(AetherColors.AccentViolet, AetherColors.AccentCyan)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("♪", color = Color.White, fontSize = 8.sp)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (config.metadataEnabled) trackTitle else "Music",
                                        color = Color(0xFFF5F8FF),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (config.metadataEnabled) trackArtist else "STAR Live Lyrics",
                                        color = Color(0xFF9EB0CC),
                                        fontSize = 6.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (config.lyricsEnabled) {
                                Text(
                                    text = if (config.showMusicalNote) "♪ Floating above the blocks" else "Floating above the blocks",
                                    color = Color(0xFFF5F8FF),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            LinearProgressIndicator(
                                progress = { noteFrac },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = AetherColors.AccentCyan,
                                trackColor = Color(0x33FFFFFF)
                            )
                        }
                    }
                }
            }
        }

        // Collision Warning / HUD Safety Banner
        val collisionLabels = remember(config, starHudConfig) {
            val res = LiveLyricsHudLayoutSolver.solve(
                HudPlacementRequest(
                    containerWidthPx = 1920f,
                    containerHeightPx = 1080f,
                    density = 3.0f,
                    safeLeftPx = 0f,
                    safeTopPx = 0f,
                    safeRightPx = 0f,
                    safeBottomPx = 0f,
                    desiredWidthPx = when (config.displayMode) {
                        LiveLyricsDisplayMode.FULL_MUSIC -> 364f
                        LiveLyricsDisplayMode.LYRICS_ONLY -> 248f
                        else -> 286f
                    } * config.overlayScale * 3.0f,
                    desiredHeightPx = (if (config.displayMode == LiveLyricsDisplayMode.FULL_MUSIC) 168f else 42f) * config.overlayScale * 3.0f,
                    config = config,
                    starHudConfig = starHudConfig
                )
            )
            res
        }

        if (collisionLabels.collidingZones.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0x33FF4D6D),
                border = BorderStroke(1.dp, Color(0xAAFF4D6D))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "HUD Collision Warning: overlaps ${collisionLabels.collidingZones.joinToString(", ") { it.label }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFC4CF),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onEnableAutoSafe) {
                        Text("Auto-Fix", color = AetherColors.AccentCyan)
                    }
                }
            }
        } else if (collisionLabels.autoRepositioned) {
            Text(
                text = "Auto HUD-Safe shifted overlay to '${collisionLabels.selectedSlotName}' to avoid HUD elements.",
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.AccentCyan
            )
        } else {
            Text(
                text = "HUD Status: Clear (no collisions with hotbar, vitals, or touch controls).",
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.TierVerifiedFg
            )
        }
    }
}

@Composable
private fun StarPlaylistRow(
    track: StarLocalTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = AetherColors.SurfaceDark,
        border = BorderStroke(
            1.dp,
            if (isCurrent) AetherColors.AccentCyan.copy(alpha = 0.5f) else AetherColors.OutlineVariantDark
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) AetherColors.AccentCyan else AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AetherTonalButton(text = if (isCurrent) "Replay" else "Play", onClick = onPlay)
            AetherOutlinedButton(text = "Remove", onClick = onRemove)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AetherColors.OnSurfaceDark)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceVariantDark)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = AetherMonoStyle.copy(color = AetherColors.AccentCyan))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range
        )
    }
}

@Composable
private fun ManualLyricsSyncDialog(
    initialTitle: String,
    initialArtist: String,
    initialDurationMs: Long,
    currentPlaybackPositionMs: () -> Long,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, durationMs: Long, rawLrc: String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var rawText by remember { mutableStateOf("") }
    var tapIndex by remember { mutableIntStateOf(0) }
    var syncedLines by remember { mutableStateOf(listOf<String>()) }
    var sessionStartMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val plainLines = remember(rawText) {
        rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Lyrics & Tap-Sync Editor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Paste LRC or plain lyric lines") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                if (plainLines.isNotEmpty()) {
                    val nextPreview = plainLines.getOrNull(tapIndex) ?: "All lines timestamped!"
                    Text(
                        text = "Next line ($tapIndex/${plainLines.size}): $nextPreview",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.AccentCyan
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AetherTonalButton(
                            text = "Tap to Stamp Line",
                            enabled = tapIndex < plainLines.size,
                            onClick = {
                                val lineText = plainLines.getOrNull(tapIndex) ?: return@AetherTonalButton
                                val posMs = currentPlaybackPositionMs().takeIf { it > 0L }
                                    ?: (System.currentTimeMillis() - sessionStartMs)
                                val stripped = lineText.replace(Regex("""^\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]"""), "").trim()
                                val stamped = "[${LrcParser.formatTimestamp(posMs)}]$stripped"
                                val updated = syncedLines + stamped
                                syncedLines = updated
                                tapIndex++
                                if (tapIndex >= plainLines.size) {
                                    rawText = updated.joinToString("\n")
                                }
                            }
                        )
                        AetherOutlinedButton(
                            text = "Reset Tap",
                            onClick = {
                                tapIndex = 0
                                syncedLines = emptyList()
                                sessionStartMs = System.currentTimeMillis()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (rawText.isNotBlank() || syncedLines.isNotEmpty()),
                onClick = {
                    val finalContent = if (syncedLines.isNotEmpty() && tapIndex >= plainLines.size) {
                        syncedLines.joinToString("\n")
                    } else {
                        rawText
                    }
                    onSave(title.trim(), artist.trim(), initialDurationMs, finalContent)
                }
            ) {
                Text("Save Local Lyrics")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
