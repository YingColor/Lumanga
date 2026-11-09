package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes one page from memory. Standard formats go through BitmapFactory (cheap and
 * sample-friendly); anything newer such as HEIF/AVIF falls back to ImageDecoder.
 * The decoded bitmap is always bounded by a pixel budget so a huge scan cannot OOM the app.
 */
object PageDecoder {
    // 8 M pixels ~= 32 MB ARGB_8888. Tall Webtoon strips used to produce a
    // single 64 MB bitmap here; capping the budget also keeps page mode safe.
    private const val MAX_PIXELS = 8L * 1024 * 1024
    private const val MAX_SAMPLE = 32

    /**
     * quality: 0 = fit screen, 1 = zoomed, 2 = zoomed further.
     * `tight` is for small preview thumbnails: it allows a much smaller decoded
     * bitmap (roughly 1.25x the displayed size instead of 2x), which cuts both
     * decode time and memory by several times on high-resolution scans.
     */
    fun decode(
        bytes: ByteArray,
        targetW: Int,
        targetH: Int,
        quality: Int = 0,
        tight: Boolean = false
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight

        if (srcW > 0 && srcH > 0) {
            val factor = qualityFactor(quality)
            val wantedW = (targetW * factor).coerceAtLeast(256)
            val wantedH = (targetH * factor).coerceAtLeast(256)
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeSample(srcW, srcH, wantedW, wantedH, tight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return decodeWithImageDecoder(bytes, targetW, targetH, quality)
    }

    /** Width / height from a (possibly truncated) image header, without decoding pixels. */
    fun bounds(bytes: ByteArray, length: Int = bytes.size): Pair<Int, Int>? {
        val n = length.coerceIn(0, bytes.size)
        if (n <= 0) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val ok = runCatching { BitmapFactory.decodeByteArray(bytes, 0, n, options) }.isSuccess
        return if (ok && options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }

    /**
     * Decodes one horizontal strip of a very tall Webtoon image. A 800 x 20,000
     * scan cannot be decoded as a single bitmap (memory and GPU limits), so the
     * continuous-scroll reader asks for ~1,000 px tall slices, one at a time.
     * BitmapRegionDecoder covers JPEG / PNG / WebP; HEIF / AVIF fall back to
     * ImageDecoder's crop (Android 10+).
     */
    @Suppress("DEPRECATION")
    fun decodeRegion(bytes: ByteArray, top: Int, bottom: Int, targetWidth: Int): Bitmap? {
        val decoder = runCatching {
            // The byte[] overload only exists from Android 12; the stream one has
            // been there since API 10, so it works all the way down to Android 5.
            BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
        }.getOrNull()
        if (decoder != null) {
            try {
                val w = decoder.width
                val h = decoder.height
                val t = top.coerceIn(0, (h - 1).coerceAtLeast(0))
                val b = bottom.coerceIn(t + 1, h)
                if (b <= t) return null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = regionSample(w, targetWidth)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = runCatching { decoder.decodeRegion(Rect(0, t, w, b), options) }.getOrNull()
                if (bitmap != null) return bitmap
            } finally {
                runCatching { decoder.recycle() }
            }
        }
        return decodeStripWithImageDecoder(bytes, top, bottom, targetWidth)
    }

    private fun decodeStripWithImageDecoder(
        bytes: ByteArray,
        top: Int,
        bottom: Int,
        targetWidth: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                val t = top.coerceIn(0, h - 1)
                val b = bottom.coerceIn(t + 1, h)
                decoder.setCrop(Rect(0, t, w, b))
                val ratio = targetWidth.toFloat() / w
                decoder.setTargetSize(
                    (w * ratio).toInt().coerceAtLeast(1),
                    ((b - t) * ratio).toInt().coerceAtLeast(1)
                )
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
        }.getOrNull()
    }

    /**
     * Last-resort whole-image decode for formats without region decoding.
     * Scales to a fixed pixel budget first, so a 20,000 px strip never becomes
     * a single giant bitmap.
     */
    fun decodeWhole(bytes: ByteArray, maxPixels: Long = 4L * 1024 * 1024): Bitmap? {
        val size = bounds(bytes) ?: return null
        val ratio = min(1f, kotlin.math.sqrt(maxPixels.toDouble() / (size.first.toDouble() * size.second)).toFloat())
        val w = (size.first * ratio).toInt().coerceAtLeast(1)
        val h = (size.second * ratio).toInt().coerceAtLeast(1)
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.setTargetSize(w, h)
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
        }.getOrNull()
    }

    private fun regionSample(srcW: Int, targetWidth: Int): Int {
        var sample = 1
        while (srcW / sample > targetWidth * 2 && sample < MAX_SAMPLE) sample *= 2
        return sample
    }

    private fun decodeWithImageDecoder(
        bytes: ByteArray,
        targetW: Int,
        targetH: Int,
        quality: Int
    ): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val factor = qualityFactor(quality)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width.coerceAtLeast(1)
            val h = info.size.height.coerceAtLeast(1)
            val wantedSide = max(targetW, targetH) * factor
            val ratio = min(1f, wantedSide.toFloat() / max(w, h).toFloat())
            val pixelRatio = min(1f, kotlin.math.sqrt(MAX_PIXELS.toDouble() / (w.toDouble() * h)).toFloat())
            val finalRatio = min(ratio, pixelRatio)
            val outW = (w * finalRatio).toInt().coerceAtLeast(1)
            val outH = (h * finalRatio).toInt().coerceAtLeast(1)
            decoder.setTargetSize(outW, outH)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        }
    }

    private fun qualityFactor(quality: Int): Int = when {
        quality >= 2 -> 3
        quality == 1 -> 2
        else -> 1
    }

    private fun computeSample(
        srcW: Int,
        srcH: Int,
        wantedW: Int,
        wantedH: Int,
        tight: Boolean
    ): Int {
        var sample = 1
        while ((srcW.toLong() * srcH) / (sample.toLong() * sample) > MAX_PIXELS && sample < MAX_SAMPLE) {
            sample *= 2
        }
        val slack = if (tight) 1.25f else 2f
        while ((srcW.toFloat() / sample) > wantedW * slack &&
            (srcH.toFloat() / sample) > wantedH * slack && sample < MAX_SAMPLE
        ) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
