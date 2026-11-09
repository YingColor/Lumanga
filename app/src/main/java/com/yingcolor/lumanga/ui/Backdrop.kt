package com.yingcolor.lumanga.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max



















internal class BackdropShot(
    val image: ImageBitmap,
    val windowWidth: Int,
    val windowHeight: Int
)

internal object Backdrop {
    
    private const val SCALE = 8

    
    private const val PASSES = 3

    
    private const val RADIUS = 3

    suspend fun capture(window: Window): BackdropShot? {
        val view = window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val frame = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val copied = suspendCancellableCoroutine { continuation ->
            val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }
            runCatching {
                PixelCopy.request(window, frame, listener, Handler(Looper.getMainLooper()))
            }.onFailure {
                if (continuation.isActive) continuation.resume(false)
            }
        }
        if (!copied) {
            frame.recycle()
            return null
        }
        val blurred = withContext(Dispatchers.Default) { shrinkAndBlur(frame) }
            ?: return null
        return BackdropShot(blurred, width, height)
    }

    private fun shrinkAndBlur(frame: Bitmap): ImageBitmap? = runCatching {
        val width = max(1, frame.width / SCALE)
        val height = max(1, frame.height / SCALE)
        val small = Bitmap.createScaledBitmap(frame, width, height, true)
        frame.recycle()

        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)

        var buffer = pixels
        repeat(PASSES) { buffer = boxBlur(buffer, width, height, RADIUS) }
        small.setPixels(buffer, 0, width, 0, 0, width, height)

        
        small.asImageBitmap()
    }.getOrNull()

    
    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return source
        val span = radius * 2 + 1
        val horizontal = IntArray(source.size)

        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val p = source[row + offset.coerceIn(0, width - 1)]
                a += (p ushr 24) and 0xFF
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until width) {
                horizontal[row + x] =
                    ((a / span) shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                val leaving = source[row + (x - radius).coerceIn(0, width - 1)]
                val entering = source[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }

        val vertical = IntArray(source.size)
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val p = horizontal[offset.coerceIn(0, height - 1) * width + x]
                a += (p ushr 24) and 0xFF
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until height) {
                vertical[y * width + x] =
                    ((a / span) shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                val leaving = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
        return vertical
    }
}
