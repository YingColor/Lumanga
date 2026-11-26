package com.yingcolor.lumanga

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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Home
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
import com.yingcolor.lumanga.data.BatchPreTranslate
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.BubbleSegmenter
import com.yingcolor.lumanga.data.MangaOcr
import com.yingcolor.lumanga.data.RoutingOcrEngine
import com.yingcolor.lumanga.data.Prefs
import com.yingcolor.lumanga.data.TranslationEngine
import com.yingcolor.lumanga.data.UiSettings
import com.yingcolor.lumanga.theme.ComicTheme
import com.yingcolor.lumanga.ui.Backdrop
import com.yingcolor.lumanga.ui.BackdropShot
import com.yingcolor.lumanga.ui.MotionSpec
import com.yingcolor.lumanga.ui.PreTranslateDialog
import com.yingcolor.lumanga.ui.ReaderScreen
import com.yingcolor.lumanga.ui.ReaderViewModel
import com.yingcolor.lumanga.ui.SettingsDialog
import com.yingcolor.lumanga.ui.TranslateConfigDialog
import com.yingcolor.lumanga.ui.SettingsRoute
import com.yingcolor.lumanga.ui.ShelfScreen
import com.yingcolor.lumanga.ui.ShelfViewModel
import com.yingcolor.lumanga.ui.motionTween
import com.yingcolor.lumanga.ui.selectionOf
import com.yingcolor.lumanga.util.folderLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The reader build never translates, so none of the recognition machinery is wired up:
        // no OCR engine, no recogniser init, no segmenter, no pre-translate receiver. Keeping
        // these out also lets the shrinker drop the whole pipeline from that APK.
        if (BuildConfig.HAS_TRANSLATION) {
            TranslationEngine.ocr = RoutingOcrEngine(this)
            MangaOcr.init(this)
            BubbleSegmenter.attach(this)
            BatchPreTranslate.attach(this)
        }
        enableEdgeToEdge()
        setContent { ComicApp() }
    }
}







/**
 * The pages the bottom bar switches between.
 *
 * Only the shelf ships today, so the bar stays hidden and the shelf gets the whole screen. The
 * pager and the bar are both driven by this list: another home page is one entry here plus its
 * branch in the pager body, and the bar reappears on its own.
 */
private enum class HomePage(
    val icon: ImageVector,
    val activeIcon: ImageVector
) {
    SHELF(Icons.Outlined.Home, Icons.Filled.Home);

    /** Read when drawn, not when the enum loads: the language is applied in onCreate. */
    val label: String get() = when (this) {
        SHELF -> S.shelf
    }
}

private val HOME_PAGES = listOf(HomePage.SHELF)

