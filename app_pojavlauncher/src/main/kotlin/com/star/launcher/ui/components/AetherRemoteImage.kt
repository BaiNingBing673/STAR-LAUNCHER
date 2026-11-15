package com.star.launcher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.star.launcher.compat.UiPerformanceProfileManager
import com.star.launcher.ui.theme.AetherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight Compose image loader backed by an in-memory [LruCache], in-flight request
 * deduplication, concurrency throttling, and a bounded disk cache under `cacheDir/mod_images`.
 */
object AetherImageCache {
    private const val MAX_MEMORY_CACHE_KB = 12 * 1024
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            val kb = (value.width * value.height * 4) / 1024
            return kb.coerceAtLeast(1)
        }
    }

    private val inFlightLocks = ConcurrentHashMap<String, Mutex>()
    private val decodeSemaphore = Semaphore(4)

    @Volatile
    private var cachedModImagesDir: File? = null

    @JvmStatic
    fun getMemory(url: String): ImageBitmap? = memoryCache.get(url)

    @JvmStatic
    fun trimMemory(aggressive: Boolean = false) {
        if (aggressive) {
            memoryCache.evictAll()
        } else {
            memoryCache.trimToSize(MAX_MEMORY_CACHE_KB / 4)
        }
    }

    suspend fun load(context: Context, url: String, maxDimensionPx: Int = 256): ImageBitmap? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        memoryCache.get(url)?.let { return it }

        val perfSpec = UiPerformanceProfileManager.currentSpec()
        val effectiveMaxDim = if (maxDimensionPx <= 384) {
            minOf(maxDimensionPx, perfSpec.maxThumbnailDimensionPx)
        } else {
            maxDimensionPx
        }
        val preferRgb565 = perfSpec.preferRgb565Thumbnails && effectiveMaxDim <= 384

        return withContext(Dispatchers.IO) {
            memoryCache.get(url)?.let { return@withContext it }
            val urlLock = inFlightLocks.getOrPut(url) { Mutex() }
            try {
                urlLock.withLock {
                    memoryCache.get(url)?.let { return@withLock it }
                    val cacheDir = resolveModImagesDir(context)
                    val diskFile = File(cacheDir, sha256Hex(url))
                    val diskBytes = if (diskFile.isFile && diskFile.length() > 0L) {
                        runCatching { diskFile.readBytes() }.getOrNull()
                    } else {
                        null
                    }

                    if (diskBytes != null) {
                        val decoded = decodeSemaphore.withPermit {
                            decodeScaledBitmap(diskBytes, effectiveMaxDim, preferRgb565)?.asImageBitmap()
                        }
                        if (decoded != null) {
                            memoryCache.put(url, decoded)
                            return@withLock decoded
                        }
                        diskFile.delete()
                    }

                    val downloaded = downloadImageBytesWithRetry(url) ?: return@withLock null
                    val decoded = decodeSemaphore.withPermit {
                        decodeScaledBitmap(downloaded, effectiveMaxDim, preferRgb565)?.asImageBitmap()
                    } ?: return@withLock null
                    runCatching { diskFile.writeBytes(downloaded) }
                    memoryCache.put(url, decoded)
                    decoded
                }
            } finally {
                inFlightLocks.remove(url, urlLock)
            }
        }
    }

    private fun resolveModImagesDir(context: Context): File {
        cachedModImagesDir?.let { if (it.isDirectory) return it }
        return File(context.applicationContext.cacheDir, "mod_images").apply {
            if (!exists()) mkdirs()
            cachedModImagesDir = this
        }
    }

    private fun downloadImageBytesWithRetry(url: String): ByteArray? {
        repeat(2) { attempt ->
            val bytes = downloadImageBytes(url)
            if (bytes != null && bytes.isNotEmpty()) return bytes
            if (attempt == 0) runCatching { Thread.sleep(150L) }
        }
        return null
    }

    private fun downloadImageBytes(initialUrl: String): ByteArray? = runCatching {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "image/*")
                setRequestProperty("User-Agent", "STAR-Launcher/1.0")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects < 4) {
                    val location = conn.getHeaderField("Location")?.trim()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        redirects++
                        continue
                    }
                }
                if (code in 200..299) {
                    val contentLength = conn.contentLengthLong
                    if (contentLength > 8L * 1024L * 1024L) {
                        return@runCatching null
                    }
                    return@runCatching conn.inputStream.use { it.readBytes() }
                }
                return@runCatching null
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }.getOrNull()

    private fun decodeScaledBitmap(
        bytes: ByteArray,
        maxDimensionPx: Int,
        preferRgb565: Boolean
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }
}

@Composable
fun AetherRemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimensionPx: Int = 256
) {
    val context = LocalContext.current
    val normalized = url?.trim()?.takeIf { it.isNotEmpty() }
    var imageBitmap by remember(normalized) {
        mutableStateOf(normalized?.let { AetherImageCache.getMemory(it) })
    }

    LaunchedEffect(normalized, maxDimensionPx) {
        if (normalized != null && imageBitmap == null) {
            imageBitmap = AetherImageCache.load(context, normalized, maxDimensionPx)
        }
    }

    val loaded = imageBitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(AetherColors.SurfaceVariantDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AetherIcons.Mods,
                contentDescription = contentDescription,
                tint = AetherColors.OnSurfaceVariantDark.copy(alpha = 0.65f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
