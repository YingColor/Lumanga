package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One detected balloon (or free-text block), with its outline in page pixels. */
class BubbleRegion(
    val box: Rect,
    /** Closed polygon as x0,y0,x1,y1..., page pixels. Null when the mask gave nothing. */
    val contour: FloatArray?,
    val confidence: Float,
    /**
     * True for the model's second class: a block of lettering printed on the artwork rather than
     * a balloon. It is not a shape to draw around dialogue; it is the region narration occupies.
     */
    val isText: Boolean = false
)

/**
 * Thin wrapper around the dual-label instance segmentation model (balloon / free text).
 *
 * The model comes from manga-translator-android 3.8.5 and is AGPL-3.0; see NOTICE. Only the
 * balloon class is used here: the OCR boxes are already good at finding lettering, but they say
 * nothing about the shape of the bubble behind it, which is what the cover needs.
 */
object BubbleSegmenter {
    private const val ASSET = "detect/mixed-dual-s-e5_float16.tflite"
    private const val INPUT = 1472
    private const val ANCHOR_COUNT = 44436
    private const val FEATURE_COUNT = 38
    private const val MASK_COEFFICIENTS = 32
    private const val PROTO_SIZE = 368
    private const val CLASS_BALLOON = 0
    private const val CLASS_TEXT = 1
    private const val BALLOON_CONFIDENCE = 0.32f
    private const val TEXT_CONFIDENCE = 0.16f
    private const val NMS_IOU = 0.45f

    @Volatile
    private var interpreter: Interpreter? = null

    /** Kept alive for as long as [interpreter] is: closing it would invalidate the graph. */
    @Volatile
    private var gpuDelegate: GpuDelegate? = null

    @Volatile
    private var disabled = false

    @Volatile
    private var appContext: Context? = null

    /** Called once from the app so grouping can reach the model without threading a context. */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val lock = Any()
    private var modelBuffer: ByteBuffer? = null
    private var inputBuffer: ByteBuffer? = null
    private var detectionBuffer: ByteBuffer? = null
    private var prototypeBuffer: ByteBuffer? = null
    private var detectionIndex = -1
    private var prototypeIndex = -1
    private var pixels = IntArray(INPUT * INPUT)

    private class Raw(
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float,
        val confidence: Float,
        val classId: Int,
        val coefficients: FloatArray
    )

    fun detect(bitmap: Bitmap): List<BubbleRegion> {
        val context = appContext ?: return emptyList()
        if (disabled || bitmap.width < 32 || bitmap.height < 32) return emptyList()
        val net = interpreter ?: synchronized(lock) {
            interpreter ?: runCatching { load(context) }.getOrElse {
                android.util.Log.w("BubbleSeg", "model unavailable: ${it.message}")
                disabled = true
                null
            }
        } ?: return emptyList()
        return runCatching { infer(net, bitmap) }.getOrElse {
            android.util.Log.w("BubbleSeg", "inference failed: ${it.message}")
            emptyList()
        }
    }

