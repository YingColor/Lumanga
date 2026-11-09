package com.yingcolor.comicviewer.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.delay
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
                when (settings.readMode) {
                    1 -> {
                        // Continuous vertical scroll for long Webtoon strips.
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
                        // Continuous horizontal scroll: pages joined into one strip.
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
    // Which side of a freshly turned spread to show first: +1 = left page,
    // -1 = right page. It must NEVER default to 0 (centre) for a spread wider
    // than the screen: panX = 0 puts the gutter between the two pages right in
    // the middle of the viewport, which is exactly what a reader should not
    // see when turning to a new pair of pages. Every way of changing pages
    // (opening a book, edge taps, toolbar buttons, slider) lands on one whole
    // page, never on the seam.
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

    // reverseLayout already flips the visual order and swipe direction for manga mode.
    // Keeping the pager index as the logical page number avoids a double inversion,
    // which used to make the progress indicator stop at the wrong end.
    fun firstImageIndex(pagerPage: Int): Int =
        if (doublePage) pagerPage * 2 else pagerPage

    val pagerState = rememberPagerState(initialPage = initialPage) { logicalCount }
    val currentPage = pagerState.currentPage.coerceIn(0, logicalCount - 1)

    // A synchronous requested-page counter is what makes rapid taps work:
    // animateScrollToPage() keeps currentPage stale for the whole ~220 ms, so
    // every tap in that window would otherwise compute the same target.
    var requestedPage by remember { mutableIntStateOf(initialPage) }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var lastTurnAt by remember { mutableStateOf(0L) }

    val turnSpec = motionTween<Float>(MotionSpec.MEDIUM)

    fun goTo(target: Int) {
        val safe = target.coerceIn(0, logicalCount - 1)
        // Toolbar prev/next/first/last and edge taps all funnel through here.
        // Updating the landing side here means none of them can ever show the
        // gutter in the middle of the screen.
        val delta = safe - pagerState.currentPage
        if (delta != 0) {
            entrySide = (if (delta > 0) 1 else -1) * (if (rtl) -1 else 1)
        }
        val now = SystemClock.uptimeMillis()
        // A single, unhurried tap plays the page-turn animation. Once taps come
        // in a burst (or the previous animation is still running), restarting
        // animateScrollToPage() over and over leaves the pager stranded
        // mid-scroll and pages stop advancing — so snap instead. That is also
        // exactly what "fast page turning" should feel like.
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

    // Base the turn on the pager's real current page. animateScrollToPage() sets
    // currentPage to the target immediately, so every tap advances exactly one page
    // and rapid taps each land on the next one. The old requestedPage counter was
    // re-synced by LaunchedEffects that could run while currentPage was still
    // lagging, pulling the next tap's target back onto the current page — exactly
    // the "needs two taps" symptom.
    fun go(delta: Int) = goTo(pagerState.currentPage + delta)

    // A finger swipe (or a slider drag) changes the page without goTo(); adopt it
    // once the pager settles, but never while our own jump is still in flight,
    // and never in the middle of a burst of taps (that would swallow the pages
    // the user has already queued up).
    LaunchedEffect(currentPage) {
        val burst = SystemClock.uptimeMillis() - lastTurnAt < 420L
        if (!burst && jumpJob?.isActive != true && !pagerState.isScrollInProgress) {
            requestedPage = currentPage
        }
    }

    // After the burst ends, re-align the counter with the real page, so a swipe
    // made right after a burst cannot leave the next tap off by a page.
    LaunchedEffect(lastTurnAt) {
        if (lastTurnAt == 0L) return@LaunchedEffect
        delay(450L)
        if (jumpJob?.isActive != true && !pagerState.isScrollInProgress) {
            requestedPage = currentPage
        }
    }

    // A manual swipe (or slider drag) ends when isScrollInProgress flips to false.
    // currentPage has already advanced by then, but the two sync effects above
    // react to currentPage / lastTurnAt and can miss that exact settle moment,
    // leaving requestedPage stuck on the PRE-swipe page. Re-align here as well:
    // without it, the first tap after a swipe computes its target from the old
    // page and effectively turns nothing, so the page looks like it needs two taps.
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && jumpJob?.isActive != true) {
                    requestedPage = pagerState.currentPage.coerceIn(0, logicalCount - 1)
                }
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
        // reverseLayout flips the visual side of "forward", so the edge a new
        // spread opens on has to be flipped with it.
        entrySide = delta * if (rtl) -1 else 1
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
        // A landscape phone is short (roughly 400 dp tall): the reader toolbar,
        // which is sized for a tall portrait screen, would otherwise cover
        // almost the entire picture. Tablets in landscape still have plenty of
        // height, so they keep the roomy layout.
        val compactBar = maxHeight < 520.dp

        // A tap is only a tap when the finger stays put.
        val tapTolerancePx = with(density) { 2.dp.toPx() }
        // Ask the OS how wide its swipe-back zones are instead of guessing:
        // they differ between phones and navigation modes. Everything inside
        // them is ignored, because on some phones that gesture reaches the app
        // as DOWN followed by an immediate cancel, with no movement at all.
        val gestureInsets = WindowInsets.systemGestures
        val leftGuard = gestureInsets.getLeft(density, LayoutDirection.Ltr)
        val rightGuard = gestureInsets.getRight(density, LayoutDirection.Ltr)
        val gestureGuardPx = maxOf(
            leftGuard.toFloat(),
            rightGuard.toFloat(),
            // Fallback for ROMs that fail to report the gesture insets. A narrow
            // strip is always reserved; the tappable left/right zones are far
            // wider than this, so normal edge paging is unaffected.
            with(density) { 20.dp.toPx() }
        ) + with(density) { 4.dp.toPx() }

        LaunchedEffect(currentPage, targetW, targetH) {
            // Warm the two adjacent pages at low priority; no suspended callers,
            // no extra coroutines competing with the visible page.
            listOf(currentPage - 1, currentPage + 1)
                .filter { it in 0 until logicalCount }
                .forEach { neighbour ->
                    vm.prefetch(firstImageIndex(neighbour), targetW, targetH)
                }
        }

        // Solid plate of the reader background under both pages. The outgoing
        // page now fades out while the incoming one fades in, so around a
        // smaller incoming picture the reader sees the background, never the
        // previous page. Drawing the previous page here (the old cure for a
        // black frame mid-flip) is exactly what used to leak through.
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
                // Tap recognition sits on the FINAL dispatch pass and never
                // consumes an event. A separate full-screen overlay (or a
                // detector that consumes the down) starves the pager's own drag
                // detector, which is what used to kill swipe-to-turn. Watching
                // from the back keeps swipes, pinch-zoom and taps independent.
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
                            // A double tap flips the toolbar twice (back to where
                            // it was) and also restores the zoom.
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
            // Signed distance from the viewport centre, in page widths. reversing
            // the layout flips the pager's own placement, so the sign is flipped
            // here too. `progress` runs 0 -> 1 as a page travels towards the
            // centre, and stays continuous when currentPage flips at the halfway
            // point of a turn.
            val pos = ((pagerPage - pagerState.currentPage) -
                pagerState.currentPageOffsetFraction) * if (rtl) -1f else 1f
            val progress = (1f - abs(pos)).coerceIn(0f, 1f)
            // Progress of the TURN as a whole: 0 while settled, 1 when the new
            // page has arrived. It is measured from the settled page (which does
            // not change mid-turn), so it stays continuous even when
            // currentPage flips at the halfway point. The outgoing page fades
            // out with exactly the complement of the incoming page's alpha, so
            // a smaller incoming canvas can never expose the previous picture.
            val settledPos = ((settledPage - pagerState.currentPage) -
                pagerState.currentPageOffsetFraction) * if (rtl) -1f else 1f
            val turnProgress = abs(settledPos).coerceIn(0f, 1f)
            val incomingAlpha = when (pageTurnMode) {
                PageAnimation.FLIP -> (turnProgress * 1.7f).coerceIn(0f, 1f)
                else -> turnProgress
            }
            val isBase = pagerPage == settledPage
            val inRange = abs(pos) <= 1.02f
            // zIndex is a modifier (not a graphicsLayer property): it decides
            // which sheet is on top, whichever index the pager composes first.
            // In a book flip the sheet being turned AWAY is the top one — it
            // swings off and uncovers the page underneath. Every other turn
            // keeps the incoming page on top.
            val layerZ = if (useTurnLayer && inRange) {
                if (pageTurnMode == PageAnimation.FLIP) {
                    if (isBase) 1f else 0f
                } else if (!isBase) 1f else 0f
            } else 0f
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
                entrySide = entrySide,
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
                                // The page being left fades away at the same
                                // rate the new one appears (complementary alpha).
                                // It used to stay fully opaque, which is why a
                                // smaller incoming picture briefly showed the
                                // previous page around its edges.
                                alpha = 1f - incomingAlpha
                                if (pageTurnMode == PageAnimation.FLIP && turnProgress > 0f) {
                                    // Fading alone is not a page turn: the old
                                    // picture never MOVED, so the animation read
                                    // as "a new picture appearing on top of the
                                    // old one". The outgoing sheet now swings
                                    // away around the same spine while the
                                    // incoming one swings in from the other
                                    // side. Both sheets are visible mid-turn,
                                    // one folding out, one folding in.
                                    val forward = if (rtl) settledPos > 0f else settledPos < 0f
                                    val dir = if (forward) 1f else -1f
                                    val side = if (rtl) -1f else 1f
                                    // By the time the complementary alpha reaches
                                    // zero the sheet is nearly edge-on, so it
                                    // never leaves a flat ghost on the screen.
                                    rotationY = -dir * side * turnProgress * 118f
                                    transformOrigin = TransformOrigin(
                                        if (rtl) 1f else 0f,
                                        0.5f
                                    )
                                    cameraDistance = 12f * screenDensity
                                    // A little travel reinforces the direction
                                    // of the fold: the sheet really leaves the
                                    // viewport instead of rotating in place.
                                    translationX -= dir * side * turnProgress *
                                        size.width * 0.10f
                                }
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
                                        // Flipping the spine to the other edge
                                        // mirrors the perspective too, so the sign
                                        // has to flip with it. Otherwise a
                                        // right-to-left book would "fold down"
                                        // when going forward and "fold up" when
                                        // going back — the exact opposite of a
                                        // left-to-right book. With this factor,
                                        // forward always rises from the bottom
                                        // and back always settles from the top,
                                        // in BOTH reading directions.
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

        // Direction feedback for edge taps. The badge exists in the tree ONLY
        // while its animation runs: showFlash is turned off unconditionally at
        // the end, so a grey circle can never be left behind on the page.
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
                // Each book remembers its own orientation; books without an
                // override inherit the global setting. ComicApp applies only the
                // global value outside the reader, so leaving a book restores it
                // (including "follow system" on tablets).
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
                    // Page number + two icons, shared by both layouts. On a short
                    // landscape screen the icons are shrunk so this row does not
                    // eat a fifth of the available height.
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
                        // A landscape phone is WIDE but SHORT. Stacking every
                        // control vertically used up nearly the whole screen and
                        // hid the comic. Spend the width instead: page previews
                        // and the seek bar on the left, all the buttons on the
                        // right, in three short rows.
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
                    }   // end of the roomy portrait/tablet layout
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
    onPick: (Int) -> Unit,
    compact: Boolean = false
) {
    val state = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme
    val stripHeight = if (compact) 48.dp else 84.dp
    val thumbWidth = if (compact) 30.dp else 52.dp
    val gap = if (compact) 4.dp else 8.dp
    val tilePadding = if (compact) 2.dp else 3.dp

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
                // `isActive` becomes false as soon as this preview scrolls out of
                // the strip, letting the worker skip the expensive unzip.
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
    entrySide: Int = 0,
    modifier: Modifier = Modifier
) {
    // One logical page is ONE picture. In double-page mode the two images are
    // stitched into a single spread first (same height, one shared scale), then
    // the fit mode is applied to that spread as a whole. Splitting the screen
    // into two independent weight(1f) cells made each half crop and pan on its
    // own, which looked like two separate viewers glued together.
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
        modifier = modifier
    )
}

