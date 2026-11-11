package com.yingcolor.lumanga.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
    /** Bump whenever a bundled model file changes, so old installs refresh their cache. */
    private val MODEL_BUNDLE_REV = 2
    private val prefs = Prefs(context.applicationContext)
    private var runtime: PaddleRuntime? = null
    private var runtimeModel: OcrModel? = null
    private val lock = Any()

    override suspend fun recognize(bitmap: Bitmap): List<TextRegion> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // Re-read on every page so switching the model in 翻译配置 takes effect without
            // restarting the app; the previous sessions are released before the new ones load.
            val model = OcrModel.from(prefs.ocrModel)
            if (runtime == null || runtimeModel != model) {
                android.util.Log.i("Ocr", "loading ${model.label}")
                runtime?.close()
                runtime = PaddleRuntime(copyModels(context, model), model)
                runtimeModel = model
            }
            runtime!!.recognize(bitmap)
        }
    }

    /**
     * Bundled models are copied out of assets once, but a plain "file exists" check means an
     * app update never replaces a stale model. The stamp ties the cache to both the model and
     * the bundle revision, so a changed model is re-copied on the next run.
     */
    private fun copyModels(context: Context, model: OcrModel): File {
        val dir = File(context.filesDir, "ocr")
        if (!dir.exists()) dir.mkdirs()
        val stamp = File(dir, ".bundle_${model.id}")
        val expected = "$MODEL_BUNDLE_REV"
        val unchanged = runCatching { stamp.readText() }.getOrNull() == expected
        for (name in model.files) {
            val target = File(dir, name)
            if (unchanged && target.exists() && target.length() > 0) continue
            val temp = File(dir, "$name.tmp")
            context.assets.open("ocr/$name").use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        stamp.writeText(expected)
        return dir
    }
}

/** One inference call: fills [out] with the raw logits. Callers own the reusable buffers. */
private interface OcrSession {
    fun run(values: FloatArray, out: FloatArray)
    fun close()
}

/** The original LiteRT backend used by the small model. Not thread-safe, so one per worker. */
private class TfLiteSession(path: File, inputElements: Int, outputElements: Int) : OcrSession {
    private val interpreter = Interpreter(path, Interpreter.Options().setNumThreads(2))
    private val inputBuffer =
        ByteBuffer.allocateDirect(inputElements * 4).order(ByteOrder.nativeOrder())
    private val outputBuffer =
        ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder())

    override fun run(values: FloatArray, out: FloatArray) {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(values)
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(out)
    }

    override fun close() {
        interpreter.close()
    }
}

/**
 * ONNX Runtime backend for the medium model. OrtSession.run is thread-safe and every call
 * owns its input tensor and result, so one session is shared by all workers and the 67 MB
 * of FP16 weights are loaded only once.
 */
