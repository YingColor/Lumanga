package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object PaddleOcr {
    @Volatile
    private var instance: PaddleOcrEngine? = null

    fun engine(context: Context): PaddleOcrEngine =
        instance ?: synchronized(this) {
            instance ?: PaddleOcrEngine(context.applicationContext).also { instance = it }
        }
}

class PaddleOcrEngine(private val context: Context) : OcrEngine {
    private var runtime: PaddleRuntime? = null
    private val lock = Any()

    override suspend fun recognize(bitmap: Bitmap): List<TextRegion> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val engine = runtime ?: PaddleRuntime(copyModels(context)).also { runtime = it }
            engine.recognize(bitmap)
        }
    }

    private fun copyModels(context: Context): File {
        val dir = File(context.filesDir, "ocr")
        if (!dir.exists()) dir.mkdirs()
        for (name in MODELS) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            context.assets.open("ocr/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
        return dir
    }

    private companion object {
        val MODELS = listOf(
            "ppocrv6_small_det_640_fp32.tflite",
            "ppocrv6_small_rec_640_fp32.tflite",
            "characters.json"
        )
    }
}

private class PaddleRuntime(private val dir: File) {
    private val det = Session(File(dir, "ppocrv6_small_det_640_fp32.tflite"), 3 * 640 * 640, 640 * 640)
    private val rec = Session(File(dir, "ppocrv6_small_rec_640_fp32.tflite"), 3 * 48 * 640, 80 * 18710)
    private val vocabulary: List<String> =
        JSONArray(File(dir, "characters.json").readText(Charsets.UTF_8))
            .let { array -> List(array.length()) { array.getString(it) } }

    private class Session(path: File, inputElements: Int, outputElements: Int) {
        private val interpreter = Interpreter(path, Interpreter.Options().setNumThreads(4))
        private val inputBuffer =
            ByteBuffer.allocateDirect(inputElements * 4).order(ByteOrder.nativeOrder())
        private val outputBuffer =
            ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder())
        private val outputSize = outputElements

        fun run(values: FloatArray): FloatArray {
            inputBuffer.rewind()
            inputBuffer.asFloatBuffer().put(values)
            inputBuffer.rewind()
            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val output = FloatArray(outputSize)
            outputBuffer.asFloatBuffer().get(output)
            return output
        }
    }

    fun recognize(bitmap: Bitmap): List<TextRegion> {
        val scale = 640f / max(bitmap.width, bitmap.height)
        val scaledW = max(1, Math.round(bitmap.width * scale))
        val scaledH = max(1, Math.round(bitmap.height * scale))
        val offsetX = (640 - scaledW) / 2
        val offsetY = (640 - scaledH) / 2

        val square = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            Rect(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        val side = 640
        val planeSize = side * side
        val detectorInput = FloatArray(3 * planeSize)
        val pixels = IntArray(planeSize)
        square.getPixels(pixels, 0, side, 0, 0, side, side)
        val mean = floatArrayOf(0.406f * 255f, 0.456f * 255f, 0.485f * 255f)
        val std = floatArrayOf(0.225f * 255f, 0.224f * 255f, 0.229f * 255f)
        for (i in 0 until planeSize) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            detectorInput[2 * planeSize + i] = (r - mean[0]) / std[0]
            detectorInput[planeSize + i] = (g - mean[1]) / std[1]
            detectorInput[i] = (b - mean[2]) / std[2]
        }

        val probabilities = det.run(detectorInput)
        val mask = BooleanArray(planeSize)
        for (i in mask.indices) mask[i] = probabilities[i] > 0.2f

        val columns = mergeIntoColumns(connectedComponents(mask))
        if (columns.isEmpty()) {
            square.recycle()
            return emptyList()
        }

        val ordered = columns.sortedWith(
            compareByDescending<Rect> { it.centerX() }.thenBy { it.top }
        )
        val regions = ArrayList<TextRegion>(ordered.size)
        for (column in ordered) {
            val pad = 4
            val left = (column.left - pad).coerceIn(0, side - 2)
            val top = (column.top - pad).coerceIn(0, side - 2)
            val right = (column.right + pad).coerceIn(left + 2, side)
            val bottom = (column.bottom + pad).coerceIn(top + 2, side)
            var crop = Bitmap.createBitmap(square, left, top, right - left, bottom - top)
            if (bottom - top >= (right - left) * 1.5f) crop = rotate(crop)
            val text = recognizeLine(crop)
            crop.recycle()
            if (text.isBlank()) continue
            val rect = Rect(
                ((left - offsetX) / scale).toInt().coerceIn(0, bitmap.width - 1),
                ((top - offsetY) / scale).toInt().coerceIn(0, bitmap.height - 1),
                ((right - offsetX) / scale).toInt().coerceIn(1, bitmap.width),
                ((bottom - offsetY) / scale).toInt().coerceIn(1, bitmap.height)
            )
            regions.add(TextRegion(text, rect))
        }
        square.recycle()
        return regions
    }

    private fun rotate(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun recognizeLine(crop: Bitmap): String {
        val targetW = 640
        val naturalW = max(1, ceil(48.0 * crop.width / crop.height.toDouble()).toInt())
        val resizedW = min(naturalW, targetW)
        val scaled = Bitmap.createScaledBitmap(crop, resizedW, 48, true)
        val pixels = IntArray(resizedW * 48)
        scaled.getPixels(pixels, 0, resizedW, 0, 0, resizedW, 48)
        scaled.recycle()

        val input = FloatArray(3 * 48 * targetW)
        for (y in 0 until 48) {
            val rowOffset = y * resizedW
            val targetRow = y * targetW
            for (x in 0 until resizedW) {
                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                input[2 * 48 * targetW + targetRow + x] = (r - 127.5f) / 127.5f
                input[48 * targetW + targetRow + x] = (g - 127.5f) / 127.5f
                input[targetRow + x] = (b - 127.5f) / 127.5f
            }
        }

        val output = rec.run(input)
        val steps = output.size / vocabulary.size
        val text = StringBuilder()
        var previous = -1
        for (t in 0 until steps) {
            val offset = t * vocabulary.size
            var best = 0
            for (c in 1 until vocabulary.size) {
                if (output[offset + c] > output[offset + best]) best = c
            }
            if (best != previous && best != 0) text.append(vocabulary[best])
            previous = best
        }
        return text.toString().trim()
    }

    private fun connectedComponents(mask: BooleanArray): List<Rect> {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = ArrayList<Rect>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = 640
            var top = 640
            var right = 0
            var bottom = 0
            var area = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % 640
                val y = index / 640
                area++
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
                if (x > 0) {
                    val n = index - 1
                    if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (x < 639) {
                    val n = index + 1
                    if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (y > 0) {
                    val n = index - 640
                    if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
                if (y < 639) {
                    val n = index + 640
                    if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n }
                }
            }
            if (area < 9) continue
            if (min(right - left + 1, bottom - top + 1) < 3) continue
            result.add(Rect(left, top, right + 1, bottom + 1))
        }
        return result
    }

    private fun mergeIntoColumns(components: List<Rect>): List<Rect> {
        val columns = ArrayList<Rect>()
        for (component in components.sortedBy { it.centerX() }) {
            val target = columns.firstOrNull { column ->
                val overlap = min(column.right, component.right) - max(column.left, component.left)
                val minWidth = min(column.width(), component.width()).coerceAtLeast(1)
                overlap.toFloat() / minWidth > 0.5f
            }
            if (target == null) columns.add(Rect(component)) else target.union(component)
        }
        return columns
    }
}
