package com.star.launcher.lyrics.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.star.launcher.lyrics.engine.LyricsTrackKey
import com.star.launcher.lyrics.media.AlbumPaletteExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * File-backed playlist store for the optional STAR Music Player.
 * Extracts local audio metadata and caches small album art thumbnails locally without uploading anything.
 */
object StarPlaylistStore {
    private const val TAG = "StarPlaylistStore"
    private const val PLAYLIST_FILE = "playlist.json"

    fun loadPlaylist(context: Context): List<StarLocalTrack> {
        val file = playlistFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = ArrayList<StarLocalTrack>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    StarLocalTrack(
                        id = o.optString("id"),
                        uriString = o.optString("uriString"),
                        title = o.optString("title"),
                        artist = o.optString("artist", "Unknown Artist"),
                        album = o.optString("album", ""),
                        durationMs = o.optLong("durationMs", 0L),
                        artworkPath = o.optString("artworkPath", "").takeIf { it.isNotBlank() }
                    )
                )
            }
            out
        }.getOrElse { emptyList() }
    }

    fun savePlaylist(context: Context, tracks: List<StarLocalTrack>) {
        val file = playlistFile(context)
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("uriString", t.uriString)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("durationMs", t.durationMs)
                if (t.artworkPath != null) put("artworkPath", t.artworkPath)
            })
        }
        writeAtomic(file, arr.toString(2))
    }

    suspend fun addTracksFromUris(context: Context, uris: List<Uri>): List<StarLocalTrack> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val existing = loadPlaylist(appCtx).toMutableList()
        val existingUris = existing.map { it.uriString }.toHashSet()

        for (uri in uris) {
            runCatching {
                appCtx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val uriStr = uri.toString()
            if (uriStr in existingUris) continue
            val track = extractTrackMetadata(appCtx, uri)
            existing.add(track)
            existingUris.add(uriStr)
        }
        savePlaylist(appCtx, existing)
        existing
    }

    fun removeTrack(context: Context, trackId: String): List<StarLocalTrack> {
        val appCtx = context.applicationContext
        val current = loadPlaylist(appCtx)
        val target = current.firstOrNull { it.id == trackId }
        val updated = current.filterNot { it.id == trackId }
        target?.artworkPath?.let { path ->
            if (updated.none { it.artworkPath == path }) {
                File(path).delete()
            }
        }
        savePlaylist(appCtx, updated)
        return updated
    }

    fun clearPlaylist(context: Context) {
        val appCtx = context.applicationContext
        savePlaylist(appCtx, emptyList())
        artDir(appCtx).listFiles()?.forEach { it.delete() }
    }

    private fun extractTrackMetadata(context: Context, uri: Uri): StarLocalTrack {
        val id = UUID.randomUUID().toString()
        var title = resolveDisplayName(context, uri)
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Local Track"
        var artist = "Unknown Artist"
        var album = ""
        var durationMs = 0L
        var artPath: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { album = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L)?.let { durationMs = it }

            val embedded = retriever.embeddedPicture
            if (embedded != null && embedded.isNotEmpty()) {
                val decoded = BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
                if (decoded != null) {
                    val scaled = AlbumPaletteExtractor.downscaleArtwork(decoded, 160)
                    if (scaled !== decoded) decoded.recycle()
                    val hash = LyricsTrackKey.sha256("$title|$artist|$album")
                    val dir = artDir(context).apply { mkdirs() }
                    val out = File(dir, "$hash.png")
                    FileOutputStream(out).use { fos ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, fos)
                    }
                    scaled.recycle()
                    artPath = out.absolutePath
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Metadata extraction partial failure for $uri", t)
        } finally {
            runCatching { retriever.release() }
        }

        return StarLocalTrack(
            id = id,
            uriString = uri.toString(),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkPath = artPath
        )
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, "star_music_player")

    private fun artDir(context: Context): File =
        File(rootDir(context), "art")

    private fun playlistFile(context: Context): File =
        File(rootDir(context), PLAYLIST_FILE)

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed writing ${target.name}", e)
            tmp.delete()
        }
    }
}
