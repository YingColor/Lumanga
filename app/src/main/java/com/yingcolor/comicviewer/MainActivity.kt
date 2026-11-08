package com.yingcolor.comicviewer

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yingcolor.comicviewer.data.Book
import com.yingcolor.comicviewer.data.Prefs
import com.yingcolor.comicviewer.data.UiSettings
import com.yingcolor.comicviewer.theme.ComicTheme
import com.yingcolor.comicviewer.ui.Backdrop
import com.yingcolor.comicviewer.ui.BackdropShot
import com.yingcolor.comicviewer.ui.MotionSpec
import com.yingcolor.comicviewer.ui.ReaderScreen
import com.yingcolor.comicviewer.ui.ReaderViewModel
import com.yingcolor.comicviewer.ui.SHELF_MODE_HOME
import com.yingcolor.comicviewer.ui.SHELF_MODE_READING
import com.yingcolor.comicviewer.ui.SettingsDialog
import com.yingcolor.comicviewer.ui.SettingsRoute
import com.yingcolor.comicviewer.ui.ShelfScreen
import com.yingcolor.comicviewer.ui.ShelfViewModel
import com.yingcolor.comicviewer.ui.motionTween
import com.yingcolor.comicviewer.ui.selectionOf
import com.yingcolor.comicviewer.util.folderLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ComicApp() }
    }
}

/**
 * "Unread" means page 0 / no record; "currently reading" means a real page turn
 * that has not reached the final page. A single snapshot of the progress store
 * keeps this O(n) without three storage reads per book, which matters for a
 * library with tens of thousands of files.
 */
@Composable
private fun currentlyReading(allBooks: List<Book>, prefs: Prefs, version: Int): List<Book> =
    remember(allBooks, prefs, version) {
        val snapshot = prefs.progressSnapshot()
        allBooks.filter { book ->
            val (page, total) = snapshot[book.id.hashCode()] ?: return@filter false
            page > 0 && !(total > 0 && page >= total - 1)
        }
    }