    private fun load(context: Context): Interpreter {
        val descriptor = context.assets.openFd(ASSET)
        val mapped = FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.channel.map(
                FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength
            )
        }
        modelBuffer = mapped
        // The fp16 model needs about 2 GB of CPU arena — on an 8 GB phone that leaves the process
        // a few megabytes of native headroom, so it gets killed at random. The GPU delegate keeps
        // the intermediates in GPU memory (the image itself is a handful of megabytes). If the
        // delegate cannot be built the plain CPU interpreter is still tried, but the balloon
        // outline is then not worth that price: disabled is set by the caller's failure path.
        val options = Interpreter.Options().setNumThreads(4).setUseXNNPACK(false)
        val net = runCatching {
            val delegate = GpuDelegate()
            gpuDelegate = delegate
            Interpreter(mapped, options.addDelegate(delegate))
        }.getOrElse { failure ->
            android.util.Log.w(
                "BubbleSeg", "GPU delegate unavailable (${failure.message}); using CPU"
            )
            gpuDelegate?.close()
            gpuDelegate = null
            Interpreter(mapped, Interpreter.Options().setNumThreads(2).setUseXNNPACK(false))
        }
        var det = -1
        var proto = -1
        for (index in 0 until net.outputTensorCount) {
            val shape = net.getOutputTensor(index).shape()
            if (shape.contentEquals(intArrayOf(1, FEATURE_COUNT, ANCHOR_COUNT))) det = index
            if (shape.contentEquals(intArrayOf(1, PROTO_SIZE, PROTO_SIZE, MASK_COEFFICIENTS))) {
                proto = index
            }
        }
        check(det >= 0 && proto >= 0) { "unexpected model outputs" }
        detectionIndex = det
        prototypeIndex = proto
        inputBuffer = direct(INPUT * INPUT * 3 * 4)
        detectionBuffer = direct(FEATURE_COUNT * ANCHOR_COUNT * 4)
        prototypeBuffer = direct(PROTO_SIZE * PROTO_SIZE * MASK_COEFFICIENTS * 4)
        interpreter = net
        return net
    }

    private fun direct(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private fun infer(net: Interpreter, bitmap: Bitmap): List<BubbleRegion> {
        val gain = min(INPUT.toFloat() / bitmap.width, INPUT.toFloat() / bitmap.height)
        val drawW = (bitmap.width * gain).toInt().coerceAtLeast(1)
        val drawH = (bitmap.height * gain).toInt().coerceAtLeast(1)
        val padX = (INPUT - drawW) / 2f
        val padY = (INPUT - drawH) / 2f

        val square = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            Rect(padX.toInt(), padY.toInt(), padX.toInt() + drawW, padY.toInt() + drawH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        square.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        square.recycle()

        val input = checkNotNull(inputBuffer)
        input.rewind()
        val floats = input.asFloatBuffer()
        val plane = INPUT * INPUT
        // NHWC, RGB in 0..1. The channel loop is split to keep the hot path branch-free.
        var rgb = 0
        for (index in 0 until plane) {
            val pixel = pixels[index]
            floats.put(rgb++, ((pixel shr 16) and 0xFF) / 255f)
            floats.put(rgb++, ((pixel shr 8) and 0xFF) / 255f)
            floats.put(rgb++, (pixel and 0xFF) / 255f)
        }

        val detBuf = checkNotNull(detectionBuffer).also { it.rewind() }
        val protoBuf = checkNotNull(prototypeBuffer).also { it.rewind() }
        net.runForMultipleInputsOutputs(
            arrayOf(input),
            mapOf(detectionIndex to detBuf, prototypeIndex to protoBuf)
        )
        detBuf.rewind()
        protoBuf.rewind()
        val detection = detBuf.asFloatBuffer()
        val prototypes = protoBuf.asFloatBuffer()

        val candidates = ArrayList<Raw>(64)
        for (anchor in 0 until ANCHOR_COUNT) {
            fun feature(channel: Int) = detection.get(channel * ANCHOR_COUNT + anchor)
            val scoreBalloon = feature(4)
            val scoreText = feature(5)
            if (!scoreBalloon.isFinite() || !scoreText.isFinite()) continue
            val classId = if (scoreBalloon >= scoreText) CLASS_BALLOON else CLASS_TEXT
            val confidence = max(scoreBalloon, scoreText)
            val floor = if (classId == CLASS_BALLOON) BALLOON_CONFIDENCE else TEXT_CONFIDENCE
            if (!confidence.isFinite() || confidence < floor || confidence > 1f) continue
            val width = feature(2) * INPUT
            val height = feature(3) * INPUT
            if (width <= 1f || height <= 1f) continue
            if (classId != CLASS_BALLOON) continue
            candidates.add(
                Raw(
                    feature(0) * INPUT, feature(1) * INPUT, width, height, confidence, classId,
                    FloatArray(MASK_COEFFICIENTS) { feature(6 + it) }
                )
            )
        }
        if (candidates.isEmpty()) return emptyList()

        val kept = ArrayList<Raw>(8)
        for (candidate in candidates.sortedByDescending { it.confidence }) {
            if (kept.none { iou(it, candidate) > NMS_IOU }) kept.add(candidate)
            if (kept.size >= 60) break
        }

        return kept.mapNotNull { raw ->
            val box = pageRect(raw, gain, padX, padY, bitmap.width, bitmap.height)
            if (box.width() < 8 || box.height() < 8) return@mapNotNull null
            val contour = contourOf(raw, prototypes, gain, padX, padY, bitmap.width, bitmap.height)
            BubbleRegion(box, contour, raw.confidence, raw.classId == CLASS_TEXT)
        }
    }

    private fun iou(a: Raw, b: Raw): Float {
        val aw = min(a.cx + a.width / 2, b.cx + b.width / 2) - max(a.cx - a.width / 2, b.cx - b.width / 2)
        val ah = min(a.cy + a.height / 2, b.cy + b.height / 2) - max(a.cy - a.height / 2, b.cy - b.height / 2)
        if (aw <= 0f || ah <= 0f) return 0f
        val inter = aw * ah
        val union = a.width * a.height + b.width * b.height - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun pageRect(
        raw: Raw, gain: Float, padX: Float, padY: Float, pageW: Int, pageH: Int
    ): Rect {
        val left = ((raw.cx - raw.width / 2f - padX) / gain).toInt().coerceIn(0, pageW - 1)
        val top = ((raw.cy - raw.height / 2f - padY) / gain).toInt().coerceIn(0, pageH - 1)
        val right = ((raw.cx + raw.width / 2f - padX) / gain).toInt().coerceIn(left + 1, pageW)
        val bottom = ((raw.cy + raw.height / 2f - padY) / gain).toInt().coerceIn(top + 1, pageH)
        return Rect(left, top, right, bottom)
    }

    /**
     * Walks the mask column by column and keeps the outer edges, producing a closed polygon in
     * page pixels. A polygon (not a rectangle) is the whole point: it lets the cover follow the
     * balloon outline instead of painting a slab over the artwork.
     */
    private fun contourOf(
        raw: Raw, prototypes: FloatBuffer, gain: Float, padX: Float, padY: Float,
        pageW: Int, pageH: Int
    ): FloatArray? {
        val x1 = floor((raw.cx - raw.width / 2f) / INPUT * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE - 1)
        val y1 = floor((raw.cy - raw.height / 2f) / INPUT * PROTO_SIZE).toInt().coerceIn(0, PROTO_SIZE - 1)
        val x2 = ceil((raw.cx + raw.width / 2f) / INPUT * PROTO_SIZE).toInt().coerceIn(x1 + 1, PROTO_SIZE)
        val y2 = ceil((raw.cy + raw.height / 2f) / INPUT * PROTO_SIZE).toInt().coerceIn(y1 + 1, PROTO_SIZE)
        val w = x2 - x1
        val h = y2 - y1
        val foreground = BooleanArray(w * h)
        for (localY in 0 until h) {
            val row = (y1 + localY) * PROTO_SIZE + x1
            for (localX in 0 until w) {
                var score = 0f
                for (c in 0 until MASK_COEFFICIENTS) {
                    score += raw.coefficients[c] *
                        prototypes.get((row + localX) * MASK_COEFFICIENTS + c)
                }
                foreground[localY * w + localX] = score > 0f
            }
        }
        val main = largestComponent(foreground, w, h) ?: return null

        val samples = h.coerceIn(4, 40)
        val left = ArrayList<Float>(samples * 2)
        val right = ArrayList<Float>(samples * 2)
        for (index in 0 until samples) {
            val fraction = if (samples == 1) 0f else index / (samples - 1f)
            val y = (y1 + ((h - 1) * fraction).toInt()).coerceIn(y1, y2 - 1)
            var leftX = -1
            var rightX = -1
            for (x in x1 until x2) {
                if (main[(y - y1) * w + (x - x1)]) {
                    if (leftX < 0) leftX = x
                    rightX = x
                }
            }
            if (leftX < 0) continue
            val lx = ((leftX.toFloat() / PROTO_SIZE * INPUT - padX) / gain).coerceIn(0f, pageW.toFloat())
            val rx = (((rightX + 1).toFloat() / PROTO_SIZE * INPUT - padX) / gain)
                .coerceIn(0f, pageW.toFloat())
            val py = ((y.toFloat() / PROTO_SIZE * INPUT - padY) / gain).coerceIn(0f, pageH.toFloat())
            left.add(lx)
            left.add(py)
            right.add(rx)
            right.add(py)
        }
        if (left.size < 6) return null
        val polygon = FloatArray(left.size + right.size)
        left.toFloatArray().copyInto(polygon, 0)
        var cursor = left.size
        for (index in right.size - 2 downTo 0 step 2) {
            polygon[cursor++] = right[index]
            polygon[cursor++] = right[index + 1]
        }
        return polygon
    }

    private fun largestComponent(mask: BooleanArray, width: Int, height: Int): BooleanArray? {
        val labels = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var next = 0
        var bestLabel = 0
        var bestSize = 0
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = next
            var size = 0
            while (head < tail) {
                val current = queue[head++]
                size++
                val x = current % width
                val y = current / width
                if (x > 0) {
                    val n = current - 1
                    if (mask[n] && labels[n] == 0) { labels[n] = next; queue[tail++] = n }
                }
                if (x + 1 < width) {
                    val n = current + 1
                    if (mask[n] && labels[n] == 0) { labels[n] = next; queue[tail++] = n }
                }
                if (y > 0) {
                    val n = current - width
                    if (mask[n] && labels[n] == 0) { labels[n] = next; queue[tail++] = n }
                }
                if (y + 1 < height) {
                    val n = current + width
                    if (mask[n] && labels[n] == 0) { labels[n] = next; queue[tail++] = n }
                }
            }
            if (size > bestSize) {
                bestSize = size
                bestLabel = next
            }
        }
        if (bestLabel == 0 || bestSize < 64) return null
        return BooleanArray(mask.size) { labels[it] == bestLabel }
    }
}