/** Decode one image of the spread, using the same targets as before. */
@Composable
private fun rememberPageBitmap(
    vm: ReaderViewModel,
    index: Int,
    targetW: Int,
    targetH: Int,
    quality: Int,
    zoomMode: Int,
    zoomPercent: Int
): Bitmap? {
    // "Original size" is the only mode that needs the real pixel dimensions of
    // the artwork; every other mode is scaled from the viewport anyway.
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
    // Show the cached (possibly lower-resolution) bitmap immediately while the
    // sharper one loads, instead of blanking the screen with a spinner.
    val state = produceState<Bitmap?>(
        initialValue = vm.peekPage(index, quality),
        key1 = index,
        key2 = quality,
        key3 = "$decodeW-$decodeH"
    ) {
        value = vm.page(index, decodeW, decodeH, quality)
    }
    return state.value
}

/**
 * Draws one logical page: one image, or two images stitched into ONE spread.
 *
 * The stitched picture has a single height, a single scale factor and a single
 * pan offset. Fit / zoom modes are applied to the COMBINED dimensions, never to
 * each half separately, so a double page behaves exactly like one wide picture
 * instead of two independent half-screen viewers with a seam down the middle.
 */
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

        // A fixed number of call sites for a given spread keeps the produceState
        // slots stable across recompositions and page turns.
        val bitmaps = ArrayList<Bitmap?>(indices.size)
        for (i in indices) {
            bitmaps += rememberPageBitmap(vm, i, targetW, targetH, quality, zoomMode, zoomPercent)
        }
        if (bitmaps.any { it == null }) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            // Normalise every page to one common height, keeping its own aspect
            // ratio, then concatenate the widths to get ONE combined picture.
            val commonH = bitmaps.maxOf { it!!.height.toFloat().coerceAtLeast(1f) }
            val widths = FloatArray(bitmaps.size) { k ->
                val b = bitmaps[k]!!
                b.width.toFloat() * (commonH / b.height.toFloat().coerceAtLeast(1f))
            }
            val totalW = widths.sum().coerceAtLeast(1f)

            // The old single-page maths, applied to the combined dimensions.
            val (scaleW, scaleH) = when (zoomMode) {
                ZoomMode.ORIGINAL -> 1f to 1f
                ZoomMode.FIT_WIDTH -> {
                    val f = viewportW / totalW
                    f to f
                }
                ZoomMode.FIT_HEIGHT -> {
                    // "Fit to height" means the combined picture fills the
                    // screen height. On a portrait phone a two-page spread then
                    // becomes wider than the screen; that overflow is panned,
                    // exactly like Fit-to-width overflows vertically. Capping
                    // this by the width would silently turn it into Fit-to-width.
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
                else -> { // FIT_SCREEN
                    val f = minOf(viewportW / totalW, viewportH / commonH)
                    f to f
                }
            }
            val spreadW = totalW * scaleW
            val spreadH = commonH * scaleH
            val maxPanX = ((spreadW - viewportW) / 2f).coerceAtLeast(0f)
            val maxPanY = ((spreadH - viewportH) / 2f).coerceAtLeast(0f)

            // Landing position for a NEW spread: put the CENTRE OF THE
            // VIEWPORT on the centre of the page the reader enters from. For a
            // left-to-right book turning forward that is the left page, so the
            // middle of the screen shows that whole page, not the gutter
            // between the two. Single pages keep the old centred behaviour.
            val firstW = widths[0] * scaleW
            val secondW = if (widths.size > 1) widths[1] * scaleW else 0f
            val landingCentre = when {
                widths.size < 2 -> spreadW / 2f
                entrySide >= 0 -> firstW / 2f
                else -> firstW + secondW / 2f
            }
            val landingPx = (spreadW / 2f - landingCentre)
                .coerceIn(-maxPanX, maxPanX)

            // Only a freshly turned spread is repositioned: after that panX is
            // left alone so dragging keeps exactly where the finger put it.
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
                    // Panning an oversized spread just stops at the edge. Page
                    // turns come from tapping the left / right side of the
                    // screen (or the pager's own swipe when the picture fits);
                    // dragging past the edge must never change the page.
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
                    // requiredSize, not size: size() clamps to the parent's
                    // constraints and would squash a page that needs to overflow.
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
        }
    }
}
