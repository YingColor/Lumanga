package com.yingcolor.comicviewer.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yingcolor.comicviewer.S
import com.yingcolor.comicviewer.data.ArchiveException
import com.yingcolor.comicviewer.data.Book
import com.yingcolor.comicviewer.data.PageAnimation
import com.yingcolor.comicviewer.data.PageDecoder
import com.yingcolor.comicviewer.data.PageSource
import com.yingcolor.comicviewer.data.Prefs
import com.yingcolor.comicviewer.data.UiSettings
import com.yingcolor.comicviewer.data.ZoomMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * One archive, one worker, three priority queues.
 *
 * A ZipFile shares a single seekable channel, so "parallel" page reads only
 * serialise on a lock while competing for CPU and heap. Worse, the preview strip
 * used to unzip ten full pages at once, and every one of them could delay the
 * page the user is actually looking at. Here all archive reads go through one
 * worker that always serves: visible page > neighbouring pages > preview strip.
 * A visible page therefore waits for at most one small job, never for a queue.
 *
 * Requests are deduplicated, stale previews (scrolled out of the strip) are
 * skipped, and opening another book invalidates everything still in flight.
 */
class ReaderViewModel(app: Application) : AndroidViewModel(app) {
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

    /** Bounded by bytes, so zooming into a giant scan cannot exhaust the heap. */
    private val pageCache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Previews live apart from full pages so they can never evict (or be handed out as) a page. */
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Header-only page dimensions, used by the 1:1 "original size" zoom mode. */
    private val sizeCache = object : LruCache<Int, Pair<Int, Int>>(256) {
        override fun sizeOf(key: Int, value: Pair<Int, Int>): Int = 1
    }

    private data class Load(
        val key: String,
        val index: Int,
        val widthPx: Int,
        val heightPx: Int,
        val quality: Int,
        val thumbnail: Boolean,
        val priority: Int,
        val generation: Int,
        val active: () -> Boolean,
        val result: CompletableDeferred<Bitmap?>
    )

    private val urgent = ArrayDeque<Load>()
    private val neighbours = ArrayDeque<Load>()
    private val previews = ArrayDeque<Load>()
    private val inFlight = HashMap<String, CompletableDeferred<Bitmap?>>()
    private val queueLock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Continuous-scroll mode keeps the inflated bytes of recently seen pages so
     * all of their slices share one unzip, and a separate bitmap cache for the
     * slices themselves. It has its own worker: page mode and scroll mode are
     * never active at the same time, so neither can starve the other.
     */
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
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val job = takeNext()
                if (job == null) {
                    wake.receive()
                    continue
                }
                if (job.generation != generation || !job.active() || job.result.isCompleted) {
                    finish(job, null)
                    continue
                }
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

    private fun takeNext(): Load? = synchronized(queueLock) {
        urgent.removeFirstOrNull()
            ?: neighbours.removeFirstOrNull()
            ?: previews.removeFirstOrNull()
    }

    private fun finish(job: Load, bitmap: Bitmap?) {
        if (bitmap != null && job.generation == generation) {
            (if (job.thumbnail) thumbCache else pageCache).put(job.key, bitmap)
        }
        synchronized(queueLock) {
            if (inFlight[job.key] === job.result) inFlight.remove(job.key)
        }
        job.result.complete(bitmap)
    }

    private fun decode(job: Load): Bitmap? {
        val archive = reader ?: return null
        val bytes = archive.readPage(job.index)
        return if (job.thumbnail) {
            // Much tighter sampling than a real page: a 52 dp preview never needs
            // a 500 px bitmap, and inflating the full page is the expensive part.
            PageDecoder.decode(bytes, 160, 240, 0, tight = true)
        } else {
            PageDecoder.decode(
                bytes,
                job.widthPx.coerceAtLeast(256),
                job.heightPx.coerceAtLeast(256),
                job.quality
            )
        }
    }

