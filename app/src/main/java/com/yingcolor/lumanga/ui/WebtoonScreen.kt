package com.yingcolor.lumanga.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.Prefs
import com.yingcolor.lumanga.data.UiSettings
import com.yingcolor.lumanga.data.ZoomMode
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch










private data class StripTile(
    val page: Int,
    val slice: Int,
    val top: Int,
    val bottom: Int,
    val targetWidthPx: Int,
    val shownHeightPx: Int
)

private const val TILE_HEIGHT_PX = 1024
private const val MAX_SLICES_PER_PAGE = 200

private fun buildTiles(
    sizes: List<Pair<Int, Int>>,
    screenW: Int,
    screenH: Int,
    zoomMode: Int,
    zoomPercent: Int
): Pair<List<StripTile>, IntArray> {
    val tiles = ArrayList<StripTile>(sizes.size * 2)
    val starts = IntArray(sizes.size)
    val maxWidth = (screenW * 1.6f).toInt()
    sizes.forEachIndexed { page, (rawW, rawH) ->
        starts[page] = tiles.size
        val srcW = rawW.coerceAtLeast(1)
        val srcH = rawH.coerceAtLeast(1)
        
        
        
        val displayW = when (zoomMode) {
            ZoomMode.ORIGINAL -> srcW.coerceIn(1, maxWidth)
            ZoomMode.FIXED -> (screenW.toLong() * zoomPercent / 100).toInt()
            ZoomMode.FIT_HEIGHT -> (srcW.toLong() * screenH / srcH).toInt()
                .coerceIn((screenW * 0.25f).toInt().coerceAtLeast(1), screenW * 2)
            else -> screenW
        }.coerceAtLeast(1)
        val scale = displayW.toFloat() / srcW
        val shownH = (srcH * scale).toInt().coerceAtLeast(1)
        val count = ((shownH + TILE_HEIGHT_PX - 1) / TILE_HEIGHT_PX)
            .coerceIn(1, MAX_SLICES_PER_PAGE)
        for (i in 0 until count) {
            val top = (i.toLong() * srcH / count).toInt()
            val bottom = ((i + 1).toLong() * srcH / count).toInt()
            if (bottom <= top) continue
            val shown = ((bottom - top) * scale).toInt().coerceAtLeast(1)
            tiles.add(StripTile(page, i, top, bottom, displayW, shown))
        }
        if (starts[page] == tiles.size) {
            tiles.add(StripTile(page, 0, 0, srcH, displayW, shownH))
        }
    }
    return tiles to starts
}

