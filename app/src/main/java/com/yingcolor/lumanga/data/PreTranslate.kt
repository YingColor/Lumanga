package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Whole-volume translation: walks the picked pages, runs the same OCR → bubble → API pipeline the
 * reader uses, and writes each result into [TranslationStore]. Nothing is rendered here, so the
 * stored data stays small and the reader can redraw it at any screen size.
 */
object PreTranslate {
    /**
     * Upper bound for the bitmap handed to OCR, not a target size.
     *
     * It used to be 1400, which made pre-translation work on a *smaller* page than the reader
     * does: decoding already returns 1240x1754 for a 2480x3509 scan, and [fit] then shrank that
     * to 989x1400. The same column was therefore read at 0.86 confidence by the reader and 0.45
     * (dropped, below the 0.45 floor) by pre-translation — which is why a bubble could come out
     * translated when read live and untouched after a batch run. Keeping the decoded size makes
     * both paths see the identical page.
     */
    private const val MAX_SIDE = 3000

    /** Three missed pages in a row means the endpoint is down, not that one page was unlucky. */
    private const val MAX_CONSECUTIVE_FAILURES = 3

    /**
     * The stop button has to reach into the blocking HTTP call, which a cancelled coroutine
     * cannot interrupt by itself. Only one run exists at a time (the single-volume panel refuses
     * to start while a batch is running), so one live token is enough.
     */
    @Volatile
    private var activeToken: ApiClient.CancelToken? = null

    /** Stops the run in progress immediately, from the UI thread. */
    fun stop() {
        activeToken?.cancel()
    }

    sealed interface Result {
        /**
         * [translated] pages written this run, [skipped] pages reused from an earlier run,
         * [failed] pages the service did not answer for. A failed page is skipped rather than
         * aborting the volume, so one hiccup does not throw away an hour of work.
         */
        data class Done(
            val translated: Int,
            val skipped: Int,
            val failed: List<Int> = emptyList()
        ) : Result
        data class Failed(val page: Int, val reason: String) : Result
        data object Cancelled : Result
    }

    /** Page count without keeping an archive open; used for progress and the "done" badge. */
    fun pageCount(context: Context, book: Book): Int = runCatching {
        PageSource.open(context, book).use { it.pageCount }
    }.getOrDefault(0)

