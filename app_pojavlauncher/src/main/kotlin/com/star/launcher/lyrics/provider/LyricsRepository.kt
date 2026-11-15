package com.star.launcher.lyrics.provider

import android.content.Context
import android.net.Uri
import android.util.Log
import com.star.launcher.lyrics.config.LyricsProviderType
import com.star.launcher.lyrics.engine.LrcParser
import com.star.launcher.lyrics.engine.LyricLine
import com.star.launcher.lyrics.engine.LyricWordSpan
import com.star.launcher.lyrics.engine.LyricsDocument
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.LyricsLookupOutcome
import com.star.launcher.lyrics.engine.LyricsTrackKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Coordinates local custom LRC files, persistent disk cache, per-track timing offsets,
 * negative lookup TTLs, and online LRCLIB lookup.
 */
class LyricsRepository(
    context: Context,
    private val lrcLibClient: LrcLibClient = LrcLibClient(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "star_live_lyrics")
    private val cacheDir = File(rootDir, "cache")
    private val negCacheDir = File(rootDir, "neg")
    private val customDir = File(rootDir, "custom")
    private val offsetsFile = File(rootDir, "offsets.json")

    private val memoryCache = object : LinkedHashMap<String, LyricsDocument>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsDocument>?): Boolean = size > 32
    }
    private val offsetsLock = Any()

    init {
        cacheDir.mkdirs()
        negCacheDir.mkdirs()
        customDir.mkdirs()
    }

    suspend fun loadLyrics(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        provider: LyricsProviderType,
        forceRefresh: Boolean = false
    ): LyricsLookupOutcome {
        val trackKey = LyricsTrackKey.build(title, artist, durationMs)
        val keyHash = LyricsTrackKey.sha256(trackKey)

        // 1. Custom user-imported or manually synced lyrics always take priority
        loadCustomLyrics(trackKey, keyHash, title, artist, album, durationMs)?.let { customDoc ->
            synchronized(memoryCache) { memoryCache[trackKey] = customDoc }
            return LyricsLookupOutcome.Found(customDoc, fromCache = true)
        }

        if (!forceRefresh) {
            synchronized(memoryCache) { memoryCache[trackKey] }?.let { memDoc ->
                return LyricsLookupOutcome.Found(memDoc, fromCache = true)
            }
            loadCachedDocument(keyHash)?.let { diskDoc ->
                synchronized(memoryCache) { memoryCache[trackKey] = diskDoc }
                return LyricsLookupOutcome.Found(diskDoc, fromCache = true)
            }
            if (isNegativelyCached(keyHash)) {
                return LyricsLookupOutcome.NotFound("Previously unavailable (cached)", fromCache = true)
            }
        }

        if (provider == LyricsProviderType.LOCAL_ONLY) {
            return LyricsLookupOutcome.NotFound("Local-only mode: no local lyrics found")
        }

        val outcome = lrcLibClient.fetchLyrics(trackKey, title, artist, album, durationMs)
        when (outcome) {
            is LyricsLookupOutcome.Found -> {
                synchronized(memoryCache) { memoryCache[trackKey] = outcome.document }
                withContext(Dispatchers.IO) {
                    saveCachedDocument(keyHash, outcome.document)
                    File(negCacheDir, "$keyHash.json").delete()
                }
            }
            is LyricsLookupOutcome.NotFound -> {
                withContext(Dispatchers.IO) {
                    saveNegativeEntry(keyHash, outcome.reason)
                }
            }
            is LyricsLookupOutcome.RateLimited,
            is LyricsLookupOutcome.NetworkFailure -> Unit
        }
        return outcome
    }

    suspend fun importCustomLyricsFromUri(
        uri: Uri,
        trackKey: String,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): Result<LyricsDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: throw IOException("Unable to read selected lyrics file")
            saveCustomLyrics(
                rawText = raw,
                trackKey = trackKey,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs
            )
        }
    }

    suspend fun saveCustomLyrics(
        rawText: String,
        trackKey: String,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): LyricsDocument = withContext(Dispatchers.IO) {
        val parsed = LrcParser.parse(
            rawContent = rawText,
            trackKey = trackKey,
            fallbackTitle = title,
            fallbackArtist = artist,
            fallbackAlbum = album,
            durationMs = durationMs,
            sourceName = "Custom",
            attribution = "Local custom lyrics",
            isCustom = true
        )
        val keyHash = LyricsTrackKey.sha256(trackKey)
        val outFile = File(customDir, "$keyHash.lrc")
        val contentToWrite = if (parsed.isSynchronized) LrcParser.toLrc(parsed) else rawText.trim()
        writeAtomic(outFile, contentToWrite)
        File(negCacheDir, "$keyHash.json").delete()
        synchronized(memoryCache) { memoryCache[trackKey] = parsed }
        parsed
    }

    suspend fun deleteCustomLyrics(trackKey: String): Boolean = withContext(Dispatchers.IO) {
        val keyHash = LyricsTrackKey.sha256(trackKey)
        synchronized(memoryCache) { memoryCache.remove(trackKey) }
        File(customDir, "$keyHash.lrc").delete()
    }

    fun getTrackOffsetMs(trackKey: String): Long {
        if (trackKey.isBlank()) return 0L
        synchronized(offsetsLock) {
            return readOffsetsMap()[trackKey] ?: 0L
        }
    }

    fun setTrackOffsetMs(trackKey: String, offsetMs: Long): Long {
        if (trackKey.isBlank()) return 0L
        val clamped = offsetMs.coerceIn(-15_000L, 15_000L)
        synchronized(offsetsLock) {
            val map = readOffsetsMap().toMutableMap()
            if (clamped == 0L) map.remove(trackKey) else map[trackKey] = clamped
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            writeAtomic(offsetsFile, obj.toString(2))
        }
        return clamped
    }

    suspend fun clearRemoteCache(): Int = withContext(Dispatchers.IO) {
        synchronized(memoryCache) { memoryCache.clear() }
        var removed = 0
        cacheDir.listFiles()?.forEach { if (it.delete()) removed++ }
        negCacheDir.listFiles()?.forEach { if (it.delete()) removed++ }
        removed
    }

    fun cacheStats(): Pair<Int, Int> {
        val cachedCount = cacheDir.listFiles()?.count { it.isFile } ?: 0
        val customCount = customDir.listFiles()?.count { it.isFile } ?: 0
        return cachedCount to customCount
    }

    private fun loadCustomLyrics(
        trackKey: String,
        keyHash: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsDocument? {
        val file = File(customDir, "$keyHash.lrc")
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            LrcParser.parse(
                rawContent = text,
                trackKey = trackKey,
                fallbackTitle = title,
                fallbackArtist = artist,
                fallbackAlbum = album,
                durationMs = durationMs,
                sourceName = "Custom",
                attribution = "Local custom lyrics",
                isCustom = true
            )
        }.onFailure {
            Log.w(TAG, "Failed to read custom lyrics ${file.name}", it)
        }.getOrNull()
    }

    private fun saveCachedDocument(keyHash: String, doc: LyricsDocument) {
        val root = JSONObject().apply {
            put("trackKey", doc.trackKey)
            put("title", doc.title)
            put("artist", doc.artist)
            put("album", doc.album)
            put("durationMs", doc.durationMs)
            put("format", doc.format.name)
            put("plainText", doc.plainText)
            put("sourceName", doc.sourceName)
            put("attribution", doc.attribution)
            put("embeddedOffsetMs", doc.embeddedOffsetMs)
            put("cachedAtMs", clockMs())
            val linesArr = JSONArray()
            for (line in doc.lines) {
                val lObj = JSONObject().apply {
                    put("start", line.startTimeMs)
                    if (line.endTimeMs != null) put("end", line.endTimeMs)
                    put("text", line.text)
                    if (line.words.isNotEmpty()) {
                        val wArr = JSONArray()
                        for (w in line.words) {
                            wArr.put(JSONObject().apply {
                                put("start", w.startTimeMs)
                                if (w.endTimeMs != null) put("end", w.endTimeMs)
                                put("text", w.text)
                            })
                        }
                        put("words", wArr)
                    }
                }
                linesArr.put(lObj)
            }
            put("lines", linesArr)
        }
        writeAtomic(File(cacheDir, "$keyHash.json"), root.toString())
    }

    private fun loadCachedDocument(keyHash: String): LyricsDocument? {
        val file = File(cacheDir, "$keyHash.json")
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val format = enumValues<LyricsFormat>().firstOrNull {
                it.name == root.optString("format")
            } ?: LyricsFormat.NONE
            val linesArr = root.optJSONArray("lines") ?: JSONArray()
            val lines = ArrayList<LyricLine>(linesArr.length())
            for (i in 0 until linesArr.length()) {
                val lObj = linesArr.optJSONObject(i) ?: continue
                val wordsArr = lObj.optJSONArray("words")
                val words = if (wordsArr != null) {
                    val list = ArrayList<LyricWordSpan>(wordsArr.length())
                    for (j in 0 until wordsArr.length()) {
                        val wObj = wordsArr.optJSONObject(j) ?: continue
                        list.add(
                            LyricWordSpan(
                                startTimeMs = wObj.optLong("start"),
                                endTimeMs = if (wObj.has("end")) wObj.optLong("end") else null,
                                text = wObj.optString("text")
                            )
                        )
                    }
                    list
                } else emptyList()
                lines.add(
                    LyricLine(
                        startTimeMs = lObj.optLong("start"),
                        endTimeMs = if (lObj.has("end")) lObj.optLong("end") else null,
                        text = lObj.optString("text"),
                        words = words
                    )
                )
            }
            LyricsDocument(
                trackKey = root.optString("trackKey"),
                title = root.optString("title"),
                artist = root.optString("artist"),
                album = root.optString("album"),
                durationMs = root.optLong("durationMs"),
                format = format,
                lines = lines,
                plainText = root.optString("plainText"),
                sourceName = root.optString("sourceName"),
                attribution = root.optString("attribution"),
                isCustom = false,
                embeddedOffsetMs = root.optLong("embeddedOffsetMs", 0L)
            )
        }.onFailure {
            Log.w(TAG, "Corrupted cached lyrics ${file.name}, deleting", it)
            file.delete()
        }.getOrNull()
    }

    private fun saveNegativeEntry(keyHash: String, reason: String) {
        val obj = JSONObject().apply {
            put("cachedAtMs", clockMs())
            put("reason", reason)
        }
        writeAtomic(File(negCacheDir, "$keyHash.json"), obj.toString())
    }

    private fun isNegativelyCached(keyHash: String): Boolean {
        val file = File(negCacheDir, "$keyHash.json")
        if (!file.isFile) return false
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val age = clockMs() - obj.optLong("cachedAtMs", 0L)
            if (age in 0 until NEGATIVE_CACHE_TTL_MS) {
                true
            } else {
                file.delete()
                false
            }
        }.getOrElse {
            file.delete()
            false
        }
    }

    private fun readOffsetsMap(): Map<String, Long> {
        if (!offsetsFile.isFile) return emptyMap()
        return runCatching {
            val obj = JSONObject(offsetsFile.readText(Charsets.UTF_8))
            val out = HashMap<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optLong(k, 0L)
            }
            out
        }.getOrElse { emptyMap() }
    }

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

    companion object {
        private const val TAG = "LyricsRepository"
        private const val NEGATIVE_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    }
}