@Composable
private fun ComicApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { Prefs(context) }
    // Language first: everything below reads its text through [S], and switching language
    // recreates the activity so this runs again with the new choice.
    remember(prefs) { S.applyStoredLanguage(prefs.langKey) }
    val settings = remember { UiSettings(prefs) }

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
    var translateConfigOpen by remember { mutableStateOf(false) }
    // Set from the "预翻译整本" shortcut inside the translate config dialog.
    var preTranslateBook by remember { mutableStateOf<Book?>(null) }
    var folders by remember { mutableStateOf(prefs.folderUris) }
    var toast by remember { mutableStateOf<String?>(null) }

    
    
    
    
    val perBookOrientation = if (screen == "reader") {
        currentBook?.let { settings.bookOrientation(it.id) } ?: -1
    } else -1
    val activeOrientation =
        if (perBookOrientation >= 0) perBookOrientation else settings.screenOrientation
    LaunchedEffect(activeOrientation, settings.bookOrientationRev) {
        activity?.requestedOrientation = when (activeOrientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    
    
    var backdrop by remember { mutableStateOf<BackdropShot?>(null) }

    val scope = rememberCoroutineScope()
    val homeGridState = rememberLazyGridState()
    val mainPagerState = rememberPagerState(initialPage = 0) { HOME_PAGES.size }
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

    
    
    
    LaunchedEffect(settings.themeMode, settings.themeColor) {
        if (settingsRoute != SettingsRoute.CLOSED) backdrop = null
    }

    val allBooks = shelfVm.books

    
    
    
    BackHandler(
        enabled = screen != "reader" && (settingsRoute != SettingsRoute.CLOSED || mainPage != 0)
    ) {
        when {
            settingsRoute == SettingsRoute.READING ||
                settingsRoute == SettingsRoute.LIBRARIES -> settingsRoute = SettingsRoute.ROOT
            settingsRoute != SettingsRoute.CLOSED -> settingsRoute = SettingsRoute.CLOSED
            mainPage != 0 -> scope.launch { mainPagerState.animateScrollToPage(0) }
        }
    }

    ComicTheme(mode = settings.themeMode, accent = settings.themeColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            
            
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
                        onTranslateConfig = { translateConfigOpen = true },
                        settingsOpen = settingsRoute != SettingsRoute.CLOSED,
                        onCloseSettings = { settingsRoute = SettingsRoute.CLOSED },
                        onProgress = { page, total -> prefs.setProgress(book.id, page, total) },
                        onResetProgress = { prefs.clearProgress(book.id) }
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = mainPagerState,
                            // Pages change by swiping as well as by tapping the bar, so another
                            // home page needs no extra gesture code.
                            userScrollEnabled = HOME_PAGES.size > 1,
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) { page ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        
                                        
                                        
                                        
                                        val distance = kotlin.math.abs(
                                            mainPagerState.currentPage - page +
                                                mainPagerState.currentPageOffsetFraction
                                        ).coerceIn(0f, 1f)
                                        alpha = 1f - distance * 0.10f
                                    }
                            ) {
                                when (HOME_PAGES[page]) {
                                HomePage.SHELF -> ShelfScreen(
                                    books = allBooks,
                                    loading = shelfVm.loading,
                                    restoring = shelfVm.restoring,
                                    error = shelfVm.error,
                                    roots = folders,
                                    settings = settings,
                                    prefs = prefs,
                                    vm = shelfVm,
                                    refreshKey = progressVersion,
                                    gridState = homeGridState,
                                    onPickFolder = { pickFolder.launch(null) },
                                    onManageFolders = { openSettings(SettingsRoute.LIBRARIES) },
                                    onRefresh = {
                                        shelfVm.refresh { toast = S.refreshDone }
                                    },
                                    onOpenBook = { selected ->
                                        currentBook = selected
                                        screen = "reader"
                                        readerVm.open(selected)
                                    },
                                    onSettings = { openSettings(SettingsRoute.ROOT) },
                                    onTranslateConfig = { translateConfigOpen = true }
                                )
                                }
                            }
                        }
                        // One page means nothing to switch between, so the bar stays out of the
                        // way and only the system inset is kept. Adding a page brings it back.
                        if (HOME_PAGES.size > 1) {
                            MainNavBar(
                                pagerState = mainPagerState,
                                pages = HOME_PAGES,
                                onSelect = { targetPage ->
                                    scope.launch { mainPagerState.animateScrollToPage(targetPage) }
                                }
                            )
                        } else {
                            Spacer(modifier = Modifier.navigationBarsPadding())
                        }
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
                },
                onLanguageChange = { lang ->
                    prefs.langKey = lang.tag
                    // Nothing on screen is reactive to the language, so the cleanest switch is a
                    // recreate: every composable is rebuilt with the new table.
                    activity?.recreate()
                }
            )

            if (BuildConfig.HAS_TRANSLATION && translateConfigOpen) {
                TranslateConfigDialog(
                    settings = settings,
                    onDismiss = { translateConfigOpen = false },
                    // Inside a volume the panel also offers the per-volume pre-translate, so
                    // that shortcut is not lost now that the long press opens this dialog.
                    onPreTranslate = currentBook?.takeIf { screen == "reader" }?.let { open ->
                        {
                            translateConfigOpen = false
                            preTranslateBook = open
                        }
                    }
                )
            }

            if (BuildConfig.HAS_TRANSLATION) preTranslateBook?.let { volume ->
                PreTranslateDialog(
                    book = volume,
                    settings = settings,
                    onDismiss = { preTranslateBook = null }
                )
            }

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







@Composable
private fun MainNavBar(
    pagerState: PagerState,
    pages: List<HomePage>,
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
                .height(60.dp)
        ) {
            val itemWidth = maxWidth / pages.size.coerceAtLeast(1)
            val indicatorWidth = 56.dp
            val travelPx = with(LocalDensity.current) { itemWidth.toPx() }
            val startPx = with(LocalDensity.current) {
                ((itemWidth - indicatorWidth) / 2).toPx()
            }

            Box(
                modifier = Modifier
                    .width(indicatorWidth)
                    .height(28.dp)
                    .align(Alignment.TopStart)
                    .offset(y = 16.dp)
                    .graphicsLayer {
                        val raw = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val p = (raw / (pages.size - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                        translationX = startPx + p * travelPx * (pages.size - 1)
                        val bulge = kotlin.math.sin(
                            (raw - raw.toInt()) * kotlin.math.PI
                        ).toFloat()
                        scaleX = 1f + bulge * 0.12f
                        scaleY = 1f - bulge * 0.06f
                    }
                    .clip(CircleShape)
                    .background(scheme.secondaryContainer)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                pages.forEachIndexed { index, page ->
                    NavTab(
                        pagerState = pagerState,
                        index = index,
                        icon = page.icon,
                        activeIcon = page.activeIcon,
                        label = page.label,
                        badge = 0,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
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
        Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selection > 0.5f) activeIcon else icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
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