@Composable
fun WebtoonScreen(
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
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101014))) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val screenW = with(density) { maxWidth.toPx().toInt() }.coerceAtLeast(1)
            val screenH = with(density) { maxHeight.toPx().toInt() }.coerceAtLeast(1)

            
            
            var sizes by remember(book.id, vm.pageCount) {
                mutableStateOf<List<Pair<Int, Int>>?>(null)
            }
            LaunchedEffect(book.id, vm.pageCount) {
                vm.releasePageModeCaches()
                sizes = null
                sizes = vm.pageSizes()
            }

            val ready = sizes
            if (ready == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = S.webtoonLoading,
                            color = Color(0xFFB9B3C4),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                val plan = remember(ready, screenW, screenH, settings.fitMode, settings.zoomPercent) {
                    buildTiles(ready, screenW, screenH, settings.fitMode, settings.zoomPercent)
                }
                WebtoonContent(
                    book = book,
                    vm = vm,
                    prefs = prefs,
                    settings = settings,
                    tiles = plan.first,
                    pageStarts = plan.second,
                    screenH = screenH,
                    screenW = screenW,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebtoonContent(
    book: Book,
    vm: ReaderViewModel,
    prefs: Prefs,
    settings: UiSettings,
    tiles: List<StripTile>,
    pageStarts: IntArray,
    screenH: Int,
    screenW: Int,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    settingsOpen: Boolean,
    onCloseSettings: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onResetProgress: () -> Unit
) {
    if (tiles.isEmpty()) return
    
    
    
    val density = LocalDensity.current
    val tapTolerancePx = with(density) { 2.dp.toPx() }
    
    
    
    val gestureInsets = WindowInsets.systemGestures
    val leftGuard = gestureInsets.getLeft(density, LayoutDirection.Ltr)
    val rightGuard = gestureInsets.getRight(density, LayoutDirection.Ltr)
    val gestureGuardPx = maxOf(
        leftGuard.toFloat(),
        rightGuard.toFloat(),
        
        
        with(density) { 20.dp.toPx() }
    ) + with(density) { 4.dp.toPx() }
    val pageCount = vm.pageCount.coerceAtLeast(1)
    val savedPage = remember(book.id) { prefs.progress(book.id) }
        .coerceIn(0, pageCount - 1)
    val initialTile = pageStarts.getOrElse(savedPage) { 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialTile)
    val scope = rememberCoroutineScope()
    var showUi by remember { mutableStateOf(true) }

    
    BackHandler {
        when {
            settingsOpen -> onCloseSettings()
            showUi -> showUi = false
            else -> onBack()
        }
    }
    var sliderPage by remember { mutableFloatStateOf(savedPage.toFloat()) }

    
    
    
    
    val widestStrip = remember(tiles) { tiles.maxOfOrNull { it.targetWidthPx } ?: 0 }
    val extraX = ((widestStrip - screenW) / 2f).coerceAtLeast(0f)
    var panX by remember(widestStrip) { mutableStateOf(0f) }

    
    
    val currentPage by remember(tiles) {
        derivedStateOf {
            val index = listState.firstVisibleItemIndex
                .coerceIn(0, (tiles.size - 1).coerceAtLeast(0))
            tiles.getOrNull(index)?.page ?: 0
        }
    }

    LaunchedEffect(currentPage, pageCount) {
        if (currentPage > 0) onProgress(currentPage, pageCount) else onResetProgress()
    }
    LaunchedEffect(currentPage) { sliderPage = currentPage.toFloat() }
    LaunchedEffect(tiles) {
        listState.scrollToItem(pageStarts.getOrElse(currentPage) { 0 })
    }

    fun goToPage(target: Int) {
        val page = target.coerceIn(0, pageCount - 1)
        scope.launch { listState.scrollToItem(pageStarts.getOrElse(page) { 0 }) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(extraX) {
                if (extraX > 0f) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        panX = (panX + dragAmount).coerceIn(-extraX, extraX)
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 130.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panX }
                
                
                
                
                .pointerInput(
                    settings.tapToTurn, tapTolerancePx, gestureGuardPx
                ) {
                    observeTaps(tapTolerancePx, gestureGuardPx) { pos ->
                        val x = pos.x
                        val width = size.width.toFloat()
                        when {
                            x < width * 0.30f -> {
                                if (settings.tapToTurn) {
                                    scope.launch { listState.animateScrollBy(-screenH * 0.88f) }
                                } else showUi = !showUi
                            }
                            x > width * 0.70f -> {
                                if (settings.tapToTurn) {
                                    scope.launch { listState.animateScrollBy(screenH * 0.88f) }
                                } else showUi = !showUi
                            }
                            else -> showUi = !showUi
                        }
                    }
                }
        ) {
            items(tiles.size, key = { i -> "${tiles[i].page}-${tiles[i].slice}" }) { i ->
                StripTileImage(tiles[i], vm, settings)
            }
        }

        AnimatedVisibility(
            visible = showUi,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
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
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
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
                            text = S.pageProgress(currentPage, pageCount),
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = sliderPage.coerceIn(0f, (pageCount - 1).coerceAtLeast(1).toFloat()),
                            onValueChange = { sliderPage = it },
                            onValueChangeFinished = { goToPage(sliderPage.toInt()) },
                            valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        Text(pageCount.toString(), style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction(S.firstPage, { goToPage(0) }, Modifier.weight(1f))
                        SmallAction(S.previous, { goToPage(currentPage - 1) }, Modifier.weight(1f))
                        SmallAction(S.next, { goToPage(currentPage + 1) }, Modifier.weight(1f))
                        SmallAction(S.lastPage, { goToPage(pageCount - 1) }, Modifier.weight(1f))
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
                        text = S.scrollHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
private fun StripTileImage(
    tile: StripTile,
    vm: ReaderViewModel,
    settings: UiSettings
) {
    val density = LocalDensity.current
    val shownH = with(density) { tile.shownHeightPx.toDp() }
    val shownW = with(density) { tile.targetWidthPx.toDp() }
    val align = when (settings.alignment) {
        1 -> Alignment.CenterStart
        2 -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(shownH)
            .clipToBounds()
            .background(Color(0xFF101014)),
        contentAlignment = align
    ) {
        val bitmap by produceState<Bitmap?>(
            initialValue = vm.peekTile(tile.page, tile.slice, tile.targetWidthPx),
            key1 = tile.page,
            key2 = tile.slice,
            key3 = tile.targetWidthPx
        ) {
            value = vm.tile(
                tile.page, tile.slice, tile.top, tile.bottom, tile.targetWidthPx
            ) { isActive }.await()
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = if (settings.smoothing == 0) {
                    FilterQuality.High
                } else FilterQuality.None,
                
                
                modifier = Modifier.requiredSize(shownW, shownH)
            )
        } else {
            Text(
                text = (tile.page + 1).toString(),
                fontSize = 12.sp,
                color = Color(0xFF6E6878)
            )
        }
    }
}
