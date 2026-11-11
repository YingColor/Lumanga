package com.yingcolor.lumanga.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.ArchiveException
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.BubbleGroup
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.PageAnimation
import com.yingcolor.lumanga.data.PageDecoder
import com.yingcolor.lumanga.data.PageSource
import com.yingcolor.lumanga.data.Prefs
import com.yingcolor.lumanga.data.TextRegion
import com.yingcolor.lumanga.data.TranslationEngine
import com.yingcolor.lumanga.data.TranslationStore
import com.yingcolor.lumanga.data.UiSettings
import com.yingcolor.lumanga.data.ZoomMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs


private const val WARM_BACK = 2
private const val WARM_AHEAD = 5
















enum class TranslateKind { IDLE, WORKING, EMPTY, NOT_JAPANESE, FAILED }

data class TranslateState(
    val kind: TranslateKind = TranslateKind.IDLE,
    val detail: String = ""
)

private sealed class TranslateOutcome {
    class Done(val bitmap: Bitmap) : TranslateOutcome()
    object NoText : TranslateOutcome()
    object NotJapanese : TranslateOutcome()
    object Stale : TranslateOutcome()
    class Failed(val reason: String) : TranslateOutcome()
}

/** OCR result plus translations for one page, kept so zooming does not repeat the work. */
private class PageResult(
    val width: Int,
    val height: Int,
    val groups: List<BubbleGroup>,
    val lines: List<String>
)

private const val STAGE_OCR = "ocr"
private const val STAGE_API = "api"

/** Hard ceiling for one page's OCR + network round trip, so the badge can never hang forever. */
private const val PAGE_BUDGET_MS = 70_000L

/** OCR alone should never take this long; a wedged interpreter should fail fast instead. */
private const val OCR_BUDGET_MS = 25_000L
private const val MEDIUM_OCR_BUDGET_MS = 70_000L
private const val MEDIUM_PAGE_BUDGET_MS = 110_000L

