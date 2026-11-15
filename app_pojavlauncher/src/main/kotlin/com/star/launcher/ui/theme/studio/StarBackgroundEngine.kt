package com.star.launcher.ui.theme.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface BackgroundImportResult {
    data class Success(val savedPath: String) : BackgroundImportResult
    data class Error(val reason: String) : BackgroundImportResult
}

/**
 * Memory-safe, cache-backed custom background & blur engine for STAR UI Studio.
 *
 * - Imports PNG, JPG/JPEG, and WEBP via Photo Picker / SAF URIs with zero storage permissions.
 * - Downscales oversized photos to bounded resolution before saving into app-private storage.
 * - Pre-computes and caches downscaled StackBlur tiers off the UI thread so Compose never
 *   performs expensive per-frame blur operations.
 */
object StarBackgroundEngine {
    private const val STUDIO_DIR_NAME = "star_ui_studio"
    private const val BACKGROUNDS_DIR_NAME = "backgrounds"
    private const val MAX_IMPORTED_DIMENSION_PX = 1920
    private const val MAX_IMPORT_BYTES = 35L * 1024L * 1024L

    private val SUPPORTED_MIME_TYPES = setOf(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/webp"
    )

    // Bounded LRU cache (approx 16 MB max across sharp + tiny blurred tiers)
    private val bitmapCache = object : LruCache<String, CachedBitmapEntry>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CachedBitmapEntry): Int {
            return value.byteCount.coerceAtLeast(1)
        }
    }

    private data class CachedBitmapEntry(
        val imageBitmap: ImageBitmap,
        val byteCount: Int
    )

    fun backgroundsDir(context: Context): File {
        val dir = File(File(context.filesDir, STUDIO_DIR_NAME), BACKGROUNDS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun importBackgroundFromUri(
        context: Context,
        uri: Uri
    ): BackgroundImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val reportedMime = resolver.getType(uri)?.lowercase()

        // Step 1: Probe bounds and actual image MIME without allocating pixel memory
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val mimeOk = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        }.getOrElse {
            return@withContext BackgroundImportResult.Error("Unable to open selected image: ${it.message ?: "IO error"}")
        }
        val detectedMime = boundsOptions.outMimeType?.lowercase() ?: reportedMime
        if (detectedMime == null || detectedMime !in SUPPORTED_MIME_TYPES) {
            return@withContext BackgroundImportResult.Error(
                "Unsupported image format (${detectedMime ?: "unknown"}). Use PNG, JPG/JPEG, or WEBP."
            )
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return@withContext BackgroundImportResult.Error("Selected file is not a valid readable image.")
        }

        // Guard against absurdly huge files before decoding
        val fileSizeBytes = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (fileSizeBytes > MAX_IMPORT_BYTES) {
            return@withContext BackgroundImportResult.Error("Image is too large (> 35 MB).")
        }

        val sampleSize = computeInSampleSize(
            srcWidth = boundsOptions.outWidth,
            srcHeight = boundsOptions.outHeight,
            maxDimension = MAX_IMPORTED_DIMENSION_PX
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var decoded = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }.getOrNull() ?: return@withContext BackgroundImportResult.Error("Failed to decode image pixels.")

        // Apply EXIF rotation for JPEG/WEBP photos when available
        val exifDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            }.getOrDefault(0f)
        } else 0f

        if (exifDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(exifDegrees) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) {
                decoded.recycle()
                decoded = rotated
            }
        }

        // Fit strictly inside MAX_IMPORTED_DIMENSION_PX
        val maxSide = max(decoded.width, decoded.height)
        if (maxSide > MAX_IMPORTED_DIMENSION_PX) {
            val scale = MAX_IMPORTED_DIMENSION_PX.toFloat() / maxSide.toFloat()
            val targetW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (scaled !== decoded) {
                decoded.recycle()
                decoded = scaled
            }
        }

        val outDir = backgroundsDir(context)
        val ext = if (detectedMime == "image/png" && decoded.hasAlpha()) "png" else "webp"
        val outFile = File(outDir, "bg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
        val tempFile = File(outDir, "${outFile.name}.tmp")

        val writeResult = runCatching {
            FileOutputStream(tempFile).use { fos ->
                if (ext == "png") {
                    decoded.compress(Bitmap.CompressFormat.PNG, 100, fos)
                } else {
                    @Suppress("DEPRECATION")
                    val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    decoded.compress(fmt, 92, fos)
                }
            }
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
        }
        decoded.recycle()

        writeResult.onFailure { err ->
            tempFile.delete()
            outFile.delete()
            return@withContext BackgroundImportResult.Error("Failed to save wallpaper: ${err.message ?: "IO error"}")
        }

        BackgroundImportResult.Success(outFile.absolutePath)
    }

    /**
     * Loads the sharp display-optimized wallpaper from app-private storage.
     */
    suspend fun loadSharpBackdrop(
        imagePath: String?,
        quality: StarEffectQuality
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        if (imagePath.isNullOrBlank()) return@withContext null
        val file = File(imagePath)
        if (!file.isFile || !file.canRead()) return@withContext null

        val maxDim = when (quality) {
            StarEffectQuality.LOW -> 720
            StarEffectQuality.MEDIUM -> 1080
            StarEffectQuality.HIGH -> 1440
            StarEffectQuality.ULTRA -> 1920
        }
        val cacheKey = "sharp|$imagePath|${file.lastModified()}|$maxDim|${quality.name}"
        bitmapCache.get(cacheKey)?.let { return@withContext it.imageBitmap }

        val bmp = decodeBoundedBitmap(
            file = file,
            maxDimension = maxDim,
            preferRgb565 = quality == StarEffectQuality.LOW
        ) ?: return@withContext null

        val entry = CachedBitmapEntry(bmp.asImageBitmap(), bmp.allocationByteCount)
        bitmapCache.put(cacheKey, entry)
        entry.imageBitmap
    }

    /**
     * Computes or returns a cached downscaled blurred [ImageBitmap] for the requested blur bucket.
     */
    suspend fun loadBlurredBackdrop(
        imagePath: String?,
        blurRadiusDp: Float,
        quality: StarEffectQuality
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        if (imagePath.isNullOrBlank()) return@withContext null
        val quantizedBucket = quantizeBlurBucket(blurRadiusDp)
        if (quantizedBucket <= 0) {
            return@withContext loadSharpBackdrop(imagePath, quality)
        }
        val file = File(imagePath)
        if (!file.isFile || !file.canRead()) return@withContext null

        val cacheKey = "blur|$imagePath|${file.lastModified()}|$quantizedBucket|${quality.name}"
        bitmapCache.get(cacheKey)?.let { return@withContext it.imageBitmap }

        val targetMaxDim = when (quality) {
            StarEffectQuality.LOW -> 180
            StarEffectQuality.MEDIUM -> 240
            StarEffectQuality.HIGH -> 320
            StarEffectQuality.ULTRA -> 420
        }
        val base = decodeBoundedBitmap(
            file = file,
            maxDimension = targetMaxDim,
            preferRgb565 = false
        ) ?: return@withContext null

        // Scale blur radius relative to downscale ratio so visual blur stays consistent
        val effectiveRadius = (quantizedBucket * when (quality) {
            StarEffectQuality.LOW -> 0.35f
            StarEffectQuality.MEDIUM -> 0.45f
            StarEffectQuality.HIGH -> 0.55f
            StarEffectQuality.ULTRA -> 0.70f
        }).roundToInt().coerceIn(1, 25)

        val blurred = stackBlurInPlace(base, effectiveRadius)
        val entry = CachedBitmapEntry(blurred.asImageBitmap(), blurred.allocationByteCount)
        bitmapCache.put(cacheKey, entry)
        entry.imageBitmap
    }

    fun quantizeBlurBucket(blurDp: Float): Int {
        if (blurDp < 1.5f) return 0
        return when {
            blurDp < 9f -> 6
            blurDp < 17f -> 12
            blurDp < 27f -> 20
            else -> 30
        }
    }

    fun trimMemory(aggressive: Boolean) {
        if (aggressive) {
            bitmapCache.evictAll()
        } else {
            bitmapCache.trimToSize(bitmapCache.maxSize() / 2)
        }
    }

    fun cleanupOrphanedBackgrounds(context: Context, referencedPaths: Set<String>) {
        runCatching {
            val dir = backgroundsDir(context)
            val files = dir.listFiles() ?: return
            files.forEach { file ->
                if (file.isFile && file.absolutePath !in referencedPaths) {
                    file.delete()
                }
            }
        }
    }

    private fun decodeBoundedBitmap(
        file: File,
        maxDimension: Int,
        preferRgb565: Boolean
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val maxSide = max(decoded.width, decoded.height)
        return if (maxSide > maxDimension) {
            val scale = maxDimension.toFloat() / maxSide.toFloat()
            val w = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val h = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
            if (scaled !== decoded) decoded.recycle()
            if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
        } else if (!decoded.isMutable && !preferRgb565) {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
        } else {
            decoded
        }
    }

    private fun computeInSampleSize(srcWidth: Int, srcHeight: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val maxSide = max(srcWidth, srcHeight)
        while ((maxSide / inSampleSize) > maxDimension * 2) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Fast pure-Kotlin integer StackBlur (Mario Klingemann algorithm) operating on ARGB_8888.
     * Avoids deprecated RenderScript and works identically across Android API 21..37.
     */
    internal fun stackBlurInPlace(bitmap: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 25)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return bitmap

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = r + r + 1

        val red = IntArray(wh)
        val green = IntArray(wh)
        val blue = IntArray(wh)
        var rSum: Int
        var gSum: Int
        var bSum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum)
        for (k in 0 until 256 * divSum) {
            dv[k] = k / divSum
        }

        yi = 0
        yw = 0

        val stack = Array(div) { IntArray(3) }
        var stackPointer: Int
        var stackStart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = r + 1
        var routSum: Int
        var goutSum: Int
        var boutSum: Int
        var rinSum: Int
        var ginSum: Int
        var binSum: Int

        y = 0
        while (y < h) {
            rSum = 0
            gSum = 0
            bSum = 0
            routSum = 0
            goutSum = 0
            boutSum = 0
            rinSum = 0
            ginSum = 0
            binSum = 0

            i = -r
            while (i <= r) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + r]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rSum += sir[0] * rbs
                gSum += sir[1] * rbs
                bSum += sir[2] * rbs
                if (i > 0) {
                    rinSum += sir[0]
                    ginSum += sir[1]
                    binSum += sir[2]
                } else {
                    routSum += sir[0]
                    goutSum += sir[1]
                    boutSum += sir[2]
                }
                i++
            }
            stackPointer = r

            x = 0
            while (x < w) {
                red[yi] = dv[rSum]
                green[yi] = dv[gSum]
                blue[yi] = dv[bSum]

                rSum -= routSum
                gSum -= goutSum
                bSum -= boutSum

                stackStart = stackPointer - r + div
                sir = stack[stackStart % div]

                routSum -= sir[0]
                goutSum -= sir[1]
                boutSum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + r + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinSum += sir[0]
                ginSum += sir[1]
                binSum += sir[2]

                rSum += rinSum
                gSum += ginSum
                bSum += binSum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer % div]

                routSum += sir[0]
                goutSum += sir[1]
                boutSum += sir[2]

                rinSum -= sir[0]
                ginSum -= sir[1]
                binSum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            rSum = 0
            gSum = 0
            bSum = 0
            routSum = 0
            goutSum = 0
            boutSum = 0
            rinSum = 0
            ginSum = 0
            binSum = 0
            yp = -r * w

            i = -r
            while (i <= r) {
                yi = max(0, yp) + x
                sir = stack[i + r]
                sir[0] = red[yi]
                sir[1] = green[yi]
                sir[2] = blue[yi]
                rbs = r1 - abs(i)
                rSum += red[yi] * rbs
                gSum += green[yi] * rbs
                bSum += blue[yi] * rbs
                if (i > 0) {
                    rinSum += sir[0]
                    ginSum += sir[1]
                    binSum += sir[2]
                } else {
                    routSum += sir[0]
                    goutSum += sir[1]
                    boutSum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackPointer = r

            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                rSum -= routSum
                gSum -= goutSum
                bSum -= boutSum

                stackStart = stackPointer - r + div
                sir = stack[stackStart % div]

                routSum -= sir[0]
                goutSum -= sir[1]
                boutSum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = red[p]
                sir[1] = green[p]
                sir[2] = blue[p]

                rinSum += sir[0]
                ginSum += sir[1]
                binSum += sir[2]

                rSum += rinSum
                gSum += ginSum
                bSum += binSum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer]

                routSum += sir[0]
                goutSum += sir[1]
                boutSum += sir[2]

                rinSum -= sir[0]
                ginSum -= sir[1]
                binSum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
