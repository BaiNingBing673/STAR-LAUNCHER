package com.star.launcher.lyrics.media

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, off-thread album artwork downscaler and ambient accent color extractor.
 * Avoids heavy blur passes or per-frame bitmap allocations on the UI or render thread.
 */
object AlbumPaletteExtractor {
    private val colorCache = LruCache<String, Int>(48)

    fun downscaleArtwork(source: Bitmap, maxDimensionPx: Int = 144): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        if (w <= maxDimensionPx && h <= maxDimensionPx) {
            return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        val scale = maxDimensionPx.toFloat() / max(w, h).toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    suspend fun extractAccentColor(artworkKey: String, bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled) return MediaTrackInfo.DEFAULT_ACCENT_COLOR
        if (artworkKey.isNotBlank()) {
            colorCache.get(artworkKey)?.let { return it }
        }
        val color = withContext(Dispatchers.Default) {
            computeAccentColor(bitmap)
        }
        if (artworkKey.isNotBlank()) {
            colorCache.put(artworkKey, color)
        }
        return color
    }

    fun computeAccentColor(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return MediaTrackInfo.DEFAULT_ACCENT_COLOR
        }
        val sample = if (bitmap.width > 24 || bitmap.height > 24) {
            Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        } else {
            bitmap
        }
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== bitmap) {
            sample.recycle()
        }

        val hsv = FloatArray(3)
        var bestHue = 195f
        var bestSat = 0.65f
        var bestScore = -1f

        val bucketCounts = IntArray(24)
        val bucketSatSum = FloatArray(24)
        val bucketValSum = FloatArray(24)

        for (px in pixels) {
            if (Color.alpha(px) < 180) continue
            Color.colorToHSV(px, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]
            if (v < 0.16f || s < 0.18f) continue
            val bucket = ((h / 15f).toInt()).coerceIn(0, 23)
            bucketCounts[bucket]++
            bucketSatSum[bucket] += s
            bucketValSum[bucket] += v
        }

        for (i in 0 until 24) {
            val count = bucketCounts[i]
            if (count == 0) continue
            val avgSat = bucketSatSum[i] / count
            val avgVal = bucketValSum[i] / count
            val score = count * (0.4f + avgSat) * (0.5f + avgVal)
            if (score > bestScore) {
                bestScore = score
                bestHue = i * 15f + 7.5f
                bestSat = avgSat.coerceIn(0.45f, 0.88f)
            }
        }

        if (bestScore < 0f) {
            return MediaTrackInfo.DEFAULT_ACCENT_COLOR
        }
        // Lift brightness slightly so dark album art still yields a readable glow on dark glass
        val finalVal = min(0.95f, max(0.72f, 0.85f))
        return Color.HSVToColor(255, floatArrayOf(bestHue, bestSat, finalVal))
    }

    fun clearCache() {
        colorCache.evictAll()
    }
}
