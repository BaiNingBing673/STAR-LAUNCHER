package com.star.launcher.lyrics.player

import android.content.Context
import android.media.session.PlaybackState
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class StarLocalTrack(
    val id: String,
    val uriString: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkPath: String? = null
)

data class StarPlayerSnapshot(
    val isServiceActive: Boolean = false,
    val isPlaying: Boolean = false,
    val currentIndex: Int = -1,
    val currentTrack: StarLocalTrack? = null,
    val positionMs: Long = 0L,
    val positionUpdateElapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val stateCode: Int = PlaybackState.STATE_NONE,
    val updatedAtMs: Long = 0L
)

/**
 * Cross-process snapshot bridge for STAR Music Player so `:game` ([net.kdt.pojavlaunch.MainActivity])
 * can display and control STAR's built-in player even without Notification Listener permission.
 */
object StarPlayerStateBridge {
    private const val STATE_FILE = "star_music_player_state.json"
    const val ACTION_STATE_CHANGED = "com.star.launcher.action.STAR_PLAYER_STATE_CHANGED"

    fun write(context: Context, snapshot: StarPlayerSnapshot) {
        val file = stateFile(context)
        val root = JSONObject().apply {
            put("isServiceActive", snapshot.isServiceActive)
            put("isPlaying", snapshot.isPlaying)
            put("currentIndex", snapshot.currentIndex)
            put("positionMs", snapshot.positionMs)
            put("positionUpdateElapsedMs", snapshot.positionUpdateElapsedMs)
            put("durationMs", snapshot.durationMs)
            put("stateCode", snapshot.stateCode)
            put("updatedAtMs", snapshot.updatedAtMs)
            snapshot.currentTrack?.let { t ->
                put("track", JSONObject().apply {
                    put("id", t.id)
                    put("uriString", t.uriString)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("durationMs", t.durationMs)
                    if (t.artworkPath != null) put("artworkPath", t.artworkPath)
                })
            }
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: IOException) {
            tmp.delete()
        }
    }

    fun read(context: Context): StarPlayerSnapshot {
        val file = stateFile(context)
        if (!file.isFile) return StarPlayerSnapshot()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val trackObj = root.optJSONObject("track")
            val track = trackObj?.let {
                StarLocalTrack(
                    id = it.optString("id"),
                    uriString = it.optString("uriString"),
                    title = it.optString("title"),
                    artist = it.optString("artist", "Unknown Artist"),
                    album = it.optString("album", ""),
                    durationMs = it.optLong("durationMs", 0L),
                    artworkPath = it.optString("artworkPath", "").takeIf { p -> p.isNotBlank() }
                )
            }
            StarPlayerSnapshot(
                isServiceActive = root.optBoolean("isServiceActive", false),
                isPlaying = root.optBoolean("isPlaying", false),
                currentIndex = root.optInt("currentIndex", -1),
                currentTrack = track,
                positionMs = root.optLong("positionMs", 0L),
                positionUpdateElapsedMs = root.optLong("positionUpdateElapsedMs", 0L),
                durationMs = root.optLong("durationMs", 0L),
                stateCode = root.optInt("stateCode", PlaybackState.STATE_NONE),
                updatedAtMs = root.optLong("updatedAtMs", 0L)
            )
        }.getOrElse { StarPlayerSnapshot() }
    }

    private fun stateFile(context: Context): File =
        File(context.applicationContext.filesDir, "star_music_player/$STATE_FILE")
}
