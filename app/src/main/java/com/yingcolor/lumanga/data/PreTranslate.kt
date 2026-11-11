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
     * Longest side of the bitmap handed to OCR. Bigger than a phone screen on purpose: the batch
     * runs once, and finer columns lift recognition quality. Larger than this mostly buys time.
     */
    private const val MAX_SIDE = 1400

    sealed interface Result {
        data object Done : Result
        data class Failed(val page: Int, val reason: String) : Result
        data object Cancelled : Result
    }

    suspend fun run(
        context: Context,
        book: Book,
        pages: List<Int>,
        api: String,
        apiKey: String,
        model: String,
        onProgress: suspend (done: Int, index: Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext Result.Done
        if (api.isBlank()) return@withContext Result.Failed(pages.first(), "no endpoint")
        var source: PageSource? = null
        var previous: List<String> = emptyList()
        try {
            source = PageSource.open(context, book)
            val total = pages.size
            var done = 0
            for (index in pages) {
                coroutineContext.ensureActive()
                onProgress(done, index)
                if (index < 0 || index >= source.pageCount) {
                    done++
                    continue
                }
                android.util.Log.i("PreTranslate", "page=$index start")
                val bytes = source.readPage(index)
                val decoded = PageDecoder.decode(bytes, MAX_SIDE, MAX_SIDE)
                val page = fit(decoded, MAX_SIDE)
                try {
                    val regions = TranslationEngine.recognize(page)
                    if (regions.isEmpty() || !TranslationEngine.looksJapanese(regions)) {
                        // Remember the verdict so the reader does not re-run OCR on this page.
                        TranslationStore.save(
                            context, book.id, index,
                            PageTranslation(page.width, page.height, emptyList(), emptyList())
                        )
                        android.util.Log.i("PreTranslate", "page=$index skipped")
                        done++
                        continue
                    }
                    val groups = TranslationEngine.groupBubbles(regions, page.width, page.height)
                    if (groups.isEmpty()) {
                        TranslationStore.save(
                            context, book.id, index,
                            PageTranslation(page.width, page.height, emptyList(), emptyList())
                        )
                        done++
                        continue
                    }
                    val lines = TranslationEngine.translate(groups, api, model, previous, apiKey)
                        ?: return@withContext Result.Failed(index, "network")
                    if (lines.all { it.isBlank() }) {
                        return@withContext Result.Failed(index, "empty reply")
                    }
                    TranslationStore.save(
                        context, book.id, index,
                        PageTranslation(page.width, page.height, groups, lines)
                    )
                    previous = lines
                    android.util.Log.i(
                        "PreTranslate", "page=$index ok groups=${groups.size}"
                    )
                } finally {
                    if (page !== decoded) page.recycle()
                    decoded.recycle()
                }
                done++
                onProgress(done, index)
            }
            Result.Done
        } catch (cancel: CancellationException) {
            Result.Cancelled
        } catch (t: Throwable) {
            Result.Failed(pages.firstOrNull() ?: 0, t.javaClass.simpleName)
        } finally {
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
