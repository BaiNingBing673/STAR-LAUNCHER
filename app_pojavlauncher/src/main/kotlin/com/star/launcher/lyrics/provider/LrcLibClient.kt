package com.star.launcher.lyrics.provider

import com.star.launcher.lyrics.engine.LrcParser
import com.star.launcher.lyrics.engine.LyricsDocument
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.LyricsLookupOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * LRCLIB API client following https://lrclib.net/docs:
 * - Recognizable STAR Launcher User-Agent
 * - Exact lookup (`/api/get`) with track title, artist, album, and duration
 * - Search fallback (`/api/search`) with strict duration validation
 * - In-flight request deduplication and rate-limit (`429` + min request spacing) protection
 */
class LrcLibClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = buildDefaultUserAgent(),
    private val minSpacingMs: Long = 1_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val requestLock = Mutex()
    private val inflightLock = Mutex()
    private val inflightRequests = HashMap<String, CompletableDeferred<LyricsLookupOutcome>>()

    @Volatile
    private var lastRequestAtMs: Long = 0L

    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    suspend fun fetchLyrics(
        trackKey: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsLookupOutcome {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty()) {
            return LyricsLookupOutcome.NotFound("Missing track title")
        }

        val deferred: CompletableDeferred<LyricsLookupOutcome>
        val isOwner: Boolean
        inflightLock.withLock {
            val existing = inflightRequests[trackKey]
            if (existing != null) {
                deferred = existing
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                inflightRequests[trackKey] = deferred
                isOwner = true
            }
        }

        if (!isOwner) {
            return deferred.await()
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                performLookup(trackKey, cleanTitle, cleanArtist, album.trim(), durationMs)
            }
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            val err = LyricsLookupOutcome.NetworkFailure(t.message ?: "LRCLIB request failed")
            deferred.complete(err)
            err
        } finally {
            inflightLock.withLock {
                inflightRequests.remove(trackKey)
            }
        }
    }

    private suspend fun performLookup(
        trackKey: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsLookupOutcome {
        val now = clockMs()
        if (now < rateLimitedUntilMs) {
            val waitSec = ((rateLimitedUntilMs - now) / 1000L).coerceAtLeast(1L)
            return LyricsLookupOutcome.RateLimited(waitSec)
        }

        val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 0

        // 1. Exact match via /api/get when we have artist + duration
        if (artist.isNotEmpty() && durationSec > 0) {
            val getQuery = buildString {
                append(baseUrl.trimEnd('/')).append("/api/get")
                append("?track_name=").append(encode(title))
                append("&artist_name=").append(encode(artist))
                if (album.isNotEmpty()) {
                    append("&album_name=").append(encode(album))
                }
                append("&duration=").append(durationSec)
            }
            when (val getRes = executeJsonRequest(getQuery)) {
                is HttpResult.Success -> {
                    val obj = JSONObject(getRes.body)
                    val doc = parseRecordToDocument(obj, trackKey, title, artist, album, durationMs)
                    if (doc != null && doc.format != LyricsFormat.NONE) {
                        return LyricsLookupOutcome.Found(doc, fromCache = false)
                    }
                }
                is HttpResult.RateLimited -> {
                    rateLimitedUntilMs = clockMs() + getRes.retryAfterSec * 1000L
                    return LyricsLookupOutcome.RateLimited(getRes.retryAfterSec)
                }
                is HttpResult.NotFound -> {
                    // Fall through to /api/search
                }
                is HttpResult.Failure -> {
                    return LyricsLookupOutcome.NetworkFailure(getRes.reason)
                }
            }
        }

        // 2. Search fallback via /api/search with duration validation
        val searchQuery = buildString {
            append(baseUrl.trimEnd('/')).append("/api/search")
            append("?track_name=").append(encode(title))
            if (artist.isNotEmpty()) {
                append("&artist_name=").append(encode(artist))
            }
        }
        return when (val searchRes = executeJsonRequest(searchQuery)) {
            is HttpResult.Success -> {
                val arr = JSONArray(searchRes.body)
                val bestObj = selectBestSearchCandidate(arr, title, artist, durationSec)
                if (bestObj == null) {
                    LyricsLookupOutcome.NotFound("No matching lyrics on LRCLIB")
                } else {
                    val doc = parseRecordToDocument(bestObj, trackKey, title, artist, album, durationMs)
                    if (doc == null || doc.format == LyricsFormat.NONE) {
                        LyricsLookupOutcome.NotFound("LRCLIB record contains no lyrics")
                    } else {
                        LyricsLookupOutcome.Found(doc, fromCache = false)
                    }
                }
            }
            is HttpResult.RateLimited -> {
                rateLimitedUntilMs = clockMs() + searchRes.retryAfterSec * 1000L
                LyricsLookupOutcome.RateLimited(searchRes.retryAfterSec)
            }
            is HttpResult.NotFound -> LyricsLookupOutcome.NotFound("No matching lyrics on LRCLIB")
            is HttpResult.Failure -> LyricsLookupOutcome.NetworkFailure(searchRes.reason)
        }
    }

    private fun selectBestSearchCandidate(
        arr: JSONArray,
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Int
    ): JSONObject? {
        var bestObj: JSONObject? = null
        var bestScore = Int.MIN_VALUE

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optBoolean("instrumental", false)) continue
            val synced = item.optString("syncedLyrics", "").trim()
            val plain = item.optString("plainLyrics", "").trim()
            if (synced.isEmpty() && plain.isEmpty()) continue

            val candDuration = item.optDouble("duration", -1.0)
            if (expectedDurationSec > 0 && candDuration > 0.0) {
                val diff = abs(candDuration - expectedDurationSec.toDouble())
                if (diff > MAX_DURATION_TOLERANCE_SEC) {
                    continue
                }
            }

            var score = 0
            if (synced.isNotEmpty()) score += 50
            val candTitle = item.optString("trackName", item.optString("name", "")).trim()
            val candArtist = item.optString("artistName", "").trim()
            if (candTitle.equals(expectedTitle, ignoreCase = true)) score += 25
            if (expectedArtist.isNotEmpty() && candArtist.equals(expectedArtist, ignoreCase = true)) score += 25
            if (expectedDurationSec > 0 && candDuration > 0.0) {
                val diffPenalty = abs(candDuration - expectedDurationSec.toDouble()).toInt() * 3
                score -= diffPenalty
            }
            if (score > bestScore) {
                bestScore = score
                bestObj = item
            }
        }
        return bestObj
    }

    private fun parseRecordToDocument(
        obj: JSONObject,
        trackKey: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long
    ): LyricsDocument? {
        if (obj.optBoolean("instrumental", false)) {
            return null
        }
        val title = obj.optString("trackName", obj.optString("name", fallbackTitle)).ifBlank { fallbackTitle }
        val artist = obj.optString("artistName", fallbackArtist).ifBlank { fallbackArtist }
        val album = obj.optString("albumName", fallbackAlbum).ifBlank { fallbackAlbum }
        val durationSec = obj.optDouble("duration", 0.0)
        val durationMs = if (durationSec > 0.0) (durationSec * 1000.0).toLong() else fallbackDurationMs

        val synced = obj.optString("syncedLyrics", "").trim()
        if (synced.isNotEmpty()) {
            val parsed = LrcParser.parse(
                rawContent = synced,
                trackKey = trackKey,
                fallbackTitle = title,
                fallbackArtist = artist,
                fallbackAlbum = album,
                durationMs = durationMs,
                sourceName = "LRCLIB",
                attribution = ATTRIBUTION_LRCLIB,
                isCustom = false
            )
            if (parsed.isSynchronized) return parsed
        }

        val plain = obj.optString("plainLyrics", "").trim()
        if (plain.isNotEmpty()) {
            return LrcParser.fromPlainText(
                plainText = plain,
                trackKey = trackKey,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                sourceName = "LRCLIB",
                attribution = ATTRIBUTION_LRCLIB,
                isCustom = false
            )
        }
        return null
    }

    private suspend fun executeJsonRequest(urlString: String): HttpResult {
        requestLock.withLock {
            val now = clockMs()
            val elapsed = now - lastRequestAtMs
            if (elapsed in 0 until minSpacingMs) {
                delay(minSpacingMs - elapsed)
            }
            lastRequestAtMs = clockMs()
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    HttpResult.Success(body)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> HttpResult.NotFound
                429 -> {
                    val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(5L, 300L) ?: 30L
                    HttpResult.RateLimited(retryAfter)
                }
                else -> HttpResult.Failure("HTTP $code from LRCLIB")
            }
        } catch (e: IOException) {
            HttpResult.Failure(e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private sealed interface HttpResult {
        data class Success(val body: String) : HttpResult
        data object NotFound : HttpResult
        data class RateLimited(val retryAfterSec: Long) : HttpResult
        data class Failure(val reason: String) : HttpResult
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net"
        const val ATTRIBUTION_LRCLIB = "Lyrics via LRCLIB (lrclib.net)"
        private const val MAX_DURATION_TOLERANCE_SEC = 5.0
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 8_000

        fun buildDefaultUserAgent(): String {
            val version = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("1.0")
            return "STAR-Launcher/$version (Android; STAR Live Lyrics; https://github.com/star-launcher)"
        }
    }
}
