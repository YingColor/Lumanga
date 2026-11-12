package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * bluolightning/manga-ocr-mobile — a 21 MB vision-encoder / autoregressive-decoder pair.
 *
 * Unlike the column recognisers, this one reads a whole bubble at once and emits one complete
 * Japanese sentence, so it handles column order, furigana and ellipses itself. The existing
 * detector still supplies the boxes and the grouping; this only replaces the text of each group.
 *
 * The TFLite decoder exposes two signatures (`init` and `step`). The technical docs publish
 * tensor shapes but not the decoding loop: the KV slot written by a step is `position - 1`,
 * the first step starts at position 2, and the stop token is 3. Writing to the wrong slot
 * makes the model repeat characters and lose the end of a sentence.
 */
private const val MANGA_REV = "1"
private const val MANGA_ENCODER = "manga_encoder.tflite"
private const val MANGA_DECODER = "manga_decoder.tflite"
private const val MANGA_VOCAB = "manga_vocab.txt"
private val MANGA_FILES = listOf(MANGA_ENCODER, MANGA_DECODER, MANGA_VOCAB)

private const val IMG = 224
private const val PLANE = IMG * IMG
private const val VOCAB_SIZE = 9415
private const val SEQ = 196
private const val HIDDEN = 256
private const val LAYERS = 4
private const val HEADS = 4
private const val HEAD_DIM = 64
private const val CACHE_LEN = 256
private const val SELF_ELEMS = LAYERS * HEADS * HEAD_DIM
private const val CROSS_ELEMS = LAYERS * HEADS * SEQ * HEAD_DIM
private const val CACHE_ELEMS = LAYERS * HEADS * CACHE_LEN * HEAD_DIM
private const val MAX_TOKENS = 48
private const val MARGIN = 12
private const val BOS = 2L
private const val EOS = 3L
private val MANGA_SPECIAL = setOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

object MangaOcr {
    @Volatile
    private var app: Context? = null

    @Volatile
    private var runtime: MangaRuntime? = null
    private val lock = Any()

    fun init(context: Context) {
        app = context.applicationContext
    }