    private fun request(
        index: Int,
        widthPx: Int,
        heightPx: Int,
        quality: Int,
        thumbnail: Boolean,
        priority: Int,
        active: () -> Boolean
    ): Deferred<Bitmap?> {
        val key = if (thumbnail) "t$index" else "$index-$quality"
        val cache = if (thumbnail) thumbCache else pageCache
        cache.get(key)?.let { return CompletableDeferred(it) }
        return synchronized(queueLock) {
            inFlight[key]?.let { return@synchronized it }
            val deferred = CompletableDeferred<Bitmap?>()
            inFlight[key] = deferred
            val job = Load(
                key, index, widthPx, heightPx, quality, thumbnail,
                priority, generation, active, deferred
            )
            when (priority) {
                0 -> urgent.addLast(job)
                1 -> neighbours.addLast(job)
                else -> previews.addLast(job)
            }
            wake.trySend(Unit)
            deferred
        }
    }

    fun open(book: Book) {
        loading = true
        error = null
        pageCount = 0
        dropPending()
        pageCache.evictAll()
        thumbCache.evictAll()
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
            inFlight.values.forEach { it.complete(null) }
            inFlight.clear()
        }
        synchronized(tileLock) {
            tileQueue.clear()
            tileInFlight.values.forEach { it.complete(null) }
            tileInFlight.clear()
        }
        bytesCache.evictAll()
        tileCache.evictAll()
    }

    /** The page the user is looking at: highest priority. */
    suspend fun page(index: Int, widthPx: Int, heightPx: Int, quality: Int): Bitmap? =
        request(index, widthPx, heightPx, quality, false, 0) { true }.await()

    /** Fire-and-forget warm-up for the two adjacent pages. */
    fun prefetch(index: Int, widthPx: Int, heightPx: Int) {
        request(index, widthPx, heightPx, 0, false, 1) { true }
    }

    /** Lowest priority; `active` turns false when the preview scrolls away. */
    suspend fun thumbnail(index: Int, active: () -> Boolean): Bitmap? =
        request(index, 0, 0, 0, true, 2, active).await()

    /** Synchronous cache peek: show the old bitmap immediately instead of a spinner. */
    fun peekPage(index: Int, quality: Int): Bitmap? =
        pageCache.get("$index-$quality")
            ?: pageCache.get("$index-0")
            ?: pageCache.get("$index-1")
            ?: pageCache.get("$index-2")

    fun peekThumbnail(index: Int): Bitmap? = thumbCache.get("t$index")

    // ---- Continuous-scroll (Webtoon) support ----

    /**
     * Only the first 64 KB of each entry is inflated, so even a 300-strip book
     * gets a full size table in a moment. Pages whose header cannot be parsed
     * fall back to a conventional comic ratio.
     */
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

    /**
     * Pixel size of one page, read from the image header only. Used by the
     * "original size" zoom mode, which needs the real dimensions to decode at
     * 1:1 instead of at screen size.
     */
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

    /** Switching modes frees the other mode's bitmaps so both never fill the heap. */
    fun releasePageModeCaches() {
        pageCache.evictAll()
        thumbCache.evictAll()
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

    /** One unzip per page, shared by all of its slices. */
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
        // Exotic formats without region decoding: scale a bounded whole image
        // and crop this strip's vertical fraction. Softer, but never blank.
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
    settingsOpen: Boolean,
    onCloseSettings: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onResetProgress: () -> Unit
) {
    val activity = LocalContext.current as? Activity
    // While the settings window is open it owns the back button; the content
    // below installs its own handler that closes the window / tool bar first.
    BackHandler(enabled = !settingsOpen) { onBack() }

    // Screen-level push animations are owned by the parent AnimatedContent.
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
            // Rebuild the pager when the page layout changes: switching to double
            // page halves the page count, and keeping the old pager index would
            // point past the end of the new range.
            else -> key(book.id, vm.pageCount, settings.readMode, settings.doublePage, settings.readingDirection) {
                if (settings.readMode == 1) {
                    // Continuous-scroll mode for long Webtoon strips.
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
                } else {
                    ReaderContent(
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

@Composable
private fun ReaderContent(
    book: Book,
    vm: ReaderViewModel,
    prefs: Prefs,
    settings: UiSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
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

    // Stored progress is the image page index (0-based), not the pager index.
    val savedImage = prefs.progress(book.id).coerceIn(0, pageCount - 1)
    val initialPage = (if (doublePage) savedImage / 2 else savedImage)
        .coerceIn(0, logicalCount - 1)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.releaseWebtoonCaches() }
    var showUi by remember { mutableStateOf(true) }

    // Back inside the reader peels one layer at a time instead of jumping
    // straight out to the shelf: settings window -> tool bar -> leave book.
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
    // The badge must be REMOVED from the composition when the animation ends.
    // Relying on a layer alpha alone left a persistent grey circle on the page
    // after a tap; an explicit visibility flag makes that impossible.
    var showFlash by remember { mutableStateOf(false) }
    val flashAlpha = remember { Animatable(0f) }
    val flashIn = motionTween<Float>(MotionSpec.SHORT, MotionSpec.EaseOutCubic)
    val flashOut = motionTween<Float>(MotionSpec.MEDIUM, MotionSpec.EaseOutCubic)

    // One effect per tap. Restarting it (rapid taps) cancels the previous run,
    // and the new run always finishes with snapTo(0) + showFlash = false. The
    // timeout is a safety net so the badge can never get stuck on screen.
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

    // `scale` is written on every gesture frame. Reading it directly here used to
    // recompose the whole reader (including the preview strip and slider) 60 times
    // a second while pinching. derivedStateOf only invalidates at the thresholds.
    val zoomed by remember { derivedStateOf { scale > 1.02f } }
    val pageTurnMode = settings.pageAnim
    // The non-sliding animations replace the pager's own travel with an in-place
    // turn. They are switched off while the page is zoomed, where the pager's
    // normal swipe/pan behaviour must stay untouched.
    val useTurnLayer = pageTurnMode != PageAnimation.SLIDE && !zoomed
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

    // reverseLayout already flips the visual order and swipe direction for manga mode.
    // Keeping the pager index as the logical page number avoids a double inversion,
    // which used to make the progress indicator stop at the wrong end.
    fun firstImageIndex(pagerPage: Int): Int =
        if (doublePage) pagerPage * 2 else pagerPage

    val pagerState = rememberPagerState(initialPage = initialPage) { logicalCount }
    val currentPage = pagerState.currentPage.coerceIn(0, logicalCount - 1)

    // Rapid consecutive taps used to turn only one page: animateScrollToPage()
    // keeps currentPage stale for the whole ~250 ms animation, so every tap in
    // that window computed the same target. A synchronous requested-page counter
    // advances before the coroutine starts, and scrollToPage() snaps immediately.
    var requestedPage by remember { mutableIntStateOf(initialPage) }
    var jumpJob by remember { mutableStateOf<Job?>(null) }

    // The visual turn is animated; `requestedPage` is what keeps rapid taps
    // correct, because it advances synchronously and never reads the pager's
    // (deliberately lagging) currentPage.
    val turnSpec = motionTween<Float>(MotionSpec.MEDIUM)

    fun goTo(target: Int) {
        val safe = target.coerceIn(0, logicalCount - 1)
        requestedPage = safe
        jumpJob?.cancel()
        jumpJob = scope.launch { pagerState.animateScrollToPage(safe, animationSpec = turnSpec) }
    }

    fun go(delta: Int) = goTo(requestedPage + delta)

    // A finger swipe (or a slider drag) changes the page without goTo(); adopt it
    // once the pager settles, but never while our own jump is still in flight.
    LaunchedEffect(currentPage) {
        if (jumpJob?.isActive != true && !pagerState.isScrollInProgress) {
            requestedPage = currentPage
        }
    }

    // The page that was on screen before the current turn began. During a turn
    // it is drawn as a solid plate behind the pager: a fade or a flip then
    // always has a real page underneath it, instead of exposing the black
    // reader background in the middle of the animation. It only advances once
    // the pager has fully settled, so the swap is invisible.
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
        go(delta)
        flashSide = side
        flashToken++
    }

    // Changing the zoom mode changes what "1x" means, so any pinch state from the
    // previous mode would be wrong.
    LaunchedEffect(settings.fitMode, settings.zoomPercent) {
        resetZoom()
    }

    LaunchedEffect(currentPage, pageCount) {        // Reading state follows the page you are on, not the furthest page you
        // ever reached. Page 0 means "unread": the record is deleted so the book
        // disappears from the reading tab and loses its shelf progress bar.
        // Merely opening a book therefore leaves no trace either.
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

        LaunchedEffect(currentPage, targetW, targetH) {
            // Warm the two adjacent pages at low priority; no suspended callers,
            // no extra coroutines competing with the visible page.
            listOf(currentPage - 1, currentPage + 1)
                .filter { it in 0 until logicalCount }
                .forEach { neighbour ->
                    vm.prefetch(firstImageIndex(neighbour), targetW, targetH)
                }
        }

        // Opaque underlay for the animated turn. Without it, both the outgoing
        // and the incoming page are partly transparent (or edge-on) halfway
        // through a page change, so the dark reader background shows through as
        // a large black frame. This plate keeps one fully drawn page on screen
        // at every instant.
        if (useTurnLayer) {
            val baseFirst = firstImageIndex(settledPage)
            PageRow(
                vm = vm,
                first = baseFirst,
                showSecond = doublePage && baseFirst + 1 < pageCount,
                targetW = targetW,
                targetH = targetH,
                quality = quality,
                settings = settings,
                modifier = Modifier.fillMaxSize()
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                // Both detectors live on the pager's own modifier chain, in the same
                // order as before. A separate full-screen tap overlay intercepts
                // drags and kills swipe paging, so it must not be used here.
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
                .pointerInput(rtl, zoomed, tapToTurn, logicalCount) {
                    if (zoomed) {
                        // Zoomed in: taps only toggle the toolbar; double tap restores.
                        detectTapGestures(
                            onTap = { showUi = !showUi },
                            onDoubleTap = { resetZoom() }
                        )
                    } else {
                        // No onDoubleTap here: that would delay every onTap by the
                        // double-tap timeout and make edge paging feel unresponsive.
                        detectTapGestures(
                            onTap = { position ->
                                val width = size.width
                                when {
                                    position.x < width * 0.32f -> {
                                        if (tapToTurn) turn(if (rtl) 1 else -1, -1)
                                        else showUi = !showUi
                                    }
                                    position.x > width * 0.68f -> {
                                        if (tapToTurn) turn(if (rtl) -1 else 1, 1)
                                        else showUi = !showUi
                                    }
                                    else -> showUi = !showUi
                                }
                            }
                        )
                    }
                },
            reverseLayout = rtl,
            userScrollEnabled = !zoomed
        ) { pagerPage ->
            val first = firstImageIndex(pagerPage)
            val showSecond = doublePage && first + 1 < pageCount
            // Signed distance from the viewport centre, in page widths. reversing
            // the layout flips the pager's own placement, so the sign is flipped
            // here too. `progress` runs 0 -> 1 as a page travels towards the
            // centre, and stays continuous when currentPage flips at the halfway
            // point of a turn.
            val pos = ((pagerPage - pagerState.currentPage) -
                pagerState.currentPageOffsetFraction) * if (rtl) -1f else 1f
            val progress = (1f - abs(pos)).coerceIn(0f, 1f)
            val isBase = pagerPage == settledPage
            val inRange = abs(pos) <= 1.02f
            // zIndex is a modifier (not a graphicsLayer property): it lifts the
            // incoming page above the settled one, whichever index the pager
            // happens to compose first.
            val layerZ = if (useTurnLayer && !isBase && inRange) 1f else 0f
            // Captured here: inside graphicsLayer the bare name `density` does not
            // resolve to a number (it picks up a function of the same name).
            val screenDensity = LocalDensity.current.density
            PageRow(
                vm = vm,
                first = first,
                showSecond = showSecond,
                targetW = targetW,
                targetH = targetH,
                quality = quality,
                settings = settings,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(layerZ)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                        // Reset everything the turn can touch, so switching back
                        // to "slide" or pinching a page cannot leave a rotated or
                        // transparent layer behind.
                        alpha = 1f
                        rotationY = 0f
                        transformOrigin = TransformOrigin.Center
                        cameraDistance = 8f * screenDensity
                        if (useTurnLayer) {
                            // Cancel the pager's travel with the UNCLAMPED value:
                            // clamping here used to leave pages half a screen off
                            // to the side whenever a jump skipped more than one
                            // page, which is exactly the black gap.
                            translationX -= pos * size.width
                            if (isBase) {
                                // The settled page stays solid; it is the page the
                                // new one turns over.
                                alpha = 1f
                            } else {
                                if (!inRange) {
                                    alpha = 0f
                                } else when (pageTurnMode) {
                                    PageAnimation.FADE -> alpha = progress
                                    PageAnimation.FLIP -> {
                                        // The spine stays on ONE side (left for
                                        // left-to-right books, right for manga),
                                        // whichever way the reader turns. Going
                                        // back is the same fold played in reverse:
                                        // the page swings the other way around
                                        // that same edge, instead of mirroring the
                                        // whole animation onto the opposite side
                                        // and repeating it.
                                        val spineX = if (rtl) 1f else 0f
                                        val towardsNext = if (rtl) pos < 0f else pos > 0f
                                        val fold = (1f - progress) * 68f
                                        rotationY = if (towardsNext) fold else -fold
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

        // Direction feedback for edge taps. No overlay is used here (tap detection
        // lives on the pager itself), and the badge exists in the tree ONLY while
        // its animation runs: showFlash is turned off unconditionally at the end,
        // so a grey circle can never be left behind on the page.
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton(S.back, onBack, filled = false)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = S.pageProgress(firstImageIndex(currentPage), pageCount),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = S.settings,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showUi = false }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = S.closeBar,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

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
                                scope.launch { pagerState.scrollToPage(p) }
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
                        SmallAction(
                            text = S.modeScroll,
                            onClick = { settings.setReadMode(1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
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

/**
 * A horizontally scrollable strip of small page previews, in the spirit of the
 * page-seek bar in desktop/mobile comic viewers. Only visible items are decoded,
 * from a dedicated small-bitmap cache, so a 300-page book stays light.
 * In manga mode the strip is mirrored to match the reading direction.
 */
@Composable
private fun PagePreviewStrip(
    count: Int,
    current: Int,
    rtl: Boolean,
    vm: ReaderViewModel,
    imageIndexOf: (Int) -> Int,
    onPick: (Int) -> Unit
) {
    val state = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme

    // Snap, do not animate: a tween here would fight with rapid page turns and
    // with the user's own scrolling of the strip.
    LaunchedEffect(current) {
        if (!state.isScrollInProgress) {
            state.scrollToItem((current - 2).coerceAtLeast(0))
        }
    }

    LazyRow(
        state = state,
        reverseLayout = rtl,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        items(count, key = { it }) { index ->
            val imageIndex = imageIndexOf(index)
            val bitmap by produceState<Bitmap?>(
                initialValue = vm.peekThumbnail(imageIndex),
                key1 = imageIndex,
                key2 = vm
            ) {
                // `isActive` becomes false as soon as this preview scrolls out of
                // the strip, letting the worker skip the expensive unzip.
                value = vm.thumbnail(imageIndex) { isActive }
            }
            val active = index == current
            Box(
                modifier = Modifier
                    .width(52.dp)
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
                    .padding(3.dp),
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
    active: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(38.dp),
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

/**
 * One logical page (one image, or two side by side). Shared by the pager and by
 * the opaque underlay, so both always show exactly the same fit / zoom / decode.
 */
@Composable
private fun PageRow(
    vm: ReaderViewModel,
    first: Int,
    showSecond: Boolean,
    targetW: Int,
    targetH: Int,
    quality: Int,
    settings: UiSettings,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center
    ) {
        PagePane(
            vm, first, targetW, targetH, quality, settings.smoothing,
            settings.fitMode, settings.zoomPercent, Modifier.weight(1f)
        )
        if (showSecond) {
            PagePane(
                vm, first + 1, targetW, targetH, quality, settings.smoothing,
                settings.fitMode, settings.zoomPercent, Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PagePane(
    vm: ReaderViewModel,
    index: Int,
    targetW: Int,
    targetH: Int,
    quality: Int,
    smoothing: Int,
    zoomMode: Int,
    zoomPercent: Int,
    modifier: Modifier = Modifier
) {
    // "Original size" is the only mode that needs the real pixel dimensions of the
    // artwork; every other mode is scaled from the viewport anyway.
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

    val bitmap by produceState<Bitmap?>(
        // Show the cached (possibly lower-resolution) bitmap immediately while
        // the sharper one loads, instead of blanking the screen with a spinner.
        initialValue = vm.peekPage(index, quality),
        key1 = index,
        key2 = quality,
        key3 = "$decodeW-$decodeH"
    ) {
        value = vm.page(index, decodeW, decodeH, quality)
    }

    // Panning inside one page. Vertical only when the page is simply taller than
    // the screen (horizontal swipes must keep turning pages); both axes once the
    // page is wider than the screen, because then there is nothing else to reach
    // the cropped side with.
    var panX by remember(index, zoomMode, zoomPercent) { mutableStateOf(0f) }
    var panY by remember(index, zoomMode, zoomPercent) { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            val bw = image.width.toFloat().coerceAtLeast(1f)
            val bh = image.height.toFloat().coerceAtLeast(1f)
            val drawW: Float
            val drawH: Float
            when (zoomMode) {
                ZoomMode.ORIGINAL -> {
                    // 1 bitmap pixel = 1 screen pixel.
                    drawW = bw
                    drawH = bh
                }
                ZoomMode.FIT_WIDTH -> {
                    val f = viewportW / bw
                    drawW = viewportW
                    drawH = bh * f
                }
                ZoomMode.FIT_HEIGHT -> {
                    val f = viewportH / bh
                    drawW = bw * f
                    drawH = viewportH
                }
                ZoomMode.FIXED -> {
                    val f = (viewportW * zoomPercent / 100f) / bw
                    drawW = bw * f
                    drawH = bh * f
                }
                ZoomMode.STRETCH -> {
                    drawW = viewportW
                    drawH = viewportH
                }
                ZoomMode.COVER -> {
                    val f = maxOf(viewportW / bw, viewportH / bh)
                    drawW = bw * f
                    drawH = bh * f
                }
                else -> {
                    val f = minOf(viewportW / bw, viewportH / bh)
                    drawW = bw * f
                    drawH = bh * f
                }
            }
            val maxPanX = ((drawW - viewportW) / 2f).coerceAtLeast(0f)
            val maxPanY = ((drawH - viewportH) / 2f).coerceAtLeast(0f)
            val drag = when {
                maxPanX > 1f -> Modifier.pointerInput(index, zoomMode, zoomPercent, maxPanX, maxPanY) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        panX = (panX + dragAmount.x).coerceIn(-maxPanX, maxPanX)
                        panY = (panY + dragAmount.y).coerceIn(-maxPanY, maxPanY)
                    }
                }
                maxPanY > 1f -> Modifier.pointerInput(index, zoomMode, zoomPercent, maxPanY) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        panY = (panY + dragAmount).coerceIn(-maxPanY, maxPanY)
                    }
                }
                else -> Modifier
            }
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = if (smoothing == 0) FilterQuality.High else FilterQuality.None,
                modifier = Modifier
                    // requiredSize, not size: size() clamps to the parent's
                    // constraints, which squashed a page taller/wider than the
                    // viewport instead of letting it overflow and be panned.
                    .requiredSize(
                        with(density) { drawW.toDp() },
                        with(density) { drawH.toDp() }
                    )
                    .graphicsLayer {
                        translationX = panX
                        translationY = panY
                    }
                    .then(drag)
            )
        }
    }
}