/** How long a neighbouring page waits for the focused page to start before it goes ahead. */
private const val YIELD_BUDGET_MS = 3_000L

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val mediumOcr: Boolean get() = prefs.ocrModel == OcrModel.MEDIUM.id

    @Volatile
    private var reader: PageSource? = null

    @Volatile
    private var generation = 0

    var pageCount by mutableStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<ArchiveException?>(null)
        private set

    
    
    
    
    private val heapKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    private val pageCacheLimit = (heapKb / 4).coerceIn(48 * 1024, 128 * 1024)
    private val rawCacheLimit = (heapKb / 12).coerceIn(16 * 1024, 40 * 1024)

    
    private val pageCache = object : LruCache<String, Bitmap>(pageCacheLimit) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    




    private val placeholderCache = object : LruCache<Int, Bitmap>(8 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    




    private val rawCache = object : LruCache<Int, ByteArray>(rawCacheLimit) {
        override fun sizeOf(key: Int, value: ByteArray): Int = (value.size / 1024).coerceAtLeast(1)
    }
    private val rawLock = Any()

    
    private val sizeCache = object : LruCache<Int, Pair<Int, Int>>(256) {
        override fun sizeOf(key: Int, value: Pair<Int, Int>): Int = 1
    }

    
    private class Load(
        val key: String,
        val index: Int,
        val widthPx: Int,
        val heightPx: Int,
        val quality: Int,
        val thumbnail: Boolean,
        val quick: Boolean,
        var priority: Int,
        val generation: Int,
        var active: () -> Boolean,
        val result: CompletableDeferred<Bitmap?>
    )

    private val urgent = ArrayDeque<Load>()
    private val neighbours = ArrayDeque<Load>()
    private val previews = ArrayDeque<Load>()
    private val inFlight = HashMap<String, Load>()
    private val queueLock = Any()
    
    
    private val wakeVisible = Channel<Unit>(Channel.CONFLATED)
    private val wakeWarm = Channel<Unit>(Channel.CONFLATED)

    
    var focusIndex by mutableStateOf(0)
        private set

    private companion object {
        const val WARM_RADIUS = 16
        const val PLACEHOLDER_W = 480
        const val PLACEHOLDER_H = 800
    }

    





    private data class TileLoad(
        val key: String,
        val page: Int,
        val top: Int,
        val bottom: Int,
        val targetWidth: Int,
        val generation: Int,
        val active: () -> Boolean,
        val result: CompletableDeferred<Bitmap?>
    )

    private val bytesCache = object : LruCache<Int, ByteArray>(48 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray): Int = (value.size / 1024).coerceAtLeast(1)
    }
    private val tileCache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val tileQueue = ArrayDeque<TileLoad>()
    private val tileInFlight = HashMap<String, CompletableDeferred<Bitmap?>>()
    private val tileLock = Any()
    private val tileWake = Channel<Unit>(Channel.CONFLATED)

    init {
        
        
        
        
        repeat(2) {
            viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val job = takeVisible()
                    if (job == null) {
                        wakeVisible.receive()
                        continue
                    }
                    if (job.generation != generation || job.result.isCompleted) {
                        finish(job, null)
                        continue
                    }
                    val bitmap = runCatching { decode(job) }.getOrNull()
                    finish(job, bitmap)
                }
            }
        }

        
        
        
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val job = takeWarm()
                if (job == null) {
                    wakeWarm.receive()
                    continue
                }
                if (job.generation != generation || !job.active() || job.result.isCompleted) {
                    finish(job, null)
                    continue
                }
                if (!job.thumbnail) warmPlaceholder(job)
                val bitmap = runCatching { decode(job) }.getOrNull()
                finish(job, bitmap)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val job = synchronized(tileLock) { tileQueue.removeFirstOrNull() }
                if (job == null) {
                    tileWake.receive()
                    continue
                }
                if (job.generation != generation || !job.active() || job.result.isCompleted) {
                    finishTile(job, null)
                    continue
                }
                val bitmap = runCatching { decodeTile(job) }.getOrNull()
                finishTile(job, bitmap)
            }
        }
    }

    





    private fun takeVisible(): Load? {
        val dropped = ArrayList<Load>(4)
        val picked = synchronized(queueLock) {
            fun valid(job: Load) = job.generation == generation &&
                job.index in (focusIndex - WARM_RADIUS)..(focusIndex + WARM_RADIUS)

            
            
            val quick = urgent.lastOrNull { it.quick && valid(it) }
            if (quick != null) {
                urgent.remove(quick)
                return@synchronized quick
            }
            
            var found: Load? = null
            while (urgent.isNotEmpty()) {
                val job = urgent.removeLast()
                if (!valid(job)) {
                    dropped += job
                    continue
                }
                found = job
                break
            }
            found
        }
        dropped.forEach { finish(it, null) }
        return picked
    }

    
    private fun takeWarm(): Load? = synchronized(queueLock) {
        var best: Load? = null
        var bestScore = Int.MAX_VALUE
        for (job in neighbours) {
            val score = abs(job.index - focusIndex)
            if (score < bestScore) {
                bestScore = score
                best = job
            }
        }
        if (best != null) {
            neighbours.remove(best)
            return@synchronized best
        }
        previews.removeFirstOrNull()
    }

    private fun finish(job: Load, bitmap: Bitmap?) {
        if (bitmap != null && job.generation == generation) {
            when {
                job.quick -> placeholderCache.put(job.index, bitmap)
                job.thumbnail -> thumbCache.put(job.key, bitmap)
                else -> pageCache.put(job.key, bitmap)
            }
        }
        synchronized(queueLock) {
            if (inFlight[job.key] === job) inFlight.remove(job.key)
        }
        job.result.complete(bitmap)
    }

    
    private fun rawBytes(index: Int, gen: Int): ByteArray? {
        synchronized(rawLock) {
            rawCache.get(index)?.let { return it }
        }
        val archive = reader ?: return null
        val bytes = runCatching { archive.readPage(index) }.getOrNull() ?: return null
        synchronized(rawLock) {
            if (gen == generation) rawCache.put(index, bytes)
        }
        return if (gen == generation) bytes else null
    }

    
    private fun warmPlaceholder(job: Load) {
        if (placeholderCache.get(job.index) != null) return
        val bytes = rawBytes(job.index, job.generation) ?: return
        val small = runCatching {
            PageDecoder.decode(bytes, PLACEHOLDER_W, PLACEHOLDER_H, 0, tight = true)
        }.getOrNull() ?: return
        if (job.generation == generation) placeholderCache.put(job.index, small)
    }

    private fun decode(job: Load): Bitmap? {
        if (job.thumbnail) {
            
            
            val archive = reader ?: return null
            val bytes = runCatching { archive.readPage(job.index) }.getOrNull() ?: return null
            return PageDecoder.decode(bytes, 160, 240, 0, tight = true)
        }
        val bytes = rawBytes(job.index, job.generation) ?: return null
        if (job.quick) {
            return PageDecoder.decode(bytes, PLACEHOLDER_W, PLACEHOLDER_H, 0, tight = true)
        }
        return PageDecoder.decode(
            bytes,
            job.widthPx.coerceAtLeast(256),
            job.heightPx.coerceAtLeast(256),
            job.quality
        )
    }

    private fun request(
        index: Int,
        widthPx: Int,
        heightPx: Int,
        quality: Int,
        thumbnail: Boolean,
        priority: Int,
        active: () -> Boolean,
        quick: Boolean = false
    ): Deferred<Bitmap?> {
        val key = when {
            quick -> "q$index"
            thumbnail -> "t$index"
            else -> "$index-$quality"
        }
        if (quick) {
            placeholderCache.get(index)?.let { return CompletableDeferred(it) }
        } else {
            (if (thumbnail) thumbCache else pageCache).get(key)?.let { return CompletableDeferred(it) }
        }
        return synchronized(queueLock) {
            inFlight[key]?.let { existing ->
                
                
                
                if (priority < existing.priority) {
                    when (existing.priority) {
                        -1, 0 -> urgent.remove(existing)
                        1 -> neighbours.remove(existing)
                        else -> previews.remove(existing)
                    }
                    existing.priority = priority
                    existing.active = active
                    enqueue(existing)
                }
                return@synchronized existing.result
            }
            val deferred = CompletableDeferred<Bitmap?>()
            val job = Load(
                key, index, widthPx, heightPx, quality, thumbnail, quick,
                priority, generation, active, deferred
            )
            inFlight[key] = job
            enqueue(job)
            deferred
        }
    }

    
    private fun enqueue(job: Load) {
        when (job.priority) {
            -1, 0 -> urgent.addLast(job)
            1 -> neighbours.addLast(job)
            else -> previews.addLast(job)
        }
        if (job.priority <= 0) wakeVisible.trySend(Unit) else wakeWarm.trySend(Unit)
    }

    fun open(book: Book) {
        bookId = book.id
        loading = true
        error = null
        pageCount = 0
        dropPending()
        clearTranslations()
        pageCache.evictAll()
        thumbCache.evictAll()
        placeholderCache.evictAll()
        synchronized(rawLock) { rawCache.evictAll() }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { reader?.close() }
                reader = null
                runCatching { PageSource.open(getApplication(), book) }
            }
            result.fold(
                onSuccess = { opened ->
                    reader = opened
                    pageCount = opened.pageCount
                },
                onFailure = { failure ->
                    error = failure as? ArchiveException
                        ?: ArchiveException(ArchiveException.Kind.IO, failure.message ?: "")
                }
            )
            loading = false
        }
    }

    private fun dropPending() {
        synchronized(queueLock) {
            generation++
            urgent.clear()
            neighbours.clear()
            previews.clear()
            inFlight.values.forEach { it.result.complete(null) }
            inFlight.clear()
        }
        synchronized(rawLock) { rawCache.evictAll() }
        synchronized(tileLock) {
            tileQueue.clear()
            tileInFlight.values.forEach { it.complete(null) }
            tileInFlight.clear()
        }
        bytesCache.evictAll()
        tileCache.evictAll()
    }

    
    suspend fun page(index: Int, widthPx: Int, heightPx: Int, quality: Int): Bitmap? {
        
        
        repeat(3) { attempt ->
            val result = request(index, widthPx, heightPx, quality, false, 0, active = { true }).await()
            if (result != null) return result
            if (attempt < 2) delay(20)
        }
        return null
    }

    
    fun prefetch(index: Int, widthPx: Int, heightPx: Int) {
        request(index, widthPx, heightPx, 0, false, 1, active = {
            abs(index - focusIndex) <= WARM_RADIUS
        })
    }

    
    fun setVisibleFocus(focus: Int) {
        focusIndex = focus
        wakeVisible.trySend(Unit)
        wakeWarm.trySend(Unit)
    }

    





    private val translatedCache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pageResults = object : LruCache<String, PageResult>(16) {}
    private val translating = HashMap<String, Deferred<TranslateOutcome>>()
    private val translationLock = Any()
    private val translationContext = LinkedHashMap<Int, List<String>>(6)
    private val contextLock = Any()

    /** Book identity for [TranslationStore]; set by [open]. */
    private var bookId: String = ""

    private val translationStates = mutableStateMapOf<Int, TranslateState>()

    /** Bumped by [retryTranslation] so the page pipeline runs again for the same index. */
    var translationRetry by mutableStateOf(0)
        private set

    fun statusOf(index: Int): TranslateState = translationStates[index] ?: TranslateState()

    fun retryTranslation(index: Int) {
        translatedCache.evictAll()
        pageResults.evictAll()
        // Cancel the in-flight jobs as well, otherwise a slow attempt that is already running
        // keeps writing into the caches and the retry silently does nothing.
        synchronized(translationLock) {
            translating.values.forEach { it.cancel() }
            translating.clear()
        }
        translationStates[index] = TranslateState()
        translationRetry++
    }

    private fun publish(index: Int, state: TranslateState) {
        translationStates[index] = state
    }

    /**
     * Runs the whole page pipeline and returns the rendered page.
     *
     * Two invariants govern the state machine:
     *  - The badge must always leave WORKING. Every exit path publishes a terminal state, and
     *    [PAGE_BUDGET_MS] cancels a pipeline that would otherwise hang on a stalled socket or a
     *    wedged OCR call.
     *  - Cancelling the caller (the user scrolls away) must not throw finished work away. The job
     *    lives in [viewModelScope] and stores its own result, so coming back to the page is
     *    instant instead of restarting the whole pipeline.
     */
    suspend fun translate(
        index: Int,
        source: Bitmap,
        api: String,
        apiKey: String,
        model: String
    ): Bitmap? {
        val key = "$index-${source.width}x${source.height}-${(api + model).hashCode()}"
        translatedCache.get(key)?.let {
            publish(index, TranslateState())
            return it
        }
        publish(index, TranslateState(TranslateKind.WORKING))
        val deferred = synchronized(translationLock) {
            translating[key] ?: viewModelScope.async(Dispatchers.IO) {
                runTranslate(index, key, source, api, apiKey, model)
            }.also { translating[key] = it }
        }
        val started = System.currentTimeMillis()
        val pageBudget = if (mediumOcr) MEDIUM_PAGE_BUDGET_MS else PAGE_BUDGET_MS
        val outcome = try {
            val done = withTimeoutOrNull(pageBudget) { deferred.await() }
            if (done == null) {
                deferred.cancel()
                synchronized(translationLock) { translating.remove(key) }
                android.util.Log.w("Translate", "TR watchdog index=$index ${pageBudget}ms")
                publish(index, TranslateState(TranslateKind.FAILED, "timeout"))
                return null
            }
            done
        } catch (cancel: CancellationException) {
            // The page left the screen. Keep the shared job running: it writes its own result.
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.e(
                "Translate",
                "TR error index=$index ${t.javaClass.name}: ${t.message}",
                t
            )
            TranslateOutcome.Failed(t.javaClass.simpleName)
        }
        val elapsed = System.currentTimeMillis() - started
        if (elapsed > 3000) {
            android.util.Log.i("Translate", "TR slow index=$index ms=$elapsed")
        }
        synchronized(translationLock) { translating.remove(key) }
        return when (outcome) {
            is TranslateOutcome.Done -> {
                translatedCache.put(key, outcome.bitmap)
                publish(index, TranslateState())
                outcome.bitmap
            }
            TranslateOutcome.NoText -> {
                publish(index, TranslateState(TranslateKind.EMPTY))
                null
            }
            TranslateOutcome.NotJapanese -> {
                publish(index, TranslateState(TranslateKind.NOT_JAPANESE))
                null
            }
            is TranslateOutcome.Failed -> {
                android.util.Log.w("Translate", "TR failed index=$index reason=${outcome.reason}")
                publish(index, TranslateState(TranslateKind.FAILED, outcome.reason))
                null
            }
            // Stale means the page is far from the viewport: clear the badge instead of leaving it
            // on WORKING, which would never be resolved by anything else.
            TranslateOutcome.Stale -> {
                publish(index, TranslateState())
                null
            }
        }
    }

    private suspend fun runTranslate(
        index: Int,
        key: String,
        source: Bitmap,
        api: String,
        apiKey: String,
        model: String
    ): TranslateOutcome {
        val rk = resultKey(index, api, model)
        pageResults.get(rk)?.let { cached ->
            android.util.Log.i("Translate", "TR reuse index=$index")
            return TranslateOutcome.Done(renderScaled(source, cached))
        }
        // A page translated ahead of time (long-press → 预翻译) needs neither OCR nor network.
        val stored = TranslationStore.page(getApplication(), bookId, index)
        if (stored != null) {
            if (stored.isEmpty) {
                // Checked during pre-translation and found nothing worth translating.
                android.util.Log.i("Translate", "TR stored-empty index=$index")
                return TranslateOutcome.Done(source)
            }
            android.util.Log.i("Translate", "TR stored index=$index")
            val cached = PageResult(stored.width, stored.height, stored.groups, stored.lines)
            pageResults.put(rk, cached)
            return TranslateOutcome.Done(renderScaled(source, cached))
        }
        if (api.isBlank()) return TranslateOutcome.Failed("no endpoint")
        // The page on screen must never queue behind its neighbours: OCR is serialised, so a
        // neighbour that grabs it first would add its full cost to the page the user is reading.
        // Neighbours wait their turn instead, bounded so a wedged page cannot block them forever.
        var waited = 0L
        while (waited < YIELD_BUDGET_MS && index != focusIndex &&
            statusOf(focusIndex).kind == TranslateKind.WORKING
        ) {
            delay(150)
            waited += 150
        }
        if (abs(index - focusIndex) > WARM_AHEAD) {
            android.util.Log.i("Translate", "TR stale index=$index focus=$focusIndex")
            return TranslateOutcome.Stale
        }
        publish(index, TranslateState(TranslateKind.WORKING, STAGE_OCR))
        val ocrStarted = System.currentTimeMillis()
        // The medium OCR model is several times slower than the small one; give it room to
        // finish instead of throwing away a full recognition at the 25 s mark.
        val ocrBudget = if (mediumOcr) MEDIUM_OCR_BUDGET_MS else OCR_BUDGET_MS
        val regions = withTimeoutOrNull(ocrBudget) { TranslationEngine.recognize(source) }
            ?: run {
                android.util.Log.w("Translate", "TR ocr timeout index=$index")
                return TranslateOutcome.Failed("ocr timeout")
            }
        android.util.Log.i(
            "Translate",
            "TR ocr index=$index regions=${regions.size} ms=${System.currentTimeMillis() - ocrStarted}"
        )
        if (regions.isEmpty()) return TranslateOutcome.NoText
        if (!TranslationEngine.looksJapanese(regions)) {
            android.util.Log.i("Translate", "TR skip index=$index not japanese")
            return TranslateOutcome.NotJapanese
        }
        val groups = TranslationEngine.groupBubbles(regions, source.width, source.height, source)
        android.util.Log.i("Translate", "TR bubbles index=$index groups=${groups.size}")
        if (groups.isEmpty()) return TranslateOutcome.NoText
        val previous = synchronized(contextLock) {
            translationContext.entries
                .filter { it.key < index }
                .maxByOrNull { it.key }
                ?.value ?: emptyList()
        }
        publish(index, TranslateState(TranslateKind.WORKING, STAGE_API))
        val started = System.currentTimeMillis()
        val lines = TranslationEngine.translate(groups, api, model, previous, apiKey)
        android.util.Log.i(
            "Translate",
            "TR api index=$index lines=${lines?.size ?: -1} ms=${System.currentTimeMillis() - started}"
        )
        if (lines == null) return TranslateOutcome.Failed("network")
        if (lines.all { it.isBlank() }) return TranslateOutcome.Failed("empty reply")
        synchronized(contextLock) {
            translationContext[index] = lines
            while (translationContext.size > 6) {
                translationContext.remove(translationContext.keys.first())
            }
        }
        pageResults.put(rk, PageResult(source.width, source.height, groups, lines))
        val rendered = TranslationEngine.render(source, groups, lines)
        // Store the result here rather than in the caller: if the user scrolled away while the
        // request was in flight the awaiter is already cancelled, and the finished page would
        // otherwise be dropped and re-translated from scratch on the next visit.
        translatedCache.put(key, rendered)
        android.util.Log.i("Translate", "TR done index=$index")
        return TranslateOutcome.Done(rendered)
    }

    private fun resultKey(index: Int, api: String, model: String): String =
        "$index-${(api + model).hashCode()}"

    /** Re-draws a cached page at a different decode size instead of running OCR again. */
    private fun renderScaled(source: Bitmap, cached: PageResult): Bitmap {
        if (cached.width == source.width && cached.height == source.height) {
            return TranslationEngine.render(source, cached.groups, cached.lines)
        }
        val sx = source.width.toFloat() / cached.width
        val sy = source.height.toFloat() / cached.height
        val scaled = cached.groups.map { group ->
            group.copy(
                regions = group.regions.map { region ->
                    region.copy(box = scaleRect(region.box, sx, sy))
                },
                bubble = scaleRect(group.bubble, sx, sy)
            )
        }
        return TranslationEngine.render(source, scaled, cached.lines)
    }

    private fun scaleRect(rect: Rect, sx: Float, sy: Float): Rect = Rect(
        (rect.left * sx).toInt(),
        (rect.top * sy).toInt(),
        (rect.right * sx).toInt(),
        (rect.bottom * sy).toInt()
    )

    fun clearTranslations() {
        translatedCache.evictAll()
        pageResults.evictAll()
        synchronized(translationLock) { translating.clear() }
        synchronized(contextLock) { translationContext.clear() }
        translationStates.clear()
    }

    suspend fun quickPage(index: Int): Bitmap? {
        repeat(2) { attempt ->
            val result = request(index, 0, 0, 0, false, -1, active = { true }, quick = true).await()
            if (result != null) return result
            if (attempt == 0) delay(20)
        }
        return null
    }

    
    suspend fun thumbnail(index: Int, active: () -> Boolean): Bitmap? =
        request(index, 0, 0, 0, true, 2, active = active).await()

    
    fun peekPage(index: Int, quality: Int): Bitmap? =
        pageCache.get("$index-$quality")
            ?: pageCache.get("$index-0")
            ?: pageCache.get("$index-1")
            ?: pageCache.get("$index-2")

    fun peekPlaceholder(index: Int): Bitmap? = placeholderCache.get(index)

    fun peekThumbnail(index: Int): Bitmap? = thumbCache.get("t$index")

    

    




    suspend fun pageSizes(): List<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        val archive = reader ?: return@withContext emptyList()
        val count = pageCount
        val result = ArrayList<Pair<Int, Int>>(count)
        for (i in 0 until count) {
            val header = runCatching { archive.readHeader(i) }.getOrNull()
            result.add(header?.let { PageDecoder.bounds(it) } ?: (800 to 1200))
        }
        result
    }

    




    suspend fun sourceSize(index: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        sizeCache.get(index)?.let { return@withContext it }
        val archive = reader ?: return@withContext null
        val header = runCatching { archive.readHeader(index) }.getOrNull()
            ?: return@withContext null
        val bounds = PageDecoder.bounds(header) ?: return@withContext null
        if (bounds.first <= 0 || bounds.second <= 0) null else bounds.also { sizeCache.put(index, it) }
    }

    fun tile(
        page: Int,
        slice: Int,
        top: Int,
        bottom: Int,
        targetWidth: Int,
        active: () -> Boolean
    ): Deferred<Bitmap?> {        val key = tileKey(page, slice, targetWidth)
        tileCache.get(key)?.let { return CompletableDeferred(it) }
        return synchronized(tileLock) {
            tileInFlight[key]?.let { return@synchronized it }
            val deferred = CompletableDeferred<Bitmap?>()
            tileInFlight[key] = deferred
            tileQueue.addLast(
                TileLoad(key, page, top, bottom, targetWidth, generation, active, deferred)
            )
            tileWake.trySend(Unit)
            deferred
        }
    }

    fun peekTile(page: Int, slice: Int, targetWidth: Int): Bitmap? =
        tileCache.get(tileKey(page, slice, targetWidth))

    
    fun releasePageModeCaches() {
        pageCache.evictAll()
        thumbCache.evictAll()
        placeholderCache.evictAll()
        synchronized(rawLock) { rawCache.evictAll() }
    }

    fun releaseWebtoonCaches() {
        bytesCache.evictAll()
        tileCache.evictAll()
    }

    private fun tileKey(page: Int, slice: Int, targetWidth: Int) = "w$page-$slice-$targetWidth"

    private fun finishTile(job: TileLoad, bitmap: Bitmap?) {
        if (bitmap != null && job.generation == generation) tileCache.put(job.key, bitmap)
        synchronized(tileLock) {
            if (tileInFlight[job.key] === job.result) tileInFlight.remove(job.key)
        }
        job.result.complete(bitmap)
    }

    
    private fun bytesFor(page: Int): ByteArray? = synchronized(tileLock) {
        bytesCache.get(page)?.let { return@synchronized it }
        val archive = reader ?: return@synchronized null
        val bytes = runCatching { archive.readPage(page) }.getOrNull() ?: return@synchronized null
        bytesCache.put(page, bytes)
        bytes
    }

    private fun decodeTile(job: TileLoad): Bitmap? {
        val bytes = bytesFor(job.page) ?: return null
        PageDecoder.decodeRegion(bytes, job.top, job.bottom, job.targetWidth)?.let { return it }
        
        
        val size = PageDecoder.bounds(bytes) ?: return null
        val full = PageDecoder.decodeWhole(bytes) ?: return null
        val scale = full.height.toFloat() / size.second
        val t = (job.top * scale).toInt().coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val b = (job.bottom * scale).toInt().coerceIn(t + 1, full.height)
        return runCatching { Bitmap.createBitmap(full, 0, t, full.width, b - t) }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        dropPending()
        runCatching { reader?.close() }
        reader = null
        pageCache.evictAll()
        thumbCache.evictAll()
        placeholderCache.evictAll()
        synchronized(rawLock) { rawCache.evictAll() }
    }
}