    /**
     * [skipStored] is what makes a batch resumable: every page already in [TranslationStore] is
     * left alone, so a cancelled run, a dead battery or a network drop never redoes finished work.
     *
     * Pages are worked on in batches. Inside one batch recognition and translation run on several
     * pages at once (sized by the two concurrency settings), which is where the speed-up comes
     * from; between batches the page order and the carried-over context are kept, so names and
     * tone stay coherent. A batch of one is exactly the old serial behaviour.
     */
    suspend fun run(
        context: Context,
        book: Book,
        pages: List<Int>,
        api: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol = ApiProtocol.DEFAULT,
        groupMode: Boolean = false,
        skipStored: Boolean = true,
        onProgress: suspend (done: Int, index: Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext Result.Done(0, 0)
        if (api.isBlank()) return@withContext Result.Failed(pages.first(), "no endpoint")
        val token = ApiClient.CancelToken()
        activeToken = token
        val stored = if (skipStored) TranslationStore.translatedIndexes(context, book.id) else emptySet()
        // Carry the previous lines into the first page of a resumed run so the model keeps the
        // tone of the conversation instead of treating it as a fresh volume.
        var previous: List<String> = emptyList()
        val firstPending = pages.firstOrNull { it !in stored }
        if (firstPending != null) {
            val prior = pages.filter { it < firstPending && it in stored }.maxOrNull()
            if (prior != null) previous = TranslationStore.page(context, book.id, prior)?.lines.orEmpty()
        }
        val prefs = Prefs(context)
        val ocrParallel = prefs.ocrConcurrency
        val translateParallel = prefs.translateConcurrency
        val engine = PaddleOcr.engine(context)
        // Recognition workers are only built for a model that can use more than one; otherwise
        // this is a no-op and the run shares the reader's serialised runtime.
        engine.preparePool(OcrModel.from(prefs.ocrModel), ocrParallel)
        var source: PageSource? = null
        var current = pages.first()
        try {
            var done = 0
            var translated = 0
            var skipped = 0
            val failedPages = ArrayList<Int>()
            var streak = 0
            val reader = PageSource.open(context, book).also { source = it }
            // Stored pages cost nothing and must not occupy a worker slot; fold them in first so
            // the batch loop only deals with pages that actually need work.
            val pending = ArrayList<Int>(pages.size)
            for (index in pages) {
                if (index in stored) {
                    skipped++
                    done++
                    onProgress(done, index)
                    continue
                }
                if (index < 0 || index >= reader.pageCount) {
                    done++
                    onProgress(done, index)
                    continue
                }
                pending.add(index)
            }
            val batchSize = max(ocrParallel, translateParallel)
            val translateGate = Semaphore(translateParallel.coerceAtLeast(1))
            var cursor = 0
            while (cursor < pending.size) {
                coroutineContext.ensureActive()
                if (token.cancelled) return@withContext Result.Cancelled
                val end = min(cursor + batchSize, pending.size)
                val batch = ArrayList<Int>(end - cursor)
                for (i in cursor until end) batch.add(pending[i])
                current = batch.last()
                // Bytes are read on this thread (one archive handle), then each page is decoded,
                // recognised and translated on its own coroutine.
                val bytes = batch.map { reader.readPage(it) }
                android.util.Log.i(
                    "PreTranslate", "batch start ${batch.first()}..${batch.last()} n=${batch.size}"
                )
                val outcomes = coroutineScope {
                    batch.mapIndexed { offset, index ->
                        async {
                            processPage(
                                index, bytes[offset], previous, api, apiKey, model,
                                protocol, groupMode, token, translateGate
                            )
                        }
                    }.awaitAll()
                }
                cursor = end
                for (outcome in outcomes) {
                    when (outcome) {
                        is PageOutcome.Cancelled -> return@withContext Result.Cancelled
                        is PageOutcome.Ok -> {
                            TranslationStore.save(
                                context, book.id, outcome.index,
                                PageTranslation(
                                    outcome.width, outcome.height, outcome.groups, outcome.lines
                                )
                            )
                            previous = outcome.lines
                            streak = 0
                            translated++
                            done++
                            onProgress(done, outcome.index)
                            android.util.Log.i(
                                "PreTranslate",
                                "page=${outcome.index} ok groups=${outcome.groups.size}"
                            )
                        }
                        is PageOutcome.Empty -> {
                            TranslationStore.save(
                                context, book.id, outcome.index,
                                PageTranslation(outcome.width, outcome.height, emptyList(), emptyList())
                            )
                            streak = 0
                            translated++
                            done++
                            onProgress(done, outcome.index)
                            android.util.Log.i("PreTranslate", "page=${outcome.index} skipped")
                        }
                        is PageOutcome.Partial -> {
                            // Answered some bubbles and missed others: do not store it (skipStored
                            // would then treat it as finished forever), report it for a re-run.
                            failedPages.add(outcome.index)
                            previous = outcome.lines
                            streak = 0
                            done++
                            onProgress(done, outcome.index)
                        }
                        is PageOutcome.Failed -> {
                            failedPages.add(outcome.index)
                            streak++
                            android.util.Log.w(
                                "PreTranslate", "page=${outcome.index} failed streak=$streak"
                            )
                            if (streak >= MAX_CONSECUTIVE_FAILURES) {
                                return@withContext Result.Failed(outcome.index, outcome.reason)
                            }
                            done++
                            onProgress(done, outcome.index)
                        }
                    }
                }
            }
            // Every page present means the volume is finished, even if this run only had to fill
            // a handful of gaps. This is what lights up the shelf badge.
            val total = source?.pageCount ?: pageCount(context, book)
            if (total > 0 && TranslationStore.count(context, book.id) >= total) {
                TranslationStore.markComplete(context, book.id, total)
            }
            android.util.Log.i(
                "PreTranslate",
                "book done translated=$translated skipped=$skipped failed=${failedPages.size} pages=$total"
            )
            Result.Done(translated, skipped, failedPages)
        } catch (cancel: CancellationException) {
            Result.Cancelled
        } catch (t: Throwable) {
            if (token.cancelled) Result.Cancelled
            else Result.Failed(current, t.javaClass.simpleName)
        } finally {
            engine.releasePool()
            if (activeToken === token) activeToken = null
            runCatching { source?.close() }
        }
    }

    /** What one page produced inside a batch; the caller stores and counts it in page order. */
    private sealed interface PageOutcome {
        val index: Int
        data class Ok(
            override val index: Int,
            val width: Int,
            val height: Int,
            val groups: List<BubbleGroup>,
            val lines: List<String>
        ) : PageOutcome
        data class Empty(override val index: Int, val width: Int, val height: Int) : PageOutcome
        data class Failed(override val index: Int, val reason: String) : PageOutcome
        data class Partial(override val index: Int, val lines: List<String>) : PageOutcome
        data class Cancelled(override val index: Int) : PageOutcome
    }

    /**
     * One page's share of a batch: decode, recognise, group, translate. Recognition is capped by
     * the OCR runtime pool, translation by [translateGate]; everything else runs concurrently.
     */
    private suspend fun processPage(
        index: Int,
        bytes: ByteArray,
        previous: List<String>,
        api: String,
        apiKey: String,
        model: String,
        protocol: ApiProtocol,
        groupMode: Boolean,
        token: ApiClient.CancelToken,
        translateGate: Semaphore
    ): PageOutcome {
        val decoded = PageDecoder.decode(bytes, MAX_SIDE, MAX_SIDE)
        val page = fit(decoded, MAX_SIDE)
        try {
            val regions = TranslationEngine.recognize(page, parallel = true)
            if (token.cancelled) return PageOutcome.Cancelled(index)
            // "Auto" is settled per page here too, so the filters, the reading order and the
            // prompt all agree with what the reader would do on the same page.
            val pageSource = TranslationEngine.resolveSource(regions)
            if (regions.isEmpty() || !TranslationEngine.looksJapanese(regions, pageSource)) {
                return PageOutcome.Empty(index, page.width, page.height)
            }
            val groups = TranslationEngine.groupBubbles(
                regions, page.width, page.height, page, pageSource
            )
            if (groups.isEmpty()) return PageOutcome.Empty(index, page.width, page.height)
            val lines = translateGate.withPermit {
                if (groupMode) {
                    TranslationEngine.translateEach(
                        groups, api, model, previous, apiKey, protocol,
                        sourceLang = pageSource, token = token
                    )
                } else {
                    TranslationEngine.translate(
                        groups, api, model, previous, apiKey, protocol,
                        sourceLang = pageSource, token = token
                    )
                }
            }
            if (token.cancelled) return PageOutcome.Cancelled(index)
            if (lines == null || lines.all { it.isBlank() }) {
                return PageOutcome.Failed(index, "network")
            }
            val partial = groupMode && lines.any { it.isBlank() }
            if (partial) return PageOutcome.Partial(index, lines.filter { it.isNotBlank() })
            return PageOutcome.Ok(index, page.width, page.height, groups, lines)
        } finally {
            if (page !== decoded) page.recycle()
            decoded.recycle()
        }
    }

    private fun fit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}

/**
 * Reads thumbnails from one book for the page picker. Holds a single [PageSource] open so the
 * picker does not reopen the archive per tile; close it when the sheet goes away.
 */
class BookPreview(context: Context, book: Book) : Closeable {
    private val source: PageSource? = runCatching { PageSource.open(context, book) }.getOrNull()
    val pageCount: Int = source?.pageCount ?: 0

    fun thumbnail(index: Int, width: Int): Bitmap? {
        val reader = source ?: return null
        if (index < 0 || index >= reader.pageCount) return null
        return runCatching {
            val bytes = reader.readPage(index)
            val height = (width * 1.4f).toInt()
            PageDecoder.decode(bytes, width, height, quality = 0, tight = true)
        }.getOrNull()
    }

    override fun close() {
        runCatching { source?.close() }
    }
}