private class OnnxSession(path: File, private val shape: LongArray, inputElements: Int) : OcrSession {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    // OrtSession.run is thread-safe, but a FloatBuffer is not: each worker needs its own.
    private val inputBuffers = ThreadLocal.withInitial {
        ByteBuffer.allocateDirect(inputElements * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    init {
        val options = OrtSession.SessionOptions()
        // Two intra-op threads per session, matching the two workers. XNNPACK was tried here
        // but crashed with SIGSEGV inside OrtSession.run on this device, so the plain CPU
        // provider is used instead — slower, but stable.
        options.setIntraOpNumThreads(2)
        session = env.createSession(path.absolutePath, options)
        inputName = session.inputNames.first()
    }

    override fun run(values: FloatArray, out: FloatArray) {
        val inputBuffer = inputBuffers.get()!!
        inputBuffer.rewind()
        inputBuffer.put(values)
        inputBuffer.rewind()
        val tensor = OnnxTensor.createTensor(env, inputBuffer, shape)
        val result = session.run(Collections.singletonMap(inputName, tensor))
        try {
            val buffer = (result.get(0) as OnnxTensor).floatBuffer
            buffer.rewind()
            buffer.get(out)
        } finally {
            result.close()
            tensor.close()
        }
    }

    override fun close() {
        session.close()
    }
}

private class PaddleRuntime(private val dir: File, private val model: OcrModel) {
    /** Longest tile edge fed to the 640 detector; keeps text near its native size. */
    private val TILE_TARGET = 860

    /** Margin added to a detector box before recognition; see the retry note in recognize(). */
    private val TIGHT_PAD = 3
    private val WIDE_PAD = 10

    /** Below this confidence the column is read again with the wider margin. */
    private val WIDE_RETRY_CONF = 0.85f
    private val detFile = File(dir, model.det)
    private val recFile = File(dir, model.rec)
    private val recOutSize = 80 * 18710
    private val vocabulary: List<String> =
        JSONArray(File(dir, "characters.json").readText(Charsets.UTF_8))
            .let { array -> List(array.length()) { array.getString(it) } }

    private val detShared: OnnxSession? =
        if (model.onnx) OnnxSession(detFile, longArrayOf(1, 3, 640, 640), 3 * 640 * 640) else null
    private val recShared: OnnxSession? =
        if (model.onnx) OnnxSession(recFile, longArrayOf(1, 3, 48, 640), 3 * 48 * 640) else null
    private val owned = Collections.synchronizedList(mutableListOf<OcrSession>())

    /**
     * Recognising a page is one inference per text column, and those columns are independent, so
     * they run on two workers instead of one after another. TFLite interpreters are not
     * thread-safe, so each worker gets its own; the cost is one extra copy of the 21 MB recogniser.
     */
    private val recWorkers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "ocr-rec").apply { isDaemon = true }
    }
    private val recLocal = ThreadLocal.withInitial {
        val session: OcrSession = recShared
            ?: TfLiteSession(recFile, 3 * 48 * 640, recOutSize).also { owned.add(it) }
        RecWorker(session, recOutSize)
    }