@Composable
fun ReaderScreen(
    book: Book,
    vm: ReaderViewModel,
    prefs: Prefs,
    settings: UiSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onTranslateConfig: () -> Unit,
    settingsOpen: Boolean,
    onCloseSettings: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onResetProgress: () -> Unit
) {
    val activity = LocalContext.current as? Activity
    
    
    BackHandler(enabled = !settingsOpen) { onBack() }

    
    DisposableEffect(book.id, settings.keepAwake) {
        val window = activity?.window
        if (settings.keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
    ) {
        when {
            vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(S.loading, color = Color(0xFFC2CCC7), style = MaterialTheme.typography.bodyMedium)
                }
            }
            vm.error != null -> ErrorPane(message = errorMessage(vm.error), onBack = onBack)
            
            
            
            else -> key(book.id, vm.pageCount, settings.readMode, settings.doublePage, settings.readingDirection) {
                when (settings.readMode) {
                    1 -> {
                        
                        WebtoonScreen(
                            book = book,
                            vm = vm,
                            prefs = prefs,
                            settings = settings,
                            onBack = onBack,
                            onSettings = onSettings,
                            settingsOpen = settingsOpen,
                            onCloseSettings = onCloseSettings,
                            onProgress = onProgress,
                            onResetProgress = onResetProgress
                        )
                    }
                    2 -> {
                        
                        HorizontalWebtoonScreen(
                            book = book,
                            vm = vm,
                            prefs = prefs,
                            settings = settings,
                            onBack = onBack,
                            onSettings = onSettings,
                            settingsOpen = settingsOpen,
                            onCloseSettings = onCloseSettings,
                            onProgress = onProgress,
                            onResetProgress = onResetProgress
                        )
                    }
                    else -> {
                        ReaderContent(
                            book = book,
                            vm = vm,
                            prefs = prefs,
                            settings = settings,
                            onBack = onBack,
                            onSettings = onSettings,
                            onTranslateConfig = onTranslateConfig,
                            settingsOpen = settingsOpen,
                            onCloseSettings = onCloseSettings,
                            onProgress = onProgress,
                            onResetProgress = onResetProgress
                        )
                    }
                }
            }
        }
    }
}

