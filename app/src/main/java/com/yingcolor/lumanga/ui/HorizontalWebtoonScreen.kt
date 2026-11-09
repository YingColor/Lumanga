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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.Prefs
import com.yingcolor.lumanga.data.UiSettings
import kotlinx.coroutines.launch






@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HorizontalWebtoonScreen(
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
            val screenW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val screenH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

            var sizes by remember(book.id, vm.pageCount) {
                mutableStateOf<List<Pair<Int, Int>>?>(null)
            }
            LaunchedEffect(book.id, vm.pageCount) {
                
                vm.releaseWebtoonCaches()
                sizes = null
                sizes = vm.pageSizes()
            }
            val ready = sizes
            if (ready == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                HorizontalStrip(
                    book = book,
                    vm = vm,
                    prefs = prefs,
                    settings = settings,
                    sizes = ready,
                    screenW = screenW,
                    screenH = screenH,
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
private fun HorizontalStrip(
    book: Book,
    vm: ReaderViewModel,
    prefs: Prefs,
    settings: UiSettings,
    sizes: List<Pair<Int, Int>>,
    screenW: Float,
    screenH: Float,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    settingsOpen: Boolean,
    onCloseSettings: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onResetProgress: () -> Unit
) {
    val pageCount = sizes.size.coerceAtLeast(1)
    val rtl = settings.readingDirection == 1
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var showUi by remember { mutableStateOf(true) }

    
    
    val widths = remember(sizes, screenH) {
        sizes.map { (w, h) ->
            (w.toFloat() * screenH / h.coerceAtLeast(1).toFloat()).coerceAtLeast(1f)
        }
    }
    val savedPage = remember(book.id) { prefs.progress(book.id) }
        .coerceIn(0, pageCount - 1)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = savedPage)
    var sliderPage by remember { mutableFloatStateOf(savedPage.toFloat()) }

    val currentPage by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
        }
    }
    LaunchedEffect(currentPage, pageCount) {
        if (currentPage > 0) onProgress(currentPage, pageCount) else onResetProgress()
    }
    LaunchedEffect(currentPage) { sliderPage = currentPage.toFloat() }

    fun goToPage(target: Int) {
        val page = target.coerceIn(0, pageCount - 1)
        scope.launch { listState.scrollToItem(page) }
    }

    BackHandler {
        when {
            settingsOpen -> onCloseSettings()
            showUi -> showUi = false
            else -> onBack()
        }
    }

    val tapTolerancePx = with(density) { 2.dp.toPx() }
    val gestureInsets = WindowInsets.systemGestures
    val leftGuard = gestureInsets.getLeft(density, LayoutDirection.Ltr)
    val rightGuard = gestureInsets.getRight(density, LayoutDirection.Ltr)
    val gestureGuardPx = maxOf(
        leftGuard.toFloat(),
        rightGuard.toFloat(),
        with(density) { 20.dp.toPx() }
    ) + with(density) { 4.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        LazyRow(
            state = listState,
            reverseLayout = rtl,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings.tapToTurn, rtl, tapTolerancePx, gestureGuardPx) {
                    observeTaps(tapTolerancePx, gestureGuardPx) { pos ->
                        val x = pos.x
                        val width = size.width.toFloat()
                        
                        
                        val step = screenW * 0.88f
                        when {
                            x < width * 0.30f -> {
                                if (settings.tapToTurn) {
                                    scope.launch {
                                        listState.animateScrollBy(if (rtl) step else -step)
                                    }
                                } else showUi = !showUi
                            }
                            x > width * 0.70f -> {
                                if (settings.tapToTurn) {
                                    scope.launch {
                                        listState.animateScrollBy(if (rtl) -step else step)
                                    }
                                } else showUi = !showUi
                            }
                            else -> showUi = !showUi
                        }
                    }
                }
        ) {
            items(pageCount, key = { it }) { index ->
                HorizontalPageImage(
                    index = index,
                    widthPx = widths[index],
                    heightPx = screenH,
                    vm = vm,
                    smoothing = settings.smoothing
                )
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
                        text = S.scrollHintH,
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
private fun HorizontalPageImage(
    index: Int,
    widthPx: Float,
    heightPx: Float,
    vm: ReaderViewModel,
    smoothing: Int
) {
    val density = LocalDensity.current
    val decodeW = widthPx.toInt().coerceIn(256, 2048)
    val decodeH = heightPx.toInt().coerceIn(256, 4096)
    val bitmap by produceState<Bitmap?>(
        initialValue = vm.peekPage(index, 0),
        key1 = index,
        key2 = "$decodeW-$decodeH"
    ) {
        value = vm.page(index, decodeW, decodeH, 0)
    }
    Box(
        modifier = Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .clipToBounds()
            .background(Color(0xFF101014)),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = if (smoothing == 0) {
                    FilterQuality.High
                } else FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
