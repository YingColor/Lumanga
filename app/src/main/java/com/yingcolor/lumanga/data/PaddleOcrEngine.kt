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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Upper bound on OCR runtimes pre-translation may hold. Three pages at once already saturates the
 * big cores; more only adds thread contention and memory (21 MB of recogniser per runtime).
 */
private const val MAX_PARALLEL_OCR = 3

/** Widest recogniser grid any bundled model accepts; buffers are sized for it once. */
private const val MAX_BUCKET = 960

/** A refill below this confidence is artwork the extra model guessed at, not recovered text. */
private const val REFILL_MIN_CONF = 0.5f

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
    private val MODEL_BUNDLE_REV = 3
    private val prefs = Prefs(context.applicationContext)
    private var runtime: PaddleRuntime? = null
    private var runtimeModel: OcrModel? = null
    /**
     * One page at a time, but a *cancellable* queue. A monitor (`synchronized`) cannot be
     * interrupted: a page the user has already scrolled past still waited for the lock, ran the
     * whole recognition, and only then noticed it was cancelled — which is what made a fast page
     * turn queued behind stale pages until the current one hit its budget.
     */
    private val gate = Mutex()

    /**
     * Runtimes lent to pre-translation so several pages are read at once; null means the reader's
     * single serialised runtime is in use. [preparePool] builds it for one run, [releasePool]
     * closes it afterwards — the models themselves are not touched.
     */
    private class ParallelPool(
        val model: OcrModel,
        val channel: Channel<PaddleRuntime>,
        val runtimes: List<PaddleRuntime>
    )

    @Volatile
    private var parallelPool: ParallelPool? = null

    /**
     * Builds [size] independent runtimes for one pre-translation run. A size of 1 (or a model that
     * cannot use more, see [OcrModel.supportsParallel]) allocates nothing and leaves the run on
     * the reader's serialised runtime, so the default behaviour is unchanged.
     */
    fun preparePool(model: OcrModel, size: Int) {
        releasePool()
        val count = if (model.supportsParallel) size.coerceIn(1, MAX_PARALLEL_OCR) else 1
        if (count <= 1) return
        val dir = copyModels(context, model)
        val runtimes = ArrayList<PaddleRuntime>(count)
        repeat(count) { runtimes.add(PaddleRuntime(dir, model)) }
        val channel = Channel<PaddleRuntime>(count)
        runtimes.forEach { channel.trySend(it) }
        parallelPool = ParallelPool(model, channel, runtimes)
        android.util.Log.i("Ocr", "pool ready model=${model.label} size=$count")
    }

    /** Closes every pooled runtime. Called when the run ends; a no-op when nothing was pooled. */
    fun releasePool() {
        val pool = parallelPool ?: return
        parallelPool = null
        pool.runtimes.forEach { runCatching { it.close() } }
        android.util.Log.i("Ocr", "pool released size=${pool.runtimes.size}")
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        parallel: Boolean,
        token: ApiClient.CancelToken?
    ): List<TextRegion> = withContext(Dispatchers.IO) {
        // Local runs are cancelled by the coroutine itself (ensureActive below), so the token is
        // only used by the online engine.
        // Only pre-translation asks for a pooled runtime, so a release can never pull one out
        // from under the reader. Both paths run the same models and filters.
        val pool = parallelPool
        if (parallel && pool != null && pool.model == OcrModel.from(prefs.ocrModel)) {
            val worker = pool.channel.receive()
            try {
                refillBlanks(bitmap, worker.recognize(bitmap))
            } finally {
                pool.channel.trySend(worker)
            }
        } else {
            gate.withLock {
                // Cancelled while waiting? Leave without touching the models: this page is gone.
                currentCoroutineContext().ensureActive()
                // Re-read on every page so switching the model in 翻译配置 takes effect without
                // restarting the app; the previous sessions are released before the new ones load.
                val model = OcrModel.from(prefs.ocrModel)
                if (runtime == null || runtimeModel != model) {
                    android.util.Log.i("Ocr", "loading ${model.label}")
                    runtime?.close()
                    runtime = PaddleRuntime(copyModels(context, model), model)
                    runtimeModel = model
                }
                refillBlanks(bitmap, runtime!!.recognize(bitmap))
            }
        }
    }

    /**
     * Recogniser that re-reads a box the chosen model returned nothing for.
     *
     * SMALL decodes white outlined lettering on a dark drawing as nothing — CG p.10 lost
     * 「そして、」 that way — while MEDIUM reads that same crop at 0.9. Only the recogniser is
     * copied and loaded, and only once a page actually has such a box.
     */
    @Volatile
    private var refillReader: PaddleRuntime? = null

    @Synchronized
    private fun refillReader(): PaddleRuntime {
        refillReader?.let { return it }
        val dir = copyAssets(listOf(OcrModel.MEDIUM.rec), ".refill")
        android.util.Log.i("Ocr", "refill ready")
        return PaddleRuntime(dir, OcrModel.MEDIUM, recOnly = true).also { refillReader = it }
    }

    /**
     * Gives every empty box a second read with the medium recogniser.
     *
     * The box, not the page, is the unit: nothing missing means no inference at all. Picking
     * MEDIUM as the reader, or turning the switch off, leaves this a no-op.
     */
    private fun refillBlanks(bitmap: Bitmap, regions: List<TextRegion>): List<TextRegion> {
        if (!prefs.ocrBlankRefill) return regions
        if (OcrModel.from(prefs.ocrModel) == OcrModel.MEDIUM) return regions
        if (regions.none { it.text.isBlank() }) return regions
        val reader = refillReader()
        return regions.map { region ->
            if (region.text.isNotBlank()) return@map region
            // A refill is a bonus, never a reason to lose the page: if the extra model cannot
            // run, the box stays empty and the cover still hides it.
            val again = runCatching { reader.readBox(bitmap, region.box) }.getOrNull()
            // A single glyph read off artwork is not text worth translating, and accepting it
            // would also cost the box its cover (it would stop counting as unread). Keep it blank.
            if (again == null || again.text.length < 2 || again.confidence < REFILL_MIN_CONF) {
                return@map region
            }
            android.util.Log.i("Ocr", "OCR refill ${region.box} -> ${again.text}")
            region.copy(text = again.text, confidence = again.confidence)
        }
    }

    /**
     * Bundled models are copied out of assets once, but a plain "file exists" check means an
     * app update never replaces a stale model. The stamp ties the cache to both the model and
     * the bundle revision, so a changed model is re-copied on the next run.
     */
    private fun copyModels(context: Context, model: OcrModel): File =
        copyAssets(model.files, ".bundle_${model.id}")

    /** Copies [names] out of assets once; [stampName] records the bundle revision that did it. */
    private fun copyAssets(names: List<String>, stampName: String): File {
        val dir = File(context.filesDir, "ocr")
        if (!dir.exists()) dir.mkdirs()
        val stamp = File(dir, stampName)
        val expected = "$MODEL_BUNDLE_REV"
        val unchanged = runCatching { stamp.readText() }.getOrNull() == expected
        for (name in names) {
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
private class OnnxSession(
    path: File,
    private val shape: LongArray,
    inputElements: Int,
    /** True when the last dimension may change per call (the recogniser), false for the detector. */
    private val dynamicWidth: Boolean = false
) : OcrSession {
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
        // A padded recogniser input is not neutral: the model reads the trailing zeros as
        // ink-free paper and they steal the attention a short column needs. Feeding the exact
        // content width is what turns 「そって」 back into 「そして」 (CG p.10, three glyphs).
        // The width therefore comes from the buffer size, and the buffer is limited to it —
        // ONNX rejects a buffer larger than the shape it is told to expect.
        val width = if (dynamicWidth) values.size / (3 * 48) else shape[3].toInt()
        val inputBuffer = inputBuffers.get()!!
        inputBuffer.clear()
        inputBuffer.limit(values.size.coerceAtMost(3 * 48 * width))
        inputBuffer.put(values)
        inputBuffer.rewind()
        val actual = if (dynamicWidth) longArrayOf(1, 3, 48, width.toLong()) else shape
        val tensor = OnnxTensor.createTensor(env, inputBuffer, actual)
        val result = session.run(Collections.singletonMap(inputName, tensor))
        try {
            val buffer = (result.get(0) as OnnxTensor).floatBuffer
            buffer.rewind()
            buffer.get(out)
        } finally {
            result.close()
            tensor.close()
            inputBuffer.clear()
        }
    }

    override fun close() {
        session.close()
    }
}

private class PaddleRuntime(
    private val dir: File,
    private val model: OcrModel,
    /** True for the refill reader: it only ever reads single boxes, never detects a page. */
    private val recOnly: Boolean = false
) {
    /** Longest tile edge fed to the 640 detector; keeps text near its native size. */
    private val TILE_TARGET = 860

    /** Margin added to a detector box before recognition; see the retry note in recognize(). */
    private val TIGHT_PAD = 3
    private val WIDE_PAD = 10

    /** Thresholds used by the reference project's PaddleTextLineDetector. */
    private val DET_PROB_THRESHOLD = 0.2f
    private val DET_BOX_THRESHOLD = 0.45f
    private val DET_UNCLIP = 1.4f

    /**
     * Share of the page a balloon must cover before it gets its own detection pass.
     *
     * This used to be 3%, which skipped small balloons: the page-wide detector boxed only 「は」 of
     * 「はい」 on CG p.6 and the translator turned that orphan into 「哈」. The floor is lowered
     * until only thumbnails are skipped; the detector runs on a tight crop, so the cost is small.
     */
    private val BALLOON_REDETECT_SHARE = 0.006f

    /** Below this confidence the column is read again with the wider margin. */
    private val WIDE_RETRY_CONF = 0.85f
    private val detFile = File(dir, model.det)
    private val recFile = File(dir, model.rec)

    /**
     * Recogniser input widths this model ships. The recogniser is trained at one fixed width and
     * the reference project bundles 320/640/960: a 299 px strip sent to the 640 model spends its
     * output grid on padding and 「ふふふ」 came back as 「…」, while the 320 model reads it.
     */
    private val recBuckets: List<Int> =
        if (model.recBuckets.isEmpty()) listOf(640) else model.recBuckets
    private val vocabulary: List<String> =
        JSONArray(File(dir, "characters.json").readText(Charsets.UTF_8))
            .let { array -> List(array.length()) { array.getString(it) } }

    // SMALL and MEDIUM share tensor shapes, so detector and recogniser can come from different
    // bundles (see OcrModel.HYBRID); each half picks its own backend.
    private val detShared: OnnxSession? =
        if (!recOnly && model.detOnnx) OnnxSession(detFile, longArrayOf(1, 3, 640, 640), 3 * 640 * 640)
        else null
    private val recShared: OnnxSession? =
        if (model.recOnnx) OnnxSession(recFile, longArrayOf(1, 3, 48, 640), 3 * 48 * MAX_BUCKET, true)
        else null
    private val owned = Collections.synchronizedList(mutableListOf<OcrSession>())

    /**
     * One inference per column, and columns are independent, so two workers run instead of one
     * after another. Interpreters are not thread-safe, so each worker gets its own copy.
     */
    private val recWorkers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "ocr-rec").apply { isDaemon = true }
    }
    private val recLocal = ThreadLocal.withInitial {
        RecWorker(
            fileFor = { bucket ->
                val named = File(dir, "ppocrv6_small_rec_${bucket}_fp32.tflite")
                if (named.exists() && named.length() > 0) named else recFile
            },
            shared = recShared,
            buckets = recBuckets,
            owned = owned
        )
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

    /**
     * One recogniser per worker thread per input width. Widths share nothing but the vocabulary,
     * so each slot owns its interpreter and buffers, and they are created lazily: a page of short
     * columns only ever touches the 320 slot.
     */
    private class RecWorker(
        private val fileFor: (Int) -> File,
        private val shared: OcrSession?,
        private val buckets: List<Int>,
        private val owned: MutableList<OcrSession>
    ) {
        class Slot(val session: OcrSession, val input: FloatArray, val output: FloatArray) {
            /** Model input width this slot was built for. */
            val width: Int get() = input.size / (3 * 48)
        }

        private val slots = HashMap<Int, Slot>(buckets.size)

        fun slot(bucket: Int): Slot = slots.getOrPut(bucket) {
            val session = shared
                ?: TfLiteSession(fileFor(bucket), 3 * 48 * bucket, (bucket / 8) * 18710)
                    .also { owned.add(it) }
            Slot(session, FloatArray(3 * 48 * bucket), FloatArray((bucket / 8) * 18710))
        }
    }

    fun close() {
        detShared?.close()
        recShared?.close()
        owned.forEach { runCatching { it.close() } }
        detWorkers.shutdownNow()
        recWorkers.shutdownNow()
    }

    /**
     * Recognises a page the way the reference implementation does: find the balloons first, then
     * re-run detection *inside each balloon* and read it line by line. A page-wide pass alone
     * merges a shout bubble's four columns into one box, which is what produced 「CNO的」.
     *
     * Balloon-free pages (and pages where the segmentation model is unavailable) fall back to
     * the previous page-wide pass.
     */
    fun recognize(bitmap: Bitmap): List<TextRegion> {
        val started = android.os.SystemClock.elapsedRealtime()
        val balloons = BubbleSegmenter.detect(bitmap)
        // One page-wide pass, shared by both paths. Re-running the detector inside *every*
        // balloon cost a full detector run each: a 14-balloon page spent 21 s on detection alone,
        // blew the OCR budget and the page kept its Japanese. The local pass is therefore kept
        // for the balloons where the page pass demonstrably fails, i.e. the big ones whose
        // columns get merged into one box.
        val page = recognizePage(bitmap, started)
        if (balloons.isEmpty() || bitmap.width < 200 || bitmap.height < 200) return page

        val pageArea = bitmap.width.toFloat() * bitmap.height
        val refill = balloons.filter { balloon ->
            val box = balloon.box
            box.width() >= 40 && box.height() >= 40 &&
                box.width().toFloat() * box.height() / pageArea >= BALLOON_REDETECT_SHARE
        }
        if (refill.isEmpty()) {
            android.util.Log.i(
                "Ocr", "OCR balloons=${balloons.size} all small, page pass only " +
                    "ms=${android.os.SystemClock.elapsedRealtime() - started}"
            )
            return page
        }
        val inside = ArrayList<TextRegion>()
        for (balloon in refill) {
            val box = balloon.box
            val pad = (minOf(box.width(), box.height()) * 0.06f).toInt().coerceIn(4, 24)
            val left = (box.left - pad).coerceAtLeast(0)
            val top = (box.top - pad).coerceAtLeast(0)
            val right = (box.right + pad).coerceAtMost(bitmap.width)
            val bottom = (box.bottom + pad).coerceAtMost(bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            try {
                // Whole-balloon detection, exactly like the reference implementation. Tiling a
                // crop this small only cut its lettering at tile boundaries. The line detector
                // can still return two columns as one box, so the projection split runs here too.
                val boxes = mergeIntoColumns(
                    splitWideColumns(crop, detectLinesWhole(crop))
                )
                recognizeBoxes(crop, boxes).forEach { region ->
                    inside.add(
                        TextRegion(
                            region.text,
                            Rect(
                                region.box.left + left, region.box.top + top,
                                region.box.right + left, region.box.bottom + top
                            ),
                            region.confidence,
                            region.vertical
                        )
                    )
                }
            } finally {
                crop.recycle()
            }
        }
        // Two kinds of duplicate appear here and both of them poison the translation:
        //  * inside itself. The balloon detector still boxes a short line twice - on CG p.6 it
        //    returned 「はい」 and a separate 「は」 for the same line, and the group reached the
        //    translator as 「はい。 は」.
        //  * a page region the balloon pass already re-read, which came back as
        //    「凉香 涼香さんが…喜んでくれるかなって…」 on the same page.
        // Keep the richer reading: a region mostly covered by one that already won is dropped.
        val deduped = ArrayList<TextRegion>()
        for (region in inside.sortedWith(
            compareByDescending<TextRegion> { it.text.count { c -> !c.isWhitespace() } }
                .thenByDescending { it.confidence }
        )) {
            if (deduped.none { coveredBy(it.box, region.box) }) deduped.add(region)
        }
        // Everything outside the re-detected balloons keeps the page result, small balloons
        // included: their columns are short enough that the page pass never merged them.
        val outside = page.filter { region ->
            val area = region.box.width().toFloat() * region.box.height()
            refill.none { balloon ->
                area > 0f && overlapArea(balloon.box, region.box) / area >= 0.5f
            }
        }
        val result = deduped + outside
        android.util.Log.i(
            "Ocr",
            "OCR balloons=${balloons.size} refill=${refill.size} inside=${inside.size} " +
                "dedup=${deduped.size} outside=${outside.size} " +
                "ms=${android.os.SystemClock.elapsedRealtime() - started}"
        )
        return result
    }

    private fun recognizePage(bitmap: Bitmap, started: Long): List<TextRegion> {
        val detected = detectColumns(bitmap)
        // A big shout bubble is often returned as one box spanning all of its vertical columns.
        // Recognition then reads the columns as a single horizontal run — the CG page produced
        // 「CNO的」out of 「もう来てるのか」that way. Split such a box into one box per column
        // before recognising, exactly as the reference implementation re-runs line detection
        // inside each balloon.
        val boxes = splitWideColumns(bitmap, detected)
        if (boxes.size != detected.size) {
            android.util.Log.i("Ocr", "OCR split boxes ${detected.size}->${boxes.size}")
        }
        val detectMs = android.os.SystemClock.elapsedRealtime() - started
        if (boxes.isEmpty()) return emptyList()
        val regions = recognizeBoxes(bitmap, boxes)
        val recogniseMs = android.os.SystemClock.elapsedRealtime() - started - detectMs
        android.util.Log.i("Ocr", "OCR det=${detectMs}ms rec=${recogniseMs}ms boxes=${boxes.size}")
        return regions
    }

    /** Reads one box with this runtime's recogniser; the refill path calls this. */
    fun readBox(bitmap: Bitmap, box: Rect): TextRegion? =
        recognizeBoxes(bitmap, listOf(box)).firstOrNull()

    /** Recognises the given boxes on one bitmap. Shared by the page pass and the per-balloon pass. */
    private fun recognizeBoxes(bitmap: Bitmap, boxes: List<Rect>): List<TextRegion> {
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
                    val outcome = recognizeHorizontal(piece, worker)
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

        val regions = ArrayList<TextRegion>(boxes.size)
        boxes.forEachIndexed { index, column ->
            val result = texts[index]
            val box = rects[index]
            if (result == null || box == null) {
                android.util.Log.d("Ocr", "OCR blank $column")
                return@forEachIndexed
            }
            // An unread box is kept with empty text: the grouping pass turns it into cover area,
            // because the ink is still on the page even though the recogniser gave up on it.
            regions.add(TextRegion(result.first, box, result.second, verticals[index]))
        }
        return regions.sortedWith(
            compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top }
        )
    }

    /**
     * Splits boxes that swallowed several vertical columns into one box per column.
     *
     * The detector sometimes returns a wide box whose four columns are read as one line. A
     * column is a band of ink along x with a quiet gap between neighbours, so a vertical
     * projection finds the boundaries without any model. Horizontal lines are left alone.
     */
    private fun splitWideColumns(bitmap: Bitmap, boxes: List<Rect>): List<Rect> {
        if (boxes.isEmpty()) return boxes
        val widths = boxes.map { it.width() }.sorted()
        val unit = widths[widths.size / 2].coerceIn(10, 96)
        val out = ArrayList<Rect>(boxes.size + 4)
        for (box in boxes) {
            val vertical = box.height() >= box.width() * 1.2f
            // Any vertical box is projected: a width ratio cannot tell two columns apart,
            // because the median width itself moves with the page. A single column produces one
            // run and is left untouched.
            if (!vertical || box.width() < 40) {
                out.add(box)
                continue
            }
            val columns = columnRuns(bitmap, box, unit)
            if (columns.size >= 2) out.addAll(columns) else out.add(box)
        }
        return out
    }

    private fun columnRuns(bitmap: Bitmap, box: Rect, unit: Int): List<Rect> {
        val w = box.width()
        val h = box.height()
        if (w < 8 || h < 8) return emptyList()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val ink = IntArray(w)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val pixel = pixels[row + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val saturation = maxOf(r, g, b) - minOf(r, g, b)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum < 200 || saturation > 45) ink[x]++
            }
        }
        val minInk = (h * 0.03f).toInt().coerceAtLeast(2)
        val runs = ArrayList<IntArray>()
        var start = -1
        for (x in 0..w) {
            val on = x < w && ink[x] >= minInk
            if (on && start < 0) {
                start = x
            } else if (!on && start >= 0) {
                runs.add(intArrayOf(start, x))
                start = -1
            }
        }
        if (runs.size < 2) return emptyList()
        val merged = ArrayList<IntArray>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run[0] - last[1] < unit * 0.12f) {
                last[1] = run[1]
            } else {
                merged.add(intArrayOf(run[0], run[1]))
            }
        }
        if (merged.size < 2) return emptyList()
        return merged
            .map { Rect(box.left + it[0], box.top, box.left + it[1], box.bottom) }
            .filter { it.width() >= 6 }
    }

    /**
     * Line detection copied from manga-translator-android's PaddleTextLineDetector.
     *
     * Differences from [detectColumns], and the reason this exists: no tiling, black padding,
     * eight-connected components, mean-probability gate 0.45, and an unclip margin proportional
     * to the box. Its own balloon crops go through this path, so a shout bubble is detected
     * whole instead of being cut by a tile boundary — the tiled path returned only fragments of
     * CG p.8's green lettering.
     */
    private fun detectLinesWhole(bitmap: Bitmap): List<Rect> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 32 || h < 32) return emptyList()
        val side = 640
        val scale = min(side.toFloat() / w, side.toFloat() / h).coerceAtLeast(1e-6f)
        val drawW = max(1, (w * scale).toInt())
        val drawH = max(1, (h * scale).toInt())
        val padX = (side - drawW) / 2f
        val padY = (side - drawH) / 2f
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, w, h),
            Rect(padX.toInt(), padY.toInt(), padX.toInt() + drawW, padY.toInt() + drawH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        val plane = side * side
        val worker = detLocal.get() ?: run {
            square.recycle()
            return emptyList()
        }
        val pixels = worker.pixels
        square.getPixels(pixels, 0, side, 0, 0, side, side)
        square.recycle()
        val mean = floatArrayOf(0.485f * 255f, 0.456f * 255f, 0.406f * 255f)
        val std = floatArrayOf(0.229f * 255f, 0.224f * 255f, 0.225f * 255f)
        for (i in 0 until plane) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            worker.input[2 * plane + i] = (r - mean[2]) / std[2]
            worker.input[plane + i] = (g - mean[1]) / std[1]
            worker.input[i] = (b - mean[0]) / std[0]
        }
        worker.session.run(worker.input, worker.output)
        val probabilities = worker.output
        val visited = BooleanArray(plane)
        val stack = IntArray(plane)
        val boxes = ArrayList<Rect>(32)
        for (start in 0 until plane) {
            if (visited[start] || probabilities[start] <= DET_PROB_THRESHOLD) continue
            var minX = side
            var minY = side
            var maxX = 0
            var maxY = 0
            var count = 0
            var sum = 0f
            var top = 0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val index = stack[--top]
                val x = index % side
                val y = index / side
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                count++
                sum += probabilities[index]
                for (ny in y - 1..y + 1) {
                    if (ny < 0 || ny >= side) continue
                    val row = ny * side
                    for (nx in x - 1..x + 1) {
                        if (nx < 0 || nx >= side) continue
                        val n = row + nx
                        if (!visited[n] && probabilities[n] > DET_PROB_THRESHOLD) {
                            visited[n] = true
                            stack[top++] = n
                        }
                    }
                }
            }
            if (count < 3) continue
            if (sum / count < DET_BOX_THRESHOLD) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < 3 || boxH < 3) continue
            val distance = (boxW * boxH) / (2f * (boxW + boxH)) * DET_UNCLIP
            val left = ((minX - distance - padX) / scale).coerceIn(0f, w.toFloat())
            val topY = ((minY - distance - padY) / scale).coerceIn(0f, h.toFloat())
            val right = ((maxX + 1 + distance - padX) / scale).coerceIn(0f, w.toFloat())
            val bottom = ((maxY + 1 + distance - padY) / scale).coerceIn(0f, h.toFloat())
            if (right - left <= 3f || bottom - topY <= 3f) continue
            boxes.add(
                Rect(
                    left.toInt().coerceIn(0, w - 1),
                    topY.toInt().coerceIn(0, h - 1),
                    right.toInt().coerceIn(1, w),
                    bottom.toInt().coerceIn(1, h)
                )
            )
        }
        return boxes.sortedWith(compareByDescending<Rect> { it.centerX() }.thenBy { it.top })
    }

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

    private fun recognizeHorizontal(crop: Bitmap, worker: RecWorker): Pair<String, Float> {
        val required = max(1, ceil(48.0 * crop.width / crop.height.toDouble()).toInt())
        // Same bucket rule as the reference implementation: the shortest grid that still fits the
        // content, so a four-glyph column is not padded out to 640.
        val bucket = when {
            required <= 320 -> 320
            required <= 640 -> 640
            else -> 960
        }
        val slot = worker.slot(bucket)
        val targetW = slot.width
        val resizedW = min(required, targetW)
        val scaled = Bitmap.createScaledBitmap(crop, resizedW, 48, true)
        val pixels = IntArray(resizedW * 48)
        scaled.getPixels(pixels, 0, resizedW, 0, 0, resizedW, 48)
        scaled.recycle()

        // Same buffer every call: the trailing padding has to be cleared explicitly now that the
        // array is no longer freshly allocated.
        val input = slot.input
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

        slot.session.run(slot.input, slot.output)
        val output = slot.output
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
    /** Area two boxes share, 0 when they do not intersect. */
    private fun overlapArea(a: Rect, b: Rect): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w <= 0 || h <= 0) 0f else w.toFloat() * h.toFloat()
    }

    /** True when [outer] covers most of [inner], i.e. [inner] is the same lettering read twice. */
    private fun coveredBy(outer: Rect, inner: Rect): Boolean {
        val own = inner.width().toFloat() * inner.height()
        if (own <= 0f) return false
        return overlapArea(outer, inner) / own >= 0.6f
    }

    private fun mergeIntoColumns(components: List<Rect>): List<Rect> {
        val columns = ArrayList<Rect>()
        for (component in components.sortedBy { it.centerX() }) {
            val target = columns.firstOrNull { column ->
                val overlap = min(column.right, component.right) - max(column.left, component.left)
                val minWidth = min(column.width(), component.width()).coerceAtLeast(1)
                val gap = maxOf(column.top, component.top) - min(column.bottom, component.bottom)
                // Measured on CG p.8: widening this to 2.2x merged the shout bubble's columns
                // and made the small model read even less (one box, 「Cるのか 一」). Keep 1.1x.
                val allowedGap = maxOf(4f, minWidth * 1.1f)
                overlap.toFloat() / minWidth > 0.5f && gap <= allowedGap
            }
            if (target == null) columns.add(Rect(component)) else target.union(component)
        }
        return columns
    }
}