private fun errorMessage(error: ArchiveException?): String = when (error?.kind) {
    ArchiveException.Kind.NOT_SEEKABLE -> S.errorUnsupported
    ArchiveException.Kind.PAGE_TOO_LARGE -> S.errorBig
    ArchiveException.Kind.NO_IMAGES -> S.noImages
    else -> S.errorCorrupt
}

@Composable
private fun ErrorPane(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(S.errorTitle, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(22.dp))
                PillButton(S.back, onBack)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    book: Book,
    vm: ReaderViewModel,
    prefs: Prefs,
    settings: UiSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onTranslateConfig: () -> Unit,
    settingsOpen: Boolean,
    onCloseSettings: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onResetProgress: () -> Unit
) {
    val rtl = settings.readingDirection == 1
    val doublePage = settings.doublePage
    val tapToTurn = settings.tapToTurn
    val pageCount = vm.pageCount
    val logicalCount = if (doublePage) (pageCount + 1) / 2 else pageCount
    if (logicalCount <= 0) return

    
    val savedImage = prefs.progress(book.id).coerceIn(0, pageCount - 1)
    val initialPage = (if (doublePage) savedImage / 2 else savedImage)
        .coerceIn(0, logicalCount - 1)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.releaseWebtoonCaches() }
    var showUi by remember { mutableStateOf(true) }

    
    
    BackHandler {
        when {
            settingsOpen -> onCloseSettings()
            showUi -> showUi = false
            else -> onBack()
        }
    }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var flashSide by remember { mutableIntStateOf(-1) }
    var flashToken by remember { mutableIntStateOf(0) }
    
    
    
    var showFlash by remember { mutableStateOf(false) }
    val flashAlpha = remember { Animatable(0f) }
    val flashIn = motionTween<Float>(MotionSpec.SHORT, MotionSpec.EaseOutCubic)
    val flashOut = motionTween<Float>(MotionSpec.MEDIUM, MotionSpec.EaseOutCubic)

    
    
    
    LaunchedEffect(flashToken) {
        if (flashToken == 0) return@LaunchedEffect
        showFlash = true
        withTimeoutOrNull(900L) {
            flashAlpha.snapTo(0f)
            flashAlpha.animateTo(1f, flashIn)
            flashAlpha.animateTo(0f, flashOut)
        }
        flashAlpha.snapTo(0f)
        showFlash = false
    }

    
    
    
    val zoomed by remember { derivedStateOf { scale > 1.02f } }
    val pageTurnMode = settings.pageAnim
    
    
    
    val useTurnLayer = pageTurnMode != PageAnimation.SLIDE && !zoomed
    
    
    
    
    
    
    
    var entrySide by remember { mutableIntStateOf(if (rtl) -1 else 1) }
    val quality by remember {
        derivedStateOf {
            when {
                scale > 2.5f -> 2
                scale > 1.3f -> 1
                else -> 0
            }
        }
    }

    fun resetZoom() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    
    
    
    fun firstImageIndex(pagerPage: Int): Int =
        if (doublePage) pagerPage * 2 else pagerPage

    val pagerState = rememberPagerState(initialPage = initialPage) { logicalCount }
    val currentPage = pagerState.currentPage.coerceIn(0, logicalCount - 1)

    
    
    
    var requestedPage by remember { mutableIntStateOf(initialPage) }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var lastTurnAt by remember { mutableStateOf(0L) }

    val turnSpec = motionTween<Float>(MotionSpec.MEDIUM)

    fun goTo(target: Int) {
        val safe = target.coerceIn(0, logicalCount - 1)
        
        
        
        val delta = safe - pagerState.currentPage
        if (delta != 0) {
            entrySide = (if (delta > 0) 1 else -1) * (if (rtl) -1 else 1)
        }
        val now = SystemClock.uptimeMillis()
        
        
        
        
        
        val burst = now - lastTurnAt < 420L ||
            jumpJob?.isActive == true || pagerState.isScrollInProgress
        lastTurnAt = now
        requestedPage = safe
        jumpJob?.cancel()
        jumpJob = scope.launch {
            if (burst) {
                pagerState.scrollToPage(safe)
            } else {
                pagerState.animateScrollToPage(safe, animationSpec = turnSpec)
            }
        }
    }

    
    
    
    
    
    
    fun go(delta: Int) = goTo(pagerState.currentPage + delta)

    
    
    
    
    LaunchedEffect(currentPage) {
        val burst = SystemClock.uptimeMillis() - lastTurnAt < 420L
        if (!burst && jumpJob?.isActive != true && !pagerState.isScrollInProgress) {
            requestedPage = currentPage
        }
    }

    
    
    LaunchedEffect(lastTurnAt) {
        if (lastTurnAt == 0L) return@LaunchedEffect
        delay(450L)
        if (jumpJob?.isActive != true && !pagerState.isScrollInProgress) {
            requestedPage = currentPage
        }
    }

    
    
    
    
    
    
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && jumpJob?.isActive != true) {
                    requestedPage = pagerState.currentPage.coerceIn(0, logicalCount - 1)
                }
            }
    }

    
    
    
    
    
    var settledPage by remember { mutableIntStateOf(initialPage) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            !pagerState.isScrollInProgress &&
                abs(pagerState.currentPageOffsetFraction) < 0.02f
        }.collect { settled ->
            if (settled) settledPage = pagerState.currentPage
        }
    }

    fun turn(delta: Int, side: Int) {
        
        
        entrySide = delta * if (rtl) -1 else 1
        go(delta)
        flashSide = side
        flashToken++
    }

    
    
    LaunchedEffect(settings.fitMode, settings.zoomPercent) {
        resetZoom()
    }

    LaunchedEffect(currentPage, pageCount) {        
        
        
        
        val imageIndex = firstImageIndex(currentPage)
        if (imageIndex > 0) {
            onProgress(imageIndex, pageCount)
        } else {
            onResetProgress()
        }
        resetZoom()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetW = with(density) { maxWidth.toPx().toInt() }
        val targetH = with(density) { maxHeight.toPx().toInt() }
        
        
        
        
        val compactBar = maxHeight < 520.dp

        
        val tapTolerancePx = with(density) { 2.dp.toPx() }
        
        
        
        
        val gestureInsets = WindowInsets.systemGestures
        val leftGuard = gestureInsets.getLeft(density, LayoutDirection.Ltr)
        val rightGuard = gestureInsets.getRight(density, LayoutDirection.Ltr)
        val gestureGuardPx = maxOf(
            leftGuard.toFloat(),
            rightGuard.toFloat(),
            
            
            
            with(density) { 20.dp.toPx() }
        ) + with(density) { 4.dp.toPx() }

        LaunchedEffect(currentPage, doublePage, pageCount, targetW, targetH) {
            val focus = firstImageIndex(currentPage)
            vm.setVisibleFocus(focus)
            
            
            
            
            val from = (currentPage - WARM_BACK).coerceAtLeast(0)
            val to = (currentPage + WARM_AHEAD).coerceAtMost(logicalCount - 1)
            val indices = ArrayList<Int>((to - from + 1) * 2)
            for (p in from..to) {
                val first = firstImageIndex(p)
                indices += first
                if (doublePage && first + 1 < pageCount) indices += first + 1
            }
            indices.sortBy { abs(it - focus) }
            indices.forEach { vm.prefetch(it, targetW, targetH) }
        }

        
        
        
        
        
        if (useTurnLayer) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale <= 1.02f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        } else if (scale > 1.02f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                }
                
                
                
                
                
                .pointerInput(
                    rtl, zoomed, tapToTurn, logicalCount,
                    tapTolerancePx, gestureGuardPx
                ) {
                    var lastTapAt = 0L
                    var lastTapX = 0f
                    var lastTapY = 0f
                    observeTaps(tapTolerancePx, gestureGuardPx) { pos ->
                        val x = pos.x
                        val y = pos.y
                        val now = SystemClock.uptimeMillis()
                        val isDouble = now - lastTapAt < 300L &&
                            abs(x - lastTapX) < 50f && abs(y - lastTapY) < 50f
                        lastTapAt = now
                        lastTapX = x
                        lastTapY = y
                        if (zoomed) {
                            
                            
                            showUi = !showUi
                            if (isDouble) resetZoom()
                        } else if (tapToTurn) {
                            when {
                                x < size.width * 0.32f -> turn(if (rtl) 1 else -1, -1)
                                x > size.width * 0.68f -> turn(if (rtl) -1 else 1, 1)
                                else -> showUi = !showUi
                            }
                        } else {
                            showUi = !showUi
                        }
                    }
                },
            reverseLayout = rtl,
            userScrollEnabled = !zoomed
        ) { pagerPage ->
            val first = firstImageIndex(pagerPage)
            val showSecond = doublePage && first + 1 < pageCount
            
            
            
            
            
            val pos = ((pagerPage - pagerState.currentPage) -
                pagerState.currentPageOffsetFraction) * if (rtl) -1f else 1f
            val progress = (1f - abs(pos)).coerceIn(0f, 1f)
            
            
            
            
            
            
            val settledPos = ((settledPage - pagerState.currentPage) -
                pagerState.currentPageOffsetFraction) * if (rtl) -1f else 1f
            val turnProgress = abs(settledPos).coerceIn(0f, 1f)
            val incomingAlpha = when (pageTurnMode) {
                PageAnimation.FLIP -> (turnProgress * 1.7f).coerceIn(0f, 1f)
                else -> turnProgress
            }
            val isBase = pagerPage == settledPage
            val inRange = abs(pos) <= 1.02f
            
            
            
            
            
            val layerZ = if (useTurnLayer && inRange) {
                if (pageTurnMode == PageAnimation.FLIP) {
                    if (isBase) 1f else 0f
                } else if (!isBase) 1f else 0f
            } else 0f
            
            
            val screenDensity = LocalDensity.current.density
            PageRow(
                vm = vm,
                first = first,
                showSecond = showSecond,
                targetW = targetW,
                targetH = targetH,
                quality = quality,
                settings = settings,
                entrySide = entrySide,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(layerZ)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                        
                        
                        
                        alpha = 1f
                        rotationY = 0f
                        transformOrigin = TransformOrigin.Center
                        cameraDistance = 8f * screenDensity
                        if (useTurnLayer) {
                            
                            
                            
                            
                            translationX -= pos * size.width
                            if (isBase) {
                                
                                
                                
                                
                                
                                alpha = 1f - incomingAlpha
                                if (pageTurnMode == PageAnimation.FLIP && turnProgress > 0f) {
                                    
                                    
                                    
                                    
                                    
                                    
                                    
                                    
                                    val forward = if (rtl) settledPos > 0f else settledPos < 0f
                                    val dir = if (forward) 1f else -1f
                                    val side = if (rtl) -1f else 1f
                                    
                                    
                                    
                                    rotationY = -dir * side * turnProgress * 118f
                                    transformOrigin = TransformOrigin(
                                        if (rtl) 1f else 0f,
                                        0.5f
                                    )
                                    cameraDistance = 12f * screenDensity
                                    
                                    
                                    
                                    translationX -= dir * side * turnProgress *
                                        size.width * 0.10f
                                }
                            } else {
                                if (!inRange) {
                                    alpha = 0f
                                } else when (pageTurnMode) {
                                    PageAnimation.FADE -> alpha = progress
                                    PageAnimation.FLIP -> {
                                        
                                        
                                        
                                        
                                        
                                        
                                        
                                        
                                        val spineX = if (rtl) 1f else 0f
                                        val towardsNext = if (rtl) pos < 0f else pos > 0f
                                        val fold = (1f - progress) * 68f
                                        
                                        
                                        
                                        
                                        
                                        
                                        
                                        
                                        
                                        
                                        val side = if (rtl) -1f else 1f
                                        rotationY = (if (towardsNext) fold else -fold) * side
                                        cameraDistance = 12f * screenDensity
                                        transformOrigin = TransformOrigin(spineX, 0.5f)
                                        alpha = (progress * 1.7f).coerceIn(0f, 1f)
                                    }
                                    PageAnimation.ZOOM -> {
                                        val s = 0.90f + 0.10f * progress
                                        scaleX *= s
                                        scaleY *= s
                                        alpha = progress
                                    }
                                }
                            }
                        }
                    }
            )
        }

        
        
        
        if (showFlash) {
            Box(
                modifier = Modifier
                    .align(if (flashSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 20.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .graphicsLayer { alpha = flashAlpha.value },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (flashSide < 0) {
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    } else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showUi,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            enter = fadeIn(motionTween(MotionSpec.SHORT)) + slideInVertically(
                motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
            ) { -it },
            exit = fadeOut(motionTween(MotionSpec.SHORT)) + slideOutVertically(
                motionTween(MotionSpec.SHORT, MotionSpec.Emphasized)
            ) { -it }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        horizontal = 16.dp,
                        vertical = if (compactBar) 2.dp else 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                
                
                
                
                val effectiveOrientation = settings.bookOrientationRev.let {
                    val perBook = settings.bookOrientation(book.id)
                    if (perBook >= 0) perBook else settings.screenOrientation
                }
                val lockedLandscape = effectiveOrientation == 2
                PillButton(S.back, onBack, filled = false)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                PillButton(
                    text = if (lockedLandscape) S.rotateToPortrait else S.rotateToLandscape,
                    onClick = {
                        settings.setBookOrientation(book.id, if (lockedLandscape) 1 else 2)
                    },
                    filled = false
                )
            }
        }

        AnimatedVisibility(
            visible = showUi,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            enter = fadeIn(motionTween(MotionSpec.SHORT)) + slideInVertically(
                motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
            ) { it / 2 },
            exit = fadeOut(motionTween(MotionSpec.SHORT)) + slideOutVertically(
                motionTween(MotionSpec.SHORT, MotionSpec.Emphasized)
            ) { it / 2 }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 12.dp,
                        vertical = if (compactBar) 4.dp else 10.dp
                    ),
                shape = RoundedCornerShape(if (compactBar) 20.dp else 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = if (compactBar) 6.dp else 10.dp
                    )
                ) {
                    
                    
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = S.pageProgress(firstImageIndex(currentPage), pageCount),
                            style = if (compactBar) {
                                MaterialTheme.typography.titleSmall
                            } else MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.weight(1f))
                        val iconBox = if (compactBar) Modifier.size(30.dp) else Modifier
                        val iconSize = if (compactBar) 18.dp else 24.dp
                        // Translation on/off without leaving the page: filled when on, hollow when
                        // off, so the current state reads at a glance. Sits just left of the gear.
                        val translateOn = settings.translateEnabled
                        // Short tap toggles translation; holding the button for a full second
                        // opens the config. The system long-press is 500 ms, so this uses a
                        // custom timer. UpdatedState keeps the callbacks fresh without ever
                        // restarting the pointerInput block (which would reset the 1 s timer).
                        val tapToggle = rememberUpdatedState<() -> Unit> {
                            if (!translateOn && settings.translateApi.isBlank()) onTranslateConfig()
                            else settings.setTranslateEnabled(!translateOn)
                        }
                        val openConfig = rememberUpdatedState(onTranslateConfig)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = (if (compactBar) Modifier.size(30.dp) else Modifier.size(48.dp))
                                .clip(CircleShape)
                                .pointerInput(Unit) {
                                    coroutineScope {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            down.consume()
                                            var longFired = false
                                            // A separate timer means "finger left the button"
                                            // (a cancelled press) can never be mistaken for a
                                            // long press, and the release is still consumed.
                                            val timer = launch {
                                                delay(1000L)
                                                longFired = true
                                                openConfig.value()
                                            }
                                            try {
                                                val up = waitForUpOrCancellation()
                                                if (up != null) {
                                                    up.consume()
                                                    if (!longFired) tapToggle.value()
                                                }
                                            } finally {
                                                timer.cancel()
                                            }
                                        }
                                    }
                                }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(iconSize)
                                    .clip(CircleShape)
                                    .background(
                                        if (translateOn) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                            ) {
                                Text(
                                    text = S.translateToggleMark,
                                    style = if (compactBar) {
                                        MaterialTheme.typography.labelMedium
                                    } else MaterialTheme.typography.labelLarge,
                                    color = if (translateOn) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onSettings, modifier = iconBox) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = S.settings,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                        IconButton(onClick = { showUi = false }, modifier = iconBox) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = S.closeBar,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }

                    if (compactBar) {
                        
                        
                        
                        
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                PagePreviewStrip(
                                    count = logicalCount,
                                    current = currentPage,
                                    rtl = rtl,
                                    vm = vm,
                                    imageIndexOf = { firstImageIndex(it) },
                                    onPick = { goTo(it) },
                                    compact = true
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (rtl) pageCount.toString() else "1",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = currentPage.coerceAtMost(
                                            (logicalCount - 1).coerceAtLeast(1)
                                        ).toFloat(),
                                        onValueChange = { value ->
                                            val p = value.toInt()
                                                .coerceIn(0, logicalCount - 1)
                                            requestedPage = p
                                            scope.launch {
                                                val d = p - pagerState.currentPage
                                                if (d != 0) {
                                                    entrySide = (if (d > 0) 1 else -1) *
                                                        (if (rtl) -1 else 1)
                                                }
                                                pagerState.scrollToPage(p)
                                            }
                                        },
                                        valueRange = 0f..(logicalCount - 1)
                                            .coerceAtLeast(1).toFloat(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 4.dp)
                                            .graphicsLayer {
                                                scaleX = if (rtl) -1f else 1f
                                            }
                                    )
                                    Text(
                                        text = if (rtl) "1" else pageCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SmallAction(
                                        S.firstPage, { goTo(0) },
                                        Modifier.weight(1f), height = 28.dp
                                    )
                                    SmallAction(
                                        S.previous, { go(-1) },
                                        Modifier.weight(1f), height = 28.dp
                                    )
                                    SmallAction(
                                        S.next, { go(1) },
                                        Modifier.weight(1f), height = 28.dp
                                    )
                                    SmallAction(
                                        S.lastPage, { goTo(logicalCount - 1) },
                                        Modifier.weight(1f), height = 28.dp
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SmallAction(
                                        text = if (doublePage) S.double else S.single,
                                        onClick = { settings.setDoublePage(!doublePage) },
                                        modifier = Modifier.weight(1f),
                                        active = doublePage,
                                        height = 28.dp
                                    )
                                    SmallAction(
                                        text = if (rtl) S.directionRtlShort
                                        else S.directionLtrShort,
                                        onClick = {
                                            settings.setReadingDirection(if (rtl) 0 else 1)
                                        },
                                        modifier = Modifier.weight(1f),
                                        active = rtl,
                                        height = 28.dp
                                    )
                                }
                                SegmentedChoice(
                                    options = S.readModeOptions,
                                    selected = settings.readMode,
                                    onSelect = settings::setReadMode,
                                    modifier = Modifier.fillMaxWidth(),
                                    itemHeight = 30.dp
                                )
                            }
                        }
                    } else {
                        PagePreviewStrip(
                            count = logicalCount,
                            current = currentPage,
                            rtl = rtl,
                            vm = vm,
                            imageIndexOf = { firstImageIndex(it) },
                            onPick = { goTo(it) }
                        )

                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (rtl) pageCount.toString() else "1",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = currentPage.coerceAtMost((logicalCount - 1).coerceAtLeast(1)).toFloat(),
                                onValueChange = { value ->
                                    val p = value.toInt().coerceIn(0, logicalCount - 1)
                                    requestedPage = p
                                    scope.launch {
                                        val d = p - pagerState.currentPage
                                        if (d != 0) {
                                            entrySide = (if (d > 0) 1 else -1) *
                                                (if (rtl) -1 else 1)
                                        }
                                        pagerState.scrollToPage(p)
                                    }
                                },
                                valueRange = 0f..(logicalCount - 1).coerceAtLeast(1).toFloat(),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                                    .graphicsLayer { scaleX = if (rtl) -1f else 1f }
                            )
                            Text(
                                text = if (rtl) "1" else pageCount.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallAction(S.firstPage, { goTo(0) }, Modifier.weight(1f))
                            SmallAction(S.previous, { go(-1) }, Modifier.weight(1f))
                            SmallAction(S.next, { go(1) }, Modifier.weight(1f))
                            SmallAction(S.lastPage, { goTo(logicalCount - 1) }, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallAction(
                                text = if (doublePage) S.double else S.single,
                                onClick = { settings.setDoublePage(!doublePage) },
                                modifier = Modifier.weight(1f),
                                active = doublePage
                            )
                            SmallAction(
                                text = if (rtl) S.directionRtlShort else S.directionLtrShort,
                                onClick = { settings.setReadingDirection(if (rtl) 0 else 1) },
                                modifier = Modifier.weight(1f),
                                active = rtl
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SegmentedChoice(
                            options = S.readModeOptions,
                            selected = settings.readMode,
                            onSelect = settings::setReadMode,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = S.zoomHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }   
                }
            }
        }
    }
}







