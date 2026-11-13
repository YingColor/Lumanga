package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.max

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
        var source: PageSource? = null
        var current = pages.first()
        try {
            var done = 0
            var translated = 0
            var skipped = 0
            // Pages the service refused, and how many in a row. A single miss is normal on a
            // self-hosted endpoint; three in a row means it is down and the run should stop.
            val failedPages = ArrayList<Int>()
            var streak = 0
            for (index in pages) {
                coroutineContext.ensureActive()
                if (token.cancelled) return@withContext Result.Cancelled
                current = index
                onProgress(done, index)
                if (index in stored) {
                    skipped++
                    done++
                    onProgress(done, index)
                    continue
                }
                val reader = source ?: PageSource.open(context, book).also { source = it }
                if (index < 0 || index >= reader.pageCount) {
                    done++
                    onProgress(done, index)
                    continue
                }
                android.util.Log.i("PreTranslate", "page=$index start")
                val bytes = reader.readPage(index)
                val decoded = PageDecoder.decode(bytes, MAX_SIDE, MAX_SIDE)
                val page = fit(decoded, MAX_SIDE)
                try {
                    val regions = TranslationEngine.recognize(page)
                    // OCR is a blocking native call; check right after it so a stop pressed
                    // during recognition does not start yet another request.
                    if (token.cancelled) return@withContext Result.Cancelled
                    // "Auto" is settled per page here too, so the filters, the reading order and
                    // the prompt all agree with what the reader would do on the same page.
                    val pageSource = TranslationEngine.resolveSource(regions)
                    if (regions.isEmpty() ||
                        !TranslationEngine.looksJapanese(regions, pageSource)
                    ) {
                        // Remember the verdict so the reader does not re-run OCR on this page.
                        TranslationStore.save(
                            context, book.id, index,
                            PageTranslation(page.width, page.height, emptyList(), emptyList())
                        )
                        android.util.Log.i("PreTranslate", "page=$index skipped")
                    } else {
                        val groups = TranslationEngine.groupBubbles(
                            regions, page.width, page.height, page, pageSource
                        )
                        if (groups.isEmpty()) {
                            TranslationStore.save(
                                context, book.id, index,
                                PageTranslation(page.width, page.height, emptyList(), emptyList())
                            )
                        } else {
                            val lines = if (groupMode) {
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
                            if (token.cancelled) return@withContext Result.Cancelled
                            // A per-group run can answer some bubbles and miss others. Saving that
                            // page would mark it translated forever (skipStored) and could light
                            // the "done" badge with Japanese still on it, so report it as a page
                            // to retry instead of storing a half-finished result.
                            val partial = groupMode && lines?.any { it.isBlank() } == true
                            if (lines == null || lines.all { it.isBlank() }) {
                                // Keep the volume going: a dropped connection on one page should
                                // not cost the pages after it. Three misses in a row is a dead
                                // service, and there stopping is the honest answer.
                                failedPages.add(index)
                                streak++
                                android.util.Log.w(
                                    "PreTranslate", "page=$index failed streak=$streak"
                                )
                                if (streak >= MAX_CONSECUTIVE_FAILURES) {
                                    return@withContext Result.Failed(index, "network")
                                }
                                done++
                                onProgress(done, index)
                                continue
                            }
                            streak = 0
                            if (partial) {
                                failedPages.add(index)
                                android.util.Log.w(
                                    "PreTranslate",
                                    "page=$index partial answered=" +
                                        "${lines.count { it.isNotBlank() }}/${lines.size}"
                                )
                                previous = lines.filter { it.isNotBlank() }
                                done++
                                onProgress(done, index)
                                continue
                            }
                            TranslationStore.save(
                                context, book.id, index,
                                PageTranslation(page.width, page.height, groups, lines)
                            )
                            previous = lines
                            android.util.Log.i("PreTranslate", "page=$index ok groups=${groups.size}")
                        }
                    }
                } finally {
                    if (page !== decoded) page.recycle()
                    decoded.recycle()
                }
                done++
                translated++
                onProgress(done, index)
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
            if (activeToken === token) activeToken = null
            runCatching { source?.close() }
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
