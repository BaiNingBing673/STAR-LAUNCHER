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
import com.star.launcher.ui.theme.AetherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Lightweight Compose image loader backed by an in-memory [LruCache] and a bounded disk cache
 * under `cacheDir/mod_images`, avoiding extra image-loader dependencies while remaining safe under
 * rapid scrolling and offline conditions.
 */
object AetherImageCache {
    private val memoryCache = object : LruCache<String, ImageBitmap>(48) {}

    fun getMemory(url: String): ImageBitmap? = memoryCache.get(url)

    suspend fun load(context: Context, url: String, maxDimensionPx: Int = 960): ImageBitmap? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        memoryCache.get(url)?.let { return it }

        return withContext(Dispatchers.IO) {
            memoryCache.get(url)?.let { return@withContext it }
            val cacheDir = File(context.cacheDir, "mod_images").apply { mkdirs() }
            val diskFile = File(cacheDir, sha256Hex(url))
            val bytes = if (diskFile.isFile && diskFile.length() > 0L) {
                runCatching { diskFile.readBytes() }.getOrNull()
            } else {
                null
            } ?: downloadImageBytes(url)?.also { downloaded ->
                runCatching { diskFile.writeBytes(downloaded) }
            } ?: return@withContext null

            decodeScaledBitmap(bytes, maxDimensionPx)?.asImageBitmap()?.also { bitmap ->
                memoryCache.put(url, bitmap)
            }
        }
    }

    private fun downloadImageBytes(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "STAR-Launcher/1.0")
        }
        try {
            if (conn.responseCode in 200..299) {
                val contentLength = conn.contentLengthLong
                if (contentLength > 8L * 1024L * 1024L) {
                    return@runCatching null
                }
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun decodeScaledBitmap(bytes: ByteArray, maxDimensionPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun AetherRemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimensionPx: Int = 960
) {
    val context = LocalContext.current
    val normalized = url?.trim()?.takeIf { it.isNotEmpty() }
    var imageBitmap by remember(normalized) {
        mutableStateOf(normalized?.let { AetherImageCache.getMemory(it) })
    }

    LaunchedEffect(normalized) {
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