@Composable
private fun PagePreviewStrip(
    count: Int,
    current: Int,
    rtl: Boolean,
    vm: ReaderViewModel,
    imageIndexOf: (Int) -> Int,
    onPick: (Int) -> Unit,
    compact: Boolean = false
) {
    val state = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme
    val stripHeight = if (compact) 48.dp else 84.dp
    val thumbWidth = if (compact) 30.dp else 52.dp
    val gap = if (compact) 4.dp else 8.dp
    val tilePadding = if (compact) 2.dp else 3.dp

    
    
    LaunchedEffect(current) {
        if (!state.isScrollInProgress) {
            state.scrollToItem((current - 2).coerceAtLeast(0))
        }
    }

    LazyRow(
        state = state,
        reverseLayout = rtl,
        horizontalArrangement = Arrangement.spacedBy(gap),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clip(RoundedCornerShape(12.dp))
    ) {
        items(count, key = { it }) { index ->
            val imageIndex = imageIndexOf(index)
            val bitmap by produceState<Bitmap?>(
                initialValue = vm.peekThumbnail(imageIndex),
                key1 = imageIndex,
                key2 = vm
            ) {
                
                
                value = vm.thumbnail(imageIndex) { isActive }
            }
            val active = index == current
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) scheme.primaryContainer else scheme.surfaceContainerHighest
                    )
                    .border(
                        width = if (active) 2.dp else 0.dp,
                        color = if (active) scheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onPick(index) }
                    .padding(tilePadding),
                contentAlignment = Alignment.Center
            ) {
                val image = bitmap
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = (imageIndex + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun SmallAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    height: Dp = 38.dp
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(height),
        shape = CircleShape,
        color = if (active) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}





@Composable
private fun PageRow(
    vm: ReaderViewModel,
    first: Int,
    showSecond: Boolean,
    targetW: Int,
    targetH: Int,
    quality: Int,
    settings: UiSettings,
    entrySide: Int = 0,
    modifier: Modifier = Modifier
) {
    
    
    
    
    
    val indices = remember(first, showSecond) {
        if (showSecond) listOf(first, first + 1) else listOf(first)
    }
    PageSpread(
        vm = vm,
        indices = indices,
        targetW = targetW,
        targetH = targetH,
        quality = quality,
        smoothing = settings.smoothing,
        zoomMode = settings.fitMode,
        zoomPercent = settings.zoomPercent,
        entrySide = entrySide,
        translate = settings.translateEnabled,
        api = settings.translateApi,
        apiKey = settings.translateApiKey,
        model = settings.translateModel,
        modifier = modifier
    )
}


@Composable
private fun rememberPageBitmap(
    vm: ReaderViewModel,
    index: Int,
    targetW: Int,
    targetH: Int,
    quality: Int,
    zoomMode: Int,
    zoomPercent: Int,
    translate: Boolean,
    api: String,
    apiKey: String,
    model: String
): Bitmap? {
    
    
    var source by remember(index, zoomMode) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(index, zoomMode) {
        if (zoomMode == ZoomMode.ORIGINAL) source = vm.sourceSize(index)
    }
    val decodeW: Int
    val decodeH: Int
    when (zoomMode) {
        ZoomMode.ORIGINAL -> {
            decodeW = source?.first ?: targetW
            decodeH = source?.second ?: targetH
        }
        ZoomMode.FIXED -> {
            decodeW = (targetW.toLong() * zoomPercent / 100).toInt().coerceAtLeast(256)
            decodeH = (targetH.toLong() * zoomPercent / 100).toInt().coerceAtLeast(256)
        }
        else -> {
            decodeW = targetW
            decodeH = targetH
        }
    }
    
    
    
    val state = produceState<Bitmap?>(
        initialValue = vm.peekPage(index, quality) ?: vm.peekPlaceholder(index),
        key1 = index,
        key2 = quality,
        key3 = "$decodeW-$decodeH-$translate-$api-$model-${vm.translationRetry}"
    ) {
        
        
        
        
        if (vm.peekPage(index, quality) == null && vm.peekPlaceholder(index) == null) {
            val quick = vm.quickPage(index)
            if (quick != null && value == null) value = quick
        }
        val full = vm.page(index, decodeW, decodeH, quality)
        if (full != null) value = full
        if (translate && full != null) {
            val rendered = vm.translate(index, full, api, apiKey, model)
            if (rendered != null) value = rendered
        }
    }
    return state.value
}









@Composable
private fun PageSpread(
    vm: ReaderViewModel,
    indices: List<Int>,
    targetW: Int,
    targetH: Int,
    quality: Int,
    smoothing: Int,
    zoomMode: Int,
    zoomPercent: Int,
    entrySide: Int,
    translate: Boolean,
    api: String,
    apiKey: String,
    model: String,
    modifier: Modifier = Modifier
) {
    var panX by remember(indices, zoomMode, zoomPercent) { mutableStateOf(0f) }
    var panY by remember(indices, zoomMode, zoomPercent) { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

        
        
        val loaded = ArrayList<Bitmap?>(indices.size)
        for (i in indices) {
            loaded += rememberPageBitmap(
                vm, i, targetW, targetH, quality, zoomMode, zoomPercent,
                translate, api, apiKey, model
            )
        }

        
        
        
        
        
        var lastGood by remember(indices, zoomMode) { mutableStateOf<List<Bitmap>?>(null) }
        val signature = loaded.joinToString(",") { b ->
            b?.let { System.identityHashCode(it).toString() } ?: "-"
        }
        LaunchedEffect(signature) {
            if (loaded.all { it != null }) lastGood = loaded.map { it!! }
        }
        val bitmaps: List<Bitmap?> = List(indices.size) { k ->
            loaded[k] ?: lastGood?.getOrNull(k)
        }
        if (bitmaps.any { it == null }) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            
            
            val commonH = bitmaps.maxOf { it!!.height.toFloat().coerceAtLeast(1f) }
            val widths = FloatArray(bitmaps.size) { k ->
                val b = bitmaps[k]!!
                b.width.toFloat() * (commonH / b.height.toFloat().coerceAtLeast(1f))
            }
            val totalW = widths.sum().coerceAtLeast(1f)

            
            val (scaleW, scaleH) = when (zoomMode) {
                ZoomMode.ORIGINAL -> 1f to 1f
                ZoomMode.FIT_WIDTH -> {
                    val f = viewportW / totalW
                    f to f
                }
                ZoomMode.FIT_HEIGHT -> {
                    
                    
                    
                    
                    
                    val f = viewportH / commonH
                    f to f
                }
                ZoomMode.FIXED -> {
                    val f = (viewportW * zoomPercent / 100f) / totalW
                    f to f
                }
                ZoomMode.STRETCH -> (viewportW / totalW) to (viewportH / commonH)
                ZoomMode.COVER -> {
                    val f = maxOf(viewportW / totalW, viewportH / commonH)
                    f to f
                }
                else -> { 
                    val f = minOf(viewportW / totalW, viewportH / commonH)
                    f to f
                }
            }
            val spreadW = totalW * scaleW
            val spreadH = commonH * scaleH
            val maxPanX = ((spreadW - viewportW) / 2f).coerceAtLeast(0f)
            val maxPanY = ((spreadH - viewportH) / 2f).coerceAtLeast(0f)

            
            
            
            
            
            val firstW = widths[0] * scaleW
            val secondW = if (widths.size > 1) widths[1] * scaleW else 0f
            val landingCentre = when {
                widths.size < 2 -> spreadW / 2f
                entrySide >= 0 -> firstW / 2f
                else -> firstW + secondW / 2f
            }
            val landingPx = (spreadW / 2f - landingCentre)
                .coerceIn(-maxPanX, maxPanX)

            
            
            var appliedFor by remember { mutableStateOf<List<Int>?>(null) }
            LaunchedEffect(indices, maxPanX, entrySide, landingPx) {
                if (appliedFor != indices) {
                    appliedFor = indices
                    panX = landingPx
                    panY = 0f
                } else {
                    panX = panX.coerceIn(-maxPanX, maxPanX)
                    panY = panY.coerceIn(-maxPanY, maxPanY)
                }
            }

            val drag = when {
                maxPanX > 1f -> Modifier.pointerInput(
                    indices, zoomMode, zoomPercent, maxPanX, maxPanY
                ) {
                    
                    
                    
                    
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        panX = (panX + dragAmount.x).coerceIn(-maxPanX, maxPanX)
                        panY = (panY + dragAmount.y).coerceIn(-maxPanY, maxPanY)
                    }
                }
                maxPanY > 1f -> Modifier.pointerInput(indices, zoomMode, zoomPercent, maxPanY) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        panY = (panY + dragAmount).coerceIn(-maxPanY, maxPanY)
                    }
                }
                else -> Modifier
            }
            Row(
                modifier = Modifier
                    
                    
                    .requiredSize(
                        with(density) { spreadW.toDp() },
                        with(density) { spreadH.toDp() }
                    )
                    .graphicsLayer {
                        translationX = panX
                        translationY = panY
                    }
                    .then(drag)
            ) {
                for (k in bitmaps.indices) {
                    Image(
                        bitmap = bitmaps[k]!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = if (smoothing == 0) {
                            FilterQuality.High
                        } else FilterQuality.None,
                        modifier = Modifier.requiredSize(
                            with(density) { (widths[k] * scaleW).toDp() },
                            with(density) { spreadH.toDp() }
                        )
                    )
                }
            }
            val badgeIndex = if (indices.contains(vm.focusIndex)) vm.focusIndex else indices.first()
            TranslateBadge(
                status = vm.statusOf(badgeIndex),
                onRetry = { vm.retryTranslation(badgeIndex) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
            )
        }
    }
}

