package com.star.launcher.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.lyrics.config.LyricsProviderType
import com.star.launcher.lyrics.engine.LyricsFormat
import com.star.launcher.lyrics.engine.LyricsLookupOutcome
import com.star.launcher.lyrics.engine.LyricsTrackKey
import com.star.launcher.lyrics.provider.LrcLibClient
import com.star.launcher.lyrics.provider.LyricsRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LrcLibAndRepositoryTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val requestCount = AtomicInteger(0)
    private val lastUserAgent = AtomicReference("")
    private var responseMode = "GET_OK"

    @Before
    fun setUp() {
        requestCount.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/get") { exchange ->
            requestCount.incrementAndGet()
            lastUserAgent.set(exchange.requestHeaders.getFirst("User-Agent").orEmpty())
            when (responseMode) {
                "GET_OK" -> {
                    val body = """
                        {
                          "id": 101,
                          "trackName": "Sweden",
                          "artistName": "C418",
                          "albumName": "Minecraft - Volume Alpha",
                          "duration": 215.0,
                          "instrumental": false,
                          "syncedLyrics": "[00:10.00]Calm piano notes\n[00:20.00]Building blocks"
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                "RATE_LIMIT" -> {
                    exchange.responseHeaders.add("Retry-After", "45")
                    exchange.sendResponseHeaders(429, -1)
                    exchange.close()
                }
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
        }
        server.createContext("/api/search") { exchange ->
            requestCount.incrementAndGet()
            lastUserAgent.set(exchange.requestHeaders.getFirst("User-Agent").orEmpty())
            val body = """
                [
                  {
                    "id": 1,
                    "trackName": "Sweden",
                    "artistName": "C418",
                    "duration": 400.0,
                    "syncedLyrics": "[00:05.00]Wrong duration remix"
                  },
                  {
                    "id": 2,
                    "trackName": "Sweden",
                    "artistName": "C418",
                    "duration": 216.0,
                    "syncedLyrics": "[00:08.00]Matched duration lyric"
                  }
                ]
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `fetches from LRCLIB with STAR User-Agent and caches locally to avoid repeat calls`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LrcLibClient(baseUrl = baseUrl, minSpacingMs = 0L)
        val repo = LyricsRepository(context, client)
        repo.clearRemoteCache()

        responseMode = "GET_OK"
        val first = repo.loadLyrics("Sweden", "C418", "Volume Alpha", 215_000L, LyricsProviderType.LRCLIB)
        assertTrue(first is LyricsLookupOutcome.Found)
        val found = first as LyricsLookupOutcome.Found
        assertEquals(LyricsFormat.LINE_SYNCED, found.document.format)
        assertTrue(lastUserAgent.get().startsWith("STAR-Launcher/"))
        assertEquals(1, requestCount.get())

        // Second call must hit local cache without touching network
        val second = repo.loadLyrics("Sweden", "C418", "Volume Alpha", 215_000L, LyricsProviderType.LRCLIB)
        assertTrue(second is LyricsLookupOutcome.Found)
        assertTrue((second as LyricsLookupOutcome.Found).fromCache)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun `search fallback validates track duration and handles HTTP 429 rate limits`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LrcLibClient(baseUrl = baseUrl, minSpacingMs = 0L)
        val repo = LyricsRepository(context, client)
        repo.clearRemoteCache()

        responseMode = "NOT_FOUND"
        val searchRes = repo.loadLyrics("Sweden", "C418", "", 215_000L, LyricsProviderType.LRCLIB)
        assertTrue(searchRes is LyricsLookupOutcome.Found)
        assertEquals("Matched duration lyric", (searchRes as LyricsLookupOutcome.Found).document.lines[0].text)

        responseMode = "RATE_LIMIT"
        val rateLimited = repo.loadLyrics("Another", "Artist", "", 180_000L, LyricsProviderType.LRCLIB)
        assertTrue(rateLimited is LyricsLookupOutcome.RateLimited)
        assertEquals(45L, (rateLimited as LyricsLookupOutcome.RateLimited).retryAfterSeconds)
        val hitsBeforeCooldownCheck = requestCount.get()

        // Subsequent call during cooldown short-circuits locally without hitting server
        val cooled = repo.loadLyrics("Third", "Artist", "", 180_000L, LyricsProviderType.LRCLIB)
        assertTrue(cooled is LyricsLookupOutcome.RateLimited)
        assertEquals(hitsBeforeCooldownCheck, requestCount.get())
    }

    @Test
    fun `custom lyrics and per-track offsets override remote lookup`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = LrcLibClient(baseUrl = baseUrl, minSpacingMs = 0L)
        val repo = LyricsRepository(context, client)
        repo.clearRemoteCache()

        val trackKey = LyricsTrackKey.build("My Song", "Me", 120_000L)
        repo.saveCustomLyrics("[00:03.00]Custom synced line", trackKey, "My Song", "Me", durationMs = 120_000L)
        repo.setTrackOffsetMs(trackKey, -750L)

        val res = repo.loadLyrics("My Song", "Me", "", 120_000L, LyricsProviderType.LRCLIB)
        assertTrue(res is LyricsLookupOutcome.Found)
        val doc = (res as LyricsLookupOutcome.Found).document
        assertTrue(doc.isCustom)
        assertEquals("Custom synced line", doc.lines[0].text)
        assertEquals(-750L, repo.getTrackOffsetMs(trackKey))
        assertEquals(0, requestCount.get())
    }
}