    /**
     * Returns null when another OCR model is selected or anything fails. The caller then keeps
     * the original column-joined text, so a broken new model can never blank out a page.
     */
    fun readIfSelected(bitmap: Bitmap?, box: Rect): String? {
        val context = app ?: return null
        if (bitmap == null) return null
        if (OcrModel.from(Prefs(context).ocrModel) != OcrModel.MANGA) return null
        val raw = runCatching {
            synchronized(lock) {
                val engine = runtime ?: MangaRuntime(copyFiles(context)).also { runtime = it }
                engine.read(bitmap, box)
            }
        }.getOrElse {
            android.util.Log.w("Ocr", "manga-ocr failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
        return raw.takeUnless { it.isNullOrBlank() }
    }

    /** Assets are copied once; the stamp makes an app update replace a changed model. */
    private fun copyFiles(context: Context): File {
        val dir = File(context.filesDir, "mangaocr")
        if (!dir.exists()) dir.mkdirs()
        val stamp = File(dir, ".bundle")
        val unchanged = runCatching { stamp.readText() }.getOrNull() == MANGA_REV
        for (name in MANGA_FILES) {
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
        stamp.writeText(MANGA_REV)
        return dir
    }
}

private fun floats(elements: Int): ByteBuffer =
    ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())

/** White 224x224 canvas, aspect preserved and centred, RGB in [0,1], NCHW float32. */
private fun mangaInput(bitmap: Bitmap, box: Rect): FloatArray? {
    val left = max(0, box.left - MARGIN)
    val top = max(0, box.top - MARGIN)
    val right = min(bitmap.width, box.right + MARGIN)
    val bottom = min(bitmap.height, box.bottom + MARGIN)
    if (right <= left || bottom <= top) return null
    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    val scale = min(IMG.toFloat() / crop.width, IMG.toFloat() / crop.height)
    val w = max(1, (crop.width * scale).toInt())
    val h = max(1, (crop.height * scale).toInt())
    val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
    val canvas = Bitmap.createBitmap(IMG, IMG, Bitmap.Config.ARGB_8888)
    canvas.eraseColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    Canvas(canvas).drawBitmap(scaled, (IMG - w) / 2f, (IMG - h) / 2f, paint)
    val pixels = IntArray(PLANE)
    canvas.getPixels(pixels, 0, IMG, 0, 0, IMG, IMG)
    val out = FloatArray(3 * PLANE)
    for (i in 0 until PLANE) {
        val p = pixels[i]
        out[i] = ((p shr 16) and 0xFF) / 255f
        out[PLANE + i] = ((p shr 8) and 0xFF) / 255f
        out[2 * PLANE + i] = (p and 0xFF) / 255f
    }
    crop.recycle()
    if (scaled !== crop) scaled.recycle()
    canvas.recycle()
    return out
}

private class MangaRuntime(dir: File) {
    private val encoder = Interpreter(
        File(dir, MANGA_ENCODER), Interpreter.Options().setNumThreads(2).setUseXNNPACK(false)
    )
    private val decoder = Interpreter(
        File(dir, MANGA_DECODER), Interpreter.Options().setNumThreads(2).setUseXNNPACK(false)
    )
    private val vocab = File(dir, MANGA_VOCAB).readLines()

    private val encInput = floats(3 * PLANE)
    private val encOutput = floats(SEQ * HIDDEN)
    private val hidden = floats(SEQ * HIDDEN)
    private val logits = floats(VOCAB_SIZE)
    private val cacheK = floats(CACHE_ELEMS)
    private val cacheV = floats(CACHE_ELEMS)
    private val crossK = floats(CROSS_ELEMS)
    private val crossV = floats(CROSS_ELEMS)
    private val selfK = floats(SELF_ELEMS)
    private val selfV = floats(SELF_ELEMS)
    private val nextK = floats(SELF_ELEMS)
    private val nextV = floats(SELF_ELEMS)
    private val scratchK = FloatArray(SELF_ELEMS)
    private val scratchV = FloatArray(SELF_ELEMS)

    fun read(bitmap: Bitmap, box: Rect): String {
        val input = mangaInput(bitmap, box) ?: return ""
        encInput.rewind()
        encInput.asFloatBuffer().put(input)
        encInput.rewind()
        encOutput.rewind()
        encoder.runSignature(
            mapOf<String, Any>("args_0" to encInput),
            hashMapOf<String, Any>("output_0" to encOutput),
            "serving_default"
        )
        encOutput.rewind()
        val hiddenValues = FloatArray(SEQ * HIDDEN)
        encOutput.asFloatBuffer().get(hiddenValues)
        hidden.rewind()
        hidden.asFloatBuffer().put(hiddenValues)
        hidden.rewind()

        logits.rewind()
        selfK.rewind()
        selfV.rewind()
        crossK.rewind()
        crossV.rewind()
        decoder.runSignature(
            mapOf<String, Any>("args_0" to hidden, "args_1" to longArrayOf(BOS)),
            hashMapOf<String, Any>(
                "output_0" to logits,
                "output_1" to selfK,
                "output_2" to selfV,
                "output_3" to crossK,
                "output_4" to crossV
            ),
            "init"
        )
        // Keep authoritative copies. TFLite may reuse an input buffer as scratch space; a
        // mutated cross-attention cache makes the sentence drift after the first few tokens.
        selfK.rewind()
        selfV.rewind()
        val seedK = FloatArray(SELF_ELEMS)
        val seedV = FloatArray(SELF_ELEMS)
        selfK.asFloatBuffer().get(seedK)
        selfV.asFloatBuffer().get(seedV)
        val cacheKArr = FloatArray(CACHE_ELEMS)
        val cacheVArr = FloatArray(CACHE_ELEMS)
        scatter(seedK, cacheKArr, 0)
        scatter(seedV, cacheVArr, 0)
        crossK.rewind()
        crossV.rewind()
        val crossKArr = FloatArray(CROSS_ELEMS)
        val crossVArr = FloatArray(CROSS_ELEMS)
        crossK.asFloatBuffer().get(crossKArr)
        crossV.asFloatBuffer().get(crossVArr)

        var next = best(logits)
        var pos = 2
        val text = StringBuilder()
        while (next != EOS && next != 0L && pos < CACHE_LEN && text.length < MAX_TOKENS) {
            if (next in 0 until vocab.size) {
                val piece = vocab[next.toInt()]
                if (!piece.startsWith("<unused") && piece !in MANGA_SPECIAL) text.append(piece)
            }
            hidden.rewind()
            hidden.asFloatBuffer().put(hiddenValues)
            hidden.rewind()
            cacheK.rewind()
            cacheK.asFloatBuffer().put(cacheKArr)
            cacheK.rewind()
            cacheV.rewind()
            cacheV.asFloatBuffer().put(cacheVArr)
            cacheV.rewind()
            crossK.rewind()
            crossK.asFloatBuffer().put(crossKArr)
            crossK.rewind()
            crossV.rewind()
            crossV.asFloatBuffer().put(crossVArr)
            crossV.rewind()
            logits.rewind()
            nextK.rewind()
            nextV.rewind()
            decoder.runSignature(
                mapOf<String, Any>(
                    "args_0" to hidden,
                    "args_1" to longArrayOf(next),
                    "args_2" to longArrayOf(pos.toLong()),
                    "args_3" to cacheK,
                    "args_4" to cacheV,
                    "args_5" to crossK,
                    "args_6" to crossV
                ),
                hashMapOf<String, Any>(
                    "output_0" to logits,
                    "output_1" to nextK,
                    "output_2" to nextV
                ),
                "step"
            )
            nextK.rewind()
            nextV.rewind()
            nextK.asFloatBuffer().get(scratchK)
            nextV.asFloatBuffer().get(scratchV)
            scatter(scratchK, cacheKArr, pos - 1)
            scatter(scratchV, cacheVArr, pos - 1)
            next = best(logits)
            pos++
        }
        return text.toString()
            .replace("##", "")
            .replace(Regex("[―─–—]{2,}$"), "")
            .trim()
    }

    /**
     * The cache is [layers, 1, heads, length, headDim], while one decoder step yields
     * [layers, 1, heads, 1, headDim]. Each (layer, head) slab therefore gets its 64 values at
     * `slot * headDim` inside its own `length * headDim` block — not as one contiguous run.
     */
    private fun scatter(src: FloatArray, dst: FloatArray, slot: Int) {
        for (block in 0 until LAYERS * HEADS) {
            val from = block * HEAD_DIM
            val to = block * CACHE_LEN * HEAD_DIM + slot * HEAD_DIM
            src.copyInto(dst, to, from, from + HEAD_DIM)
        }
    }

    private fun best(buffer: ByteBuffer): Long {
        // runSignature leaves the output buffer's position at the end; a FloatBuffer view
        // created from that state has limit 0 and get(0) throws.
        buffer.rewind()
        val view = buffer.asFloatBuffer()
        var index = 0
        var value = Float.NEGATIVE_INFINITY
        for (i in 0 until VOCAB_SIZE) {
            val v = view.get(i)
            if (v > value) {
                value = v
                index = i
            }
        }
        return index.toLong()
    }

    fun close() {
        encoder.close()
        decoder.close()
    }
}