@Composable
private fun TranslateBadge(
    status: TranslateState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status.kind == TranslateKind.IDLE) return
    val scheme = MaterialTheme.colorScheme
    // A ticking counter: a slow network can push one page past 30 s, and without a visible number
    // the badge is indistinguishable from a frozen screen.
    var seconds by remember(status) { mutableStateOf(0) }
    LaunchedEffect(status) {
        if (status.kind != TranslateKind.WORKING) return@LaunchedEffect
        seconds = 0
        while (true) {
            delay(1000)
            seconds++
        }
    }
    val base = when (status.kind) {
        TranslateKind.WORKING ->
            if (status.detail == STAGE_OCR) S.translateReading else S.translateWorking
        TranslateKind.EMPTY -> S.translateNoText
        TranslateKind.NOT_JAPANESE -> S.translateNotJapanese
        TranslateKind.FAILED -> S.translateFailed
        else -> return
    }
    val label = if (status.kind == TranslateKind.WORKING && seconds >= 3) {
        "$base ${seconds}s"
    } else base
    val tone = when (status.kind) {
        TranslateKind.WORKING -> scheme.surfaceVariant
        TranslateKind.FAILED -> scheme.errorContainer
        else -> scheme.surfaceVariant
    }
    val content = if (status.kind == TranslateKind.FAILED) scheme.onErrorContainer else scheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.92f))
            .then(
                if (status.kind == TranslateKind.FAILED) {
                    Modifier.clickable { onRetry() }
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}