    private val detWorkers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "ocr-det").apply { isDaemon = true }
    }
    private val detLocal = ThreadLocal.withInitial {
        val session: OcrSession = detShared
            ?: TfLiteSession(detFile, 3 * 640 * 640, 640 * 640).also { owned.add(it) }
        DetWorker(session)
    }

    /** One detector plus its reusable buffers, owned by a single worker thread. */
    private class DetWorker(val session: OcrSession) {
        val input = FloatArray(3 * 640 * 640)
        val output = FloatArray(640 * 640)
        val pixels = IntArray(640 * 640)
    }

    /** One recogniser plus its reusable buffers, owned by a single worker thread. */
    private class RecWorker(private val session: OcrSession, outputSize: Int) {
        val input = FloatArray(3 * 48 * 640)
        private val output = FloatArray(outputSize)

        fun run() {
            session.run(input, output)
        }

        fun logits(): FloatArray = output
    }

    fun close() {
        detShared?.close()
        recShared?.close()
        owned.forEach { runCatching { it.close() } }
        detWorkers.shutdownNow()
        recWorkers.shutdownNow()
    }

    fun recognize(bitmap: Bitmap): List<TextRegion> {
        val started = android.os.SystemClock.elapsedRealtime()
        val boxes = detectColumns(bitmap)
        val detectMs = android.os.SystemClock.elapsedRealtime() - started
        if (boxes.isEmpty()) return emptyList()

        val texts = arrayOfNulls<Pair<String, Float>>(boxes.size)
        val rects = arrayOfNulls<Rect>(boxes.size)
        val verticals = BooleanArray(boxes.size)
        val tasks = boxes.mapIndexed { index, column ->
            Callable {
                val worker = recLocal.get() ?: return@Callable
                val box = cropRect(bitmap, column, TIGHT_PAD)
                if (box.width() < 6 || box.height() < 6) return@Callable
                val vertical = box.height() >= box.width() * 1.5f

                fun read(pad: Int): Pair<String, Float>? {
                    val rect = cropRect(bitmap, column, pad)
                    if (rect.width() < 6 || rect.height() < 6) return null
                    var piece = Bitmap.createBitmap(bitmap, rect.left, rect.top,
                        rect.width(), rect.height())
                    if (vertical) piece = rotate(piece)
                    val outcome = recognizeLine(piece, worker)
                    piece.recycle()
                    return outcome
                }

                var best = read(TIGHT_PAD) ?: return@Callable
                // A tight crop clips the outer strokes of the first and last glyph and the
                // recogniser substitutes a look-alike (ホ comes back as 木). Re-reading with a
                // wider margin recovers them, but too much margin drags the neighbouring column
                // in, so both are tried and the more confident result wins.
                if (best.second < WIDE_RETRY_CONF) {
                    val wide = read(WIDE_PAD)
                    if (wide != null && wide.second > best.second) best = wide
                }
                texts[index] = best
                rects[index] = box
                verticals[index] = vertical
            }
        }
        if (tasks.size == 1) {
            tasks[0].call()
        } else {
            recWorkers.invokeAll(tasks).forEach { it.get() }
        }
        val recogniseMs = android.os.SystemClock.elapsedRealtime() - started - detectMs
        android.util.Log.i("Ocr", "OCR det=${detectMs}ms rec=${recogniseMs}ms boxes=${boxes.size}")

        val regions = ArrayList<TextRegion>(boxes.size)
        boxes.forEachIndexed { index, column ->
            val result = texts[index] ?: return@forEachIndexed
            if (result.first.isBlank()) return@forEachIndexed
            val box = rects[index] ?: return@forEachIndexed
            regions.add(TextRegion(result.first, box, result.second, verticals[index]))
        }
        return regions.sortedWith(
            compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top }
        )
    }

    /**
     * Runs the detector over overlapping tiles instead of one shrunken full page.
     * A 1240x1754 page squeezed into 640 leaves columns ~18 px wide, which the recognizer then
     * has to blow up again; tiling keeps them around 25-35 px and lifts confidence a lot.
     */
    private fun detectColumns(bitmap: Bitmap): List<Rect> {
        val w = bitmap.width
        val h = bitmap.height
        val cols = ((w + TILE_TARGET - 1) / TILE_TARGET).coerceIn(1, 3)
        val rows = ((h + TILE_TARGET - 1) / TILE_TARGET).coerceIn(1, 3)
        val tileW = (w + cols - 1) / cols
        val tileH = (h + rows - 1) / rows
        val bounds = ArrayList<IntArray>(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val overlapX = (tileW * 0.08f).toInt()
                val overlapY = (tileH * 0.08f).toInt()
                val x0 = (col * tileW - overlapX).coerceAtLeast(0)
                val y0 = (row * tileH - overlapY).coerceAtLeast(0)
                val x1 = ((col + 1) * tileW + overlapX).coerceAtMost(w)
                val y1 = ((row + 1) * tileH + overlapY).coerceAtMost(h)
                bounds.add(intArrayOf(x0, y0, x1, y1))
            }
        }
        // Tiles are independent, so two detector workers share the page; results keep tile order.
        val perTile = arrayOfNulls<List<Rect>>(bounds.size)
        val tasks = bounds.mapIndexed { index, b ->
            Callable {
                val worker = detLocal.get() ?: return@Callable
                perTile[index] = detectTile(bitmap, b[0], b[1], b[2], b[3], worker)
            }
        }
        if (tasks.size == 1) tasks[0].call() else detWorkers.invokeAll(tasks).forEach { it.get() }
        val collected = ArrayList<Rect>()
        perTile.forEach { if (it != null) collected.addAll(it) }
        val kept = ArrayList<Rect>()
        for (box in collected.sortedByDescending { it.width() * it.height() }) {
            val duplicate = kept.any { existing ->
                val iw = min(existing.right, box.right) - max(existing.left, box.left)
                val ih = min(existing.bottom, box.bottom) - max(existing.top, box.top)
                if (iw <= 0 || ih <= 0) return@any false
                val smaller = min(existing.width() * existing.height(), box.width() * box.height())
                    .coerceAtLeast(1)
                (iw * ih).toFloat() / smaller > 0.4f
            }
            if (!duplicate) kept.add(box)
        }
        return kept
    }

    private fun detectTile(
        bitmap: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        worker: DetWorker
    ): List<Rect> {
        val tileW = x1 - x0
        val tileH = y1 - y0
        if (tileW < 24 || tileH < 24) return emptyList()
        val scale = 640f / max(tileW, tileH)
        val scaledW = max(1, Math.round(tileW * scale))
        val scaledH = max(1, Math.round(tileH * scale))
        val offsetX = (640 - scaledW) / 2
        val offsetY = (640 - scaledH) / 2

        val square = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            bitmap,
            Rect(x0, y0, x1, y1),
            Rect(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        val side = 640
        val planeSize = side * side
        val detectorInput = worker.input
        val pixels = worker.pixels
        square.getPixels(pixels, 0, side, 0, 0, side, side)
        square.recycle()
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

        worker.session.run(detectorInput, worker.output)
        val probabilities = worker.output
        val mask = BooleanArray(planeSize)
        for (i in mask.indices) mask[i] = probabilities[i] > 0.2f
        val columns = mergeIntoColumns(connectedComponents(mask))
        return columns.mapNotNull { column ->
            val left = x0 + ((column.left - offsetX) / scale).toInt()
            val top = y0 + ((column.top - offsetY) / scale).toInt()
            val right = x0 + ((column.right - offsetX) / scale).toInt()
            val bottom = y0 + ((column.bottom - offsetY) / scale).toInt()
            val rect = Rect(
                left.coerceIn(0, bitmap.width - 1),
                top.coerceIn(0, bitmap.height - 1),
                right.coerceIn(1, bitmap.width),
                bottom.coerceIn(1, bitmap.height)
            )
            if (rect.width() < 6 || rect.height() < 6) null else rect
        }
    }

    /** Grows the detector box by [pad] and clamps it to the page. */
    private fun cropRect(bitmap: Bitmap, column: Rect, pad: Int): Rect {
        val left = (column.left - pad).coerceIn(0, bitmap.width - 2)
        val top = (column.top - pad).coerceIn(0, bitmap.height - 2)
        val right = (column.right + pad).coerceIn(left + 2, bitmap.width)
        val bottom = (column.bottom + pad).coerceIn(top + 2, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    private fun rotate(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun recognizeLine(crop: Bitmap, worker: RecWorker): Pair<String, Float> {
        val targetW = 640
        val naturalW = max(1, ceil(48.0 * crop.width / crop.height.toDouble()).toInt())
        val resizedW = min(naturalW, targetW)
        val scaled = Bitmap.createScaledBitmap(crop, resizedW, 48, true)
        val pixels = IntArray(resizedW * 48)
        scaled.getPixels(pixels, 0, resizedW, 0, 0, resizedW, 48)
        scaled.recycle()

        // Same buffer every call: the trailing padding has to be cleared explicitly now that the
        // array is no longer freshly allocated.
        val input = worker.input
        input.fill(0f)
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

        worker.run()
        val output = worker.logits()
        val steps = output.size / vocabulary.size
        val text = StringBuilder()
        var previous = -1
        var confidenceSum = 0f
        var confidenceCount = 0
        for (t in 0 until steps) {
            val offset = t * vocabulary.size
            var best = 0
            for (c in 1 until vocabulary.size) {
                if (output[offset + c] > output[offset + best]) best = c
            }
            if (best != previous && best != 0) {
                text.append(vocabulary[best])
                confidenceSum += output[offset + best]
                confidenceCount++
            }
            previous = best
        }
        val confidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount
        return text.toString().trim() to confidence
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

    /**
     * Joins the character blobs of one text column. The vertical gap check matters: without it
     * columns that happen to share the same x range in two different bubbles (or two panels)
     * were fused into one tall box, which then swallowed the artwork between them.
     */
    private fun mergeIntoColumns(components: List<Rect>): List<Rect> {
        val columns = ArrayList<Rect>()
        for (component in components.sortedBy { it.centerX() }) {
            val target = columns.firstOrNull { column ->
                val overlap = min(column.right, component.right) - max(column.left, component.left)
                val minWidth = min(column.width(), component.width()).coerceAtLeast(1)
                val gap = maxOf(column.top, component.top) - min(column.bottom, component.bottom)
                val allowedGap = maxOf(4f, minWidth * 1.1f)
                overlap.toFloat() / minWidth > 0.5f && gap <= allowedGap
            }
            if (target == null) columns.add(Rect(component)) else target.union(component)
        }
        return columns
    }
}