@Composable
private fun ComicApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { Prefs(context) }
    val settings = remember { UiSettings(prefs) }

    // Rotation and an app-only brightness override are global settings: they
    // apply to the shelf as well as the reader, and never change the system's
    // own brightness setting. Portrait is the default: requesting an explicit
    // orientation overrides the system's auto-rotate, so lying on one side no
    // longer turns the whole app sideways.
    LaunchedEffect(settings.screenOrientation) {
        activity?.requestedOrientation = when (settings.screenOrientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(settings.brightness) {
        val window = activity?.window ?: return@LaunchedEffect
        val params = window.attributes
        params.screenBrightness = if (settings.brightness < 0) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            (settings.brightness.coerceIn(1, 100) / 100f).coerceAtLeast(0.01f)
        }
        window.attributes = params
    }
    val shelfVm: ShelfViewModel = viewModel()
    val readerVm: ReaderViewModel = viewModel()

    var progressVersion by remember { mutableStateOf(0) }
    var screen by remember { mutableStateOf("shelf") }
    var currentBook by remember { mutableStateOf<Book?>(null) }
    var settingsRoute by remember { mutableIntStateOf(SettingsRoute.CLOSED) }
    var folders by remember { mutableStateOf(prefs.folderUris) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Frozen, blurred copy of the page shown inside the settings card. It is
    // captured before the card appears, so the glass never reflects itself.
    var backdrop by remember { mutableStateOf<BackdropShot?>(null) }

    val scope = rememberCoroutineScope()
    val homeGridState = rememberLazyGridState()
    val readingGridState = rememberLazyGridState()
    val mainPagerState = rememberPagerState(initialPage = 0) { 2 }
    val mainPage = mainPagerState.currentPage
    val slideSpec = motionTween<IntOffset>(MotionSpec.LONG, MotionSpec.Emphasized)
    val fadeSpec = motionTween<Float>(MotionSpec.MEDIUM, MotionSpec.Emphasized)

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            folders = prefs.addFolder(uri.toString())
            shelfVm.load(folders.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() })
        }
    }

    LaunchedEffect(Unit) {
        if (folders.isNotEmpty()) {
            shelfVm.load(folders.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() })
        }
    }

    // Opening the card first grabs a blurred snapshot of the page: it has to be
    // taken while the card is still invisible, otherwise the glass would show a
    // reflection of itself. The wait is about one frame — the card would have
    // spent it on its own fade-in anyway.
    val openSettings: (Int) -> Unit = { target ->
        val window = activity?.window
        if (window == null) {
            settingsRoute = target
        } else {
            scope.launch {
                backdrop = withTimeoutOrNull(240) { Backdrop.capture(window) }
                settingsRoute = target
            }
        }
    }

    // Changing the palette repaints everything under the card, which would leave
    // the frozen snapshot in the previous theme's colours. The card falls back to
    // its denser look until it is opened again.
    LaunchedEffect(settings.themeMode, settings.themeColor) {
        if (settingsRoute != SettingsRoute.CLOSED) backdrop = null
    }

    val allBooks = shelfVm.books
    val readingBooks = currentlyReading(allBooks, prefs, progressVersion)

    // The reader owns the back button while it is open: inside it, back first
    // closes the settings window, then the tool bar, and only then leaves the
    // book (see ReaderScreen). Here we only handle the shelf.
    BackHandler(
        enabled = screen != "reader" && (settingsRoute != SettingsRoute.CLOSED || mainPage == 1)
    ) {
        when {
            settingsRoute == SettingsRoute.READING ||
                settingsRoute == SettingsRoute.LIBRARIES -> settingsRoute = SettingsRoute.ROOT
            settingsRoute != SettingsRoute.CLOSED -> settingsRoute = SettingsRoute.CLOSED
            mainPage == 1 -> scope.launch { mainPagerState.animateScrollToPage(0) }
        }
    }

    ComicTheme(mode = settings.themeMode, accent = settings.themeColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // The page stays sharp around the settings card; only the card itself
            // is frosted, using a blurred snapshot taken just before it opens.
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState == "reader") {
                        (slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec))
                            .togetherWith(
                                slideOutHorizontally(slideSpec) { -it / 4 } + fadeOut(fadeSpec)
                            )
                    } else {
                        (slideInHorizontally(slideSpec) { -it / 4 } + fadeIn(fadeSpec))
                            .togetherWith(
                                slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec)
                            )
                    }
                },
                label = "screenTransition"
            ) { target ->
                val book = currentBook
                if (target == "reader" && book != null) {
                    ReaderScreen(
                        book = book,
                        vm = readerVm,
                        prefs = prefs,
                        settings = settings,
                        onBack = {
                            screen = "shelf"
                            progressVersion++
                        },
                        onSettings = { openSettings(SettingsRoute.READING) },
                        settingsOpen = settingsRoute != SettingsRoute.CLOSED,
                        onCloseSettings = { settingsRoute = SettingsRoute.CLOSED },
                        onProgress = { page, total -> prefs.setProgress(book.id, page, total) },
                        onResetProgress = { prefs.clearProgress(book.id) }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = mainPagerState,
                            // Keep both tabs composed so switching does not build a whole
                            // new grid (and dozens of covers) inside a single frame.
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) { page ->
                            val readingMode = page == 1
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // Never translate a page here: HorizontalPager
                                        // already parks off-screen pages one full width
                                        // away, and any extra translation pulls them
                                        // back into the viewport (overlapping headers).
                                        val distance = kotlin.math.abs(
                                            mainPagerState.currentPage - page +
                                                mainPagerState.currentPageOffsetFraction
                                        ).coerceIn(0f, 1f)
                                        alpha = 1f - distance * 0.10f
                                    }
                            ) {
                                ShelfScreen(
                                    books = if (readingMode) readingBooks else allBooks,
                                    loading = shelfVm.loading,
                                    restoring = shelfVm.restoring,
                                    error = shelfVm.error,
                                    mode = if (readingMode) SHELF_MODE_READING else SHELF_MODE_HOME,
                                    settings = settings,
                                    prefs = prefs,
                                    vm = shelfVm,
                                    refreshKey = progressVersion,
                                    gridState = if (readingMode) readingGridState else homeGridState,
                                    onPickFolder = { pickFolder.launch(null) },
                                    onManageFolders = { openSettings(SettingsRoute.LIBRARIES) },
                                    onRefresh = { shelfVm.refresh() },
                                    onOpenBook = { selected ->
                                        currentBook = selected
                                        screen = "reader"
                                        readerVm.open(selected)
                                    },
                                    onSettings = { openSettings(SettingsRoute.ROOT) },
                                    onGoHome = { scope.launch { mainPagerState.animateScrollToPage(0) } }
                                )
                            }
                        }
                        MainNavBar(
                            pagerState = mainPagerState,
                            readingCount = readingBooks.size,
                            onSelect = { targetPage ->
                                scope.launch { mainPagerState.animateScrollToPage(targetPage) }
                            }
                        )
                    }
                }
            }

            SettingsDialog(
                route = settingsRoute,
                settings = settings,
                folders = folders.map { it to folderLabel(it) },
                backdrop = backdrop,
                onClose = { settingsRoute = SettingsRoute.CLOSED },
                onOpenReading = { settingsRoute = SettingsRoute.READING },
                onOpenLibraries = { settingsRoute = SettingsRoute.LIBRARIES },
                onOpenPersonalize = { settingsRoute = SettingsRoute.PERSONALIZE },
                onBackToRoot = { settingsRoute = SettingsRoute.ROOT },
                onClearProgress = {
                    prefs.clearProgress()
                    toast = S.cleared
                    progressVersion++
                },
                onAddFolder = { pickFolder.launch(null) },
                onRemoveFolder = { uri ->
                    val next = prefs.removeFolder(uri)
                    if (next.isNotEmpty()) {
                        folders = next
                        shelfVm.load(next.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() })
                    }
                }
            )

            val message = toast
            if (message != null) {
                LaunchedEffect(message) {
                    delay(1800)
                    toast = null
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * Material 3 NavigationBar-style bottom bar: an icon above a label, with a small
 * active-indicator capsule behind the icon that travels continuously with the
 * pager. This is the standard navigation pattern (also used by Kazumi), not a
 * copy of any of their code.
 */
@Composable
private fun MainNavBar(
    pagerState: PagerState,
    readingCount: Int,
    onSelect: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(80.dp)
        ) {
            val itemWidth = maxWidth / 2
            val indicatorWidth = 64.dp
            val travelPx = with(LocalDensity.current) { itemWidth.toPx() }
            val startPx = with(LocalDensity.current) {
                ((itemWidth - indicatorWidth) / 2).toPx()
            }

            Box(
                modifier = Modifier
                    .width(indicatorWidth)
                    .height(32.dp)
                    .align(Alignment.TopStart)
                    .offset(y = 16.dp)
                    .graphicsLayer {
                        val p = (pagerState.currentPage +
                            pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                        translationX = startPx + p * travelPx
                        val bulge = kotlin.math.sin(p * kotlin.math.PI).toFloat()
                        scaleX = 1f + bulge * 0.12f
                        scaleY = 1f - bulge * 0.06f
                    }
                    .clip(CircleShape)
                    .background(scheme.secondaryContainer)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                NavTab(
                    pagerState = pagerState,
                    index = 0,
                    icon = Icons.Outlined.Home,
                    activeIcon = Icons.Filled.Home,
                    label = S.shelf,
                    badge = 0,
                    onClick = { onSelect(0) },
                    modifier = Modifier.weight(1f)
                )
                NavTab(
                    pagerState = pagerState,
                    index = 1,
                    icon = Icons.Outlined.PlayArrow,
                    activeIcon = Icons.Filled.PlayArrow,
                    label = S.reading,
                    badge = readingCount,
                    onClick = { onSelect(1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    pagerState: PagerState,
    index: Int,
    icon: ImageVector,
    activeIcon: ImageVector,
    label: String,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // The continuous pager position is read here rather than in the parent, so a
    // swipe only recomposes these two small tabs. The travelling indicator reads
    // it inside graphicsLayer and never triggers a recomposition at all.
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, 1f)
    val selection = selectionOf(position, index)
    val tint = lerp(scheme.onSurfaceVariant, scheme.onSecondaryContainer, selection)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selection > 0.5f) activeIcon else icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badge > 99) "99" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
