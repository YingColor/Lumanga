package com.yingcolor.lumanga.ui

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yingcolor.lumanga.BuildConfig
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.BatchPreTranslate
import com.yingcolor.lumanga.data.BatchState
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.BookKind
import com.yingcolor.lumanga.data.ComicRepository
import com.yingcolor.lumanga.data.Prefs
import com.yingcolor.lumanga.data.TranslationStore
import com.yingcolor.lumanga.data.ShelfIndex
import com.yingcolor.lumanga.data.UiSettings
import com.yingcolor.lumanga.util.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SHELF_MODE_HOME = 0
const val SHELF_MODE_READING = 1

private val ExpandableHeaderHeight = 116.dp
private const val HEADER_SCROLL_RATIO = 0.6f
private val FavoritePink = Color(0xFFFF4D87)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ComicRepository(app)
    private val index = ShelfIndex(app)
    private var loadedKey: String? = null
    private var loadJob: Job? = null
    private var lastVerifiedAt = 0L
    
    private var loadToken = 0

    var books by mutableStateOf<List<Book>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    
    var refreshing by mutableStateOf(false)
        private set
    
    var restoring by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    
    var filterGroup by mutableStateOf("")

    
    var readFilter by mutableStateOf(0)

    




    fun load(
        roots: List<Uri>,
        force: Boolean = false,
        onDone: (() -> Unit)? = null
    ) {
        if (roots.isEmpty()) {
            loadJob?.cancel()
            loadedKey = null
            books = emptyList()
            loading = false
            refreshing = false
            onDone?.invoke()
            return
        }
        val folder = roots.joinToString("|") { it.toString() }
        val sameFolder = loadedKey == folder
        if (sameFolder && loadJob?.isActive == true) {
            onDone?.invoke()
            return
        }
        
        if (sameFolder && !force && books.isNotEmpty() &&
            System.currentTimeMillis() - lastVerifiedAt < VERIFY_INTERVAL_MS
        ) {
            onDone?.invoke()
            return
        }

        loadedKey = folder
        loadJob?.cancel()
        restoring = false
        
        
        val token = ++loadToken
        loadJob = viewModelScope.launch {
            try {
                error = null
                
                
                
                
                if (!sameFolder) books = emptyList()
                restoring = !force
                val cached = withContext(Dispatchers.IO) {
                    if (force) null else index.load(folder)
                }
                restoring = false
                if (cached.isNullOrEmpty()) {
                    if (books.isEmpty()) loading = true
                } else {
                    books = cached
                    loading = false
                }

                val result = withContext(Dispatchers.IO) {
                    runCatching { repo.listBooks(roots) }
                }
                result.fold(
                    onSuccess = { scanned ->
                        
                        
                        if (scanned.isEmpty() && books.isNotEmpty()) {
                            lastVerifiedAt = System.currentTimeMillis()
                        } else {
                            if (!sameContent(books, scanned)) {
                                books = scanned
                                withContext(Dispatchers.IO) { index.save(folder, scanned) }
                            }
                            lastVerifiedAt = System.currentTimeMillis()
                        }
                    },
                    onFailure = { failure ->
                        if (books.isEmpty()) error = failure.message ?: S.folderError
                    }
                )
                loading = false
            } finally {
                if (token == loadToken) {
                    refreshing = false
                    onDone?.invoke()
                }
            }
        }
    }

    
    fun refresh(onDone: (() -> Unit)? = null) {
        val folder = loadedKey
        if (folder == null) {
            onDone?.invoke()
            return
        }
        val roots = folder.split("|").mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (roots.isEmpty()) {
            onDone?.invoke()
            return
        }
        refreshing = true
        load(roots, force = true, onDone = onDone)
    }

    
    fun refresh() {
        val folder = loadedKey ?: return
        val roots = folder.split("|").mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (roots.isEmpty()) return
        load(roots, force = true)
    }

    private fun sameContent(current: List<Book>, scanned: List<Book>): Boolean {
        if (current.size != scanned.size) return false
        val known = HashMap<String, Book>(current.size)
        current.forEach { known[it.id] = it }
        for (book in scanned) {
            val old = known[book.id] ?: return false
            if (old.name != book.name ||
                old.sizeBytes != book.sizeBytes ||
                old.lastModified != book.lastModified ||
                old.kind != book.kind ||
                old.group != book.group
            ) return false
        }
        return true
    }

    suspend fun cover(book: Book, targetPx: Int): Bitmap? = repo.cover(book, targetPx)

    fun peekCover(book: Book, targetPx: Int): Bitmap? = repo.peekCover(book.id, targetPx)

    private companion object {
        const val VERIFY_INTERVAL_MS = 30_000L
    }
}

private fun sortBooks(list: List<Book>, mode: Int, prefs: Prefs): List<Book> = when (mode) {
    0 -> list.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    1 -> list.sortedWith { a, b -> naturalCompare(b.name, a.name) }
    2 -> list.sortedByDescending { it.lastModified }
    3 -> list.sortedBy { it.lastModified }
    4 -> list.sortedByDescending { it.sizeBytes }
    5 -> list.sortedBy { it.sizeBytes }
    6 -> list.sortedByDescending { prefs.lastRead(it.id) }
    else -> list
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    books: List<Book>,
    loading: Boolean,
    restoring: Boolean,
    error: String?,
    mode: Int,
    settings: UiSettings,
    prefs: Prefs,
    vm: ShelfViewModel,
    refreshKey: Int,
    gridState: LazyGridState,
    onPickFolder: () -> Unit,
    onManageFolders: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onSettings: () -> Unit,
    onTranslateConfig: () -> Unit,
    onGoHome: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // The search field is folded away behind a magnifier until it is needed. Opening it hides
    // the chips to its left, so the field can take the whole middle of the bar.
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) runCatching { searchFocus.requestFocus() }
    }
    var sortMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var columnMenu by remember { mutableStateOf(false) }
    var readFilterMenu by remember { mutableStateOf(false) }
    var preTarget by remember { mutableStateOf<Book?>(null) }
    var preDeleteTarget by remember { mutableStateOf<Book?>(null) }
    var batchOpen by remember { mutableStateOf(false) }
    var deleteTaps by remember { mutableIntStateOf(0) }
    var lastDeleteTapAt by remember { mutableStateOf(0L) }
    val shelfScope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    
    
    
    val sorted = remember(books, settings.sortMode, refreshKey) {
        sortBooks(books, settings.sortMode, prefs)
    }
    
    
    val groups = remember(sorted) {
        sorted.asSequence()
            .map { it.group }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith { a, b -> naturalCompare(a, b) }
            .toList()
    }
    
    
    val groupFilter = vm.filterGroup.takeIf { it in groups } ?: ""
    val readFilter = vm.readFilter
    val favorites = settings.favorites
    val displayed = remember(sorted, query, groupFilter, readFilter, favorites, refreshKey) {
        val base = if (groupFilter.isEmpty()) sorted else sorted.filter { it.group == groupFilter }
        val q = query.trim()
        val searched = if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
        
        
        if (mode == SHELF_MODE_READING || readFilter == 0) searched
        else if (readFilter == 3) searched.filter { it.id in favorites }
        else searched.filter { book ->
            val page = prefs.progress(book.id)
            val total = prefs.pageCount(book.id)
            val finished = total > 0 && page >= total - 1
            if (readFilter == 1) finished else page > 0 && !finished
        }
    }
    val scheme = MaterialTheme.colorScheme
    val columns = settings.gridColumns
    val batchState by BatchPreTranslate.state.collectAsState()

    
    
    
    
    
    val maxCollapsePx = with(LocalDensity.current) { ExpandableHeaderHeight.toPx() }
    val collapseState = remember { mutableFloatStateOf(0f) }
    val scrollConnection = remember(maxCollapsePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val before = collapseState.floatValue
                val after = (before - available.y * HEADER_SCROLL_RATIO / maxCollapsePx)
                    .coerceIn(0f, 1f)
                collapseState.floatValue = after
                return Offset(0f, -(after - before) * maxCollapsePx)
            }
        }
    }
    val gridScope = rememberCoroutineScope()
    
    
    
    
    var drawerOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val showTopButton by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            
            
            
            CollapsingHeader(
                collapse = collapseState,
                mode = mode,
                loading = loading,
                restoring = restoring,
                count = displayed.size
            )

            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                
                
                
                IconButton(onClick = { drawerOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = S.menu,
                        tint = scheme.onSurfaceVariant
                    )
                }
                if (searchExpanded) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(S.search, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocus)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // Search replaces every chip to its left; while it is open only the menu button,
                // the field, the search toggle and Refresh remain.
                if (!searchExpanded) {
                if (groups.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box {
                        MiniChip(
                            text = groupFilter.ifEmpty { S.category },
                            active = groupFilter.isNotEmpty(),
                            onClick = { groupMenu = true }
                        )
                        DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Text(
                                        text = if (groupFilter.isEmpty()) "✓" else " ",
                                        color = scheme.primary
                                    )
                                },
                                text = { Text(S.categoryAll) },
                                onClick = {
                                    vm.filterGroup = ""
                                    groupMenu = false
                                }
                            )
                            groups.forEach { name ->
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Text(
                                            text = if (groupFilter == name) "✓" else " ",
                                            color = scheme.primary
                                        )
                                    },
                                    text = { Text(name) },
                                    onClick = {
                                        vm.filterGroup = name
                                        groupMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Box {
                    MiniChip(text = S.sort, onClick = { sortMenu = true })
                    DropdownMenu(
                        expanded = sortMenu,
                        onDismissRequest = { sortMenu = false }
                    ) {
                        S.sortOptions.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Text(
                                        text = if (index == settings.sortMode) "✓" else " ",
                                        color = scheme.primary
                                    )
                                },
                                text = { Text(label) },
                                onClick = {
                                    settings.setSortMode(index)
                                    sortMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    MiniChip(
                        text = S.columnOptions[columns.coerceIn(2, 5) - 2],
                        onClick = { columnMenu = true }
                    )
                    DropdownMenu(
                        expanded = columnMenu,
                        onDismissRequest = { columnMenu = false }
                    ) {
                        S.columnOptions.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Text(
                                        text = if (index + 2 == columns) "✓" else " ",
                                        color = scheme.primary
                                    )
                                },
                                text = { Text(label) },
                                onClick = {
                                    settings.setGridColumns(index + 2)
                                    columnMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    MiniChip(
                        text = S.readFilterOptions[readFilter.coerceIn(0, 3)],
                        active = readFilter != 0,
                        onClick = { readFilterMenu = true }
                    )
                    DropdownMenu(
                        expanded = readFilterMenu,
                        onDismissRequest = { readFilterMenu = false }
                    ) {
                        S.readFilterOptions.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Text(
                                        text = if (index == readFilter) "✓" else " ",
                                        color = scheme.primary
                                    )
                                },
                                trailingIcon = if (index == 3) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = null,
                                            tint = FavoritePink,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                text = { Text(label) },
                                onClick = {
                                    vm.readFilter = index
                                    readFilterMenu = false
                                }
                            )
                        }
                    }
                }
                }
                // The magnifier sits immediately left of Refresh. Tapping it again folds the
                // field away and clears the filter, so the shelf cannot stay hidden by a query
                // the user can no longer see.
                IconButton(onClick = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) query = ""
                }) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Filled.Close
                        else Icons.Filled.Search,
                        contentDescription = if (searchExpanded) S.searchClose else S.search,
                        tint = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = S.refresh,
                        tint = scheme.onSurfaceVariant
                    )
                }
            }

            
            
            

            if (batchState.running || batchState.pending.isNotEmpty()) {
                BatchBanner(state = batchState, onClick = { batchOpen = true })
            }

            when {
                error != null -> EmptyState(
                    title = S.folderError,
                    body = error,
                    onPickFolder = onPickFolder
                )
                
                restoring -> Box(Modifier.fillMaxSize())
                books.isEmpty() && !loading -> if (mode == SHELF_MODE_READING) {
                    ReadingEmptyState(onGoHome = onGoHome)
                } else {
                    EmptyState(
                        title = S.emptyTitle,
                        body = S.emptyBody,
                        tip = S.emptyTip,
                        onPickFolder = onPickFolder
                    )
                }
                displayed.isEmpty() && !loading -> EmptyState(
                    title = S.noResult,
                    body = S.search,
                    onPickFolder = onPickFolder,
                    showChooseButton = false
                )
                else -> PullToRefreshBox(
                    isRefreshing = vm.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(
                            start = if (columns >= 4) 12.dp else 18.dp,
                            end = if (columns >= 4) 12.dp else 18.dp,
                            top = 8.dp,
                            bottom = 80.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(
                            if (columns >= 4) 8.dp else 14.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(
                            if (columns >= 4) 12.dp else 18.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayed, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                columns = columns,
                                prefs = prefs,
                                vm = vm,
                                refreshKey = refreshKey,
                                favorite = book.id in favorites,
                                onToggleFavorite = { settings.toggleFavorite(book.id) },
                                onPreTranslate = { preTarget = book },
                                onDeletePreTranslate = { preDeleteTarget = book },
                                onClick = { onOpenBook(book) }
                            )
                        }
                    }
                }
            }
        }

        
        
        if (showTopButton) {
            SmallFloatingActionButton(
                onClick = {
                    gridScope.launch { gridState.animateScrollToItem(0) }
                    collapseState.floatValue = 0f
                },
                containerColor = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 58.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = S.backToTop
                )
            }
        }

        
        
        PillButton(
            text = S.manageFolders,
            onClick = onManageFolders,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 16.dp),
            filled = false
        )
    }

    
    
    AnimatedVisibility(
        visible = drawerOpen,
        enter = fadeIn(motionTween(MotionSpec.SHORT)),
        exit = fadeOut(motionTween(MotionSpec.SHORT)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { drawerOpen = false }
        )
    }
    AnimatedVisibility(
        visible = drawerOpen,
        enter = slideInHorizontally(
            motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
        ) { -it },
        exit = slideOutHorizontally(
            motionTween(MotionSpec.SHORT, MotionSpec.Emphasized)
        ) { -it },
        modifier = Modifier.align(Alignment.CenterStart)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    text = S.appName,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = S.versionLabel(
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(22.dp))
                DrawerRow(
                    icon = Icons.Filled.Settings,
                    label = S.settingsRoot,
                    onClick = {
                        drawerOpen = false
                        onSettings()
                    }
                )
                DrawerRow(
                    icon = Icons.Filled.Edit,
                    label = S.translateConfig,
                    onClick = {
                        drawerOpen = false
                        onTranslateConfig()
                    }
                )
                DrawerRow(
                    icon = Icons.Filled.PlayArrow,
                    label = S.batchPreTranslate,
                    onClick = {
                        drawerOpen = false
                        batchOpen = true
                    }
                )
                DrawerRow(
                    icon = Icons.Filled.Info,
                    label = S.about,
                    onClick = {
                        drawerOpen = false
                        showAbout = true
                    }
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onClose = { showAbout = false }
        )
    }

    preTarget?.let { target ->
        PreTranslateDialog(
            book = target,
            settings = settings,
            onDismiss = { preTarget = null }
        )
    }

    if (batchOpen) {
        BatchPreTranslateDialog(
            books = sorted,
            settings = settings,
            initialGroup = groupFilter,
            onDismiss = { batchOpen = false }
        )
    }

    // Deleting a volume is destructive: the confirm button has to be tapped three times
    // (two seconds between taps, like the clear-progress guard) before anything is removed.
    // This is a plain delete - no pre-translate panel is opened afterwards.
    preDeleteTarget?.let { target ->
        val storedCount = TranslationStore.count(appContext, target.id)
        AlertDialog(
            onDismissRequest = { preDeleteTarget = null; deleteTaps = 0 },
            title = { Text(S.preTranslateDelete) },
            text = {
                Column {
                    Text(
                        if (storedCount > 0) S.preTranslateDeleteBody(storedCount)
                        else S.preTranslateDeleteEmpty
                    )
                    if (storedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            S.preTranslateDeleteHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = storedCount > 0,
                    onClick = {
                        val now = SystemClock.uptimeMillis()
                        deleteTaps = if (now - lastDeleteTapAt > 2000L) 1 else deleteTaps + 1
                        lastDeleteTapAt = now
                        if (deleteTaps >= 3) {
                            deleteTaps = 0
                            preDeleteTarget = null
                            val book = target
                            shelfScope.launch {
                                withContext(Dispatchers.IO) {
                                    TranslationStore.clear(appContext, book.id)
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        appContext, S.preTranslateDeleted, Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        if (deleteTaps > 0) S.preTranslateDeleteTapHint(3 - deleteTaps)
                        else S.preTranslateDeleteConfirm
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { preDeleteTarget = null; deleteTaps = 0 }) {
                    Text(S.cancel)
                }
            }
        )
    }
    }
}


/**
 * Persistent way back into a folder batch. It stays visible while the batch runs and remains
 * after the app was killed so the unfinished queue can be resumed with one tap.
 */
@Composable
private fun BatchBanner(state: BatchState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.running) S.batchBannerRunning else S.batchBannerPending,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = S.batchBannerOpen,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (state.running) {
                    S.batchBannerDetail(
                        state.doneBooks + 1, state.totalBooks,
                        state.pageDone, state.pageTotal
                    )
                } else S.batchBannerPendingDetail(state.pending.size),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimaryContainer
            )
            if (state.running && state.pageTotal > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.pageDone.toFloat() / state.pageTotal },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}






@Composable
private fun CollapsingHeader(
    collapse: State<Float>,
    mode: Int,
    loading: Boolean,
    restoring: Boolean,
    count: Int
) {
    val scheme = MaterialTheme.colorScheme
    val c = collapse.value.coerceIn(0f, 1f)
    val headerAlpha = 1f - c
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ExpandableHeaderHeight * headerAlpha)
            .clipToBounds()
            .graphicsLayer { alpha = headerAlpha }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp)
        ) {
            Text(
                text = if (mode == SHELF_MODE_READING) S.reading else S.shelf,
                fontSize = (28f - 8f * c).sp,
                fontWeight = FontWeight((700f - 200f * c).toInt()),
                color = scheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (mode == SHELF_MODE_READING) S.readingSubtitle else S.shelfSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    loading -> S.loading
                    restoring -> ""
                    else -> "$count ${S.booksUnit}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiniChip(text: String, onClick: () -> Unit, active: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = motionTween(MotionSpec.SHORT, MotionSpec.EaseOutCubic),
        label = "chipPress"
    )
    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = CircleShape,
        color = if (active) scheme.primary else scheme.secondaryContainer,
        contentColor = if (active) scheme.onPrimary else scheme.onSecondaryContainer
    ) {
        Box(
            modifier = Modifier
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 12.dp, vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    columns: Int,
    prefs: Prefs,
    vm: ShelfViewModel,
    refreshKey: Int,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPreTranslate: () -> Unit,
    onDeletePreTranslate: () -> Unit,
    onClick: () -> Unit
) {
    var coverMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val appContext = LocalContext.current.applicationContext
    val completedRev by TranslationStore.completedRev.collectAsState()
    val translated = remember(book.id, completedRev) {
        TranslationStore.isComplete(appContext, book.id)
    }
    val compact = columns >= 4
    val pad = when {
        columns >= 5 -> 5.dp
        columns == 4 -> 6.dp
        columns == 3 -> 8.dp
        else -> 10.dp
    }
    
    
    val coverPx = when (columns) {
        5 -> 160
        4 -> 200
        3 -> 260
        else -> 360
    }
    val cover by produceState<Bitmap?>(
        
        
        initialValue = vm.peekCover(book, coverPx),
        key1 = book.id,
        key2 = vm,
        key3 = coverPx
    ) {
        value = vm.cover(book, coverPx)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = motionTween(MotionSpec.SHORT, MotionSpec.EaseOutCubic),
        label = "cardPress"
    )
    
    val restingCorner = if (compact) 18f else 24f
    val cardCorner by animateDpAsState(
        targetValue = (if (pressed) restingCorner * 0.65f else restingCorner).dp,
        animationSpec = motionTween(MotionSpec.SHORT, MotionSpec.EaseOutCubic),
        label = "cardCorner"
    )
    
    val (progress, total) = remember(book.id, refreshKey) {
        prefs.progress(book.id) to prefs.pageCount(book.id)
    }
    val fraction = if (total > 0) {
        ((progress + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(cardCorner),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
    ) {
        Column(modifier = Modifier.padding(pad)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(if (compact) 12.dp else 18.dp))
                    .background(scheme.primaryContainer)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                        onLongClick = { coverMenu = true }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = cover
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = book.name.take(1).ifEmpty { "漫" },
                        style = MaterialTheme.typography.displayLarge,
                        color = scheme.onPrimaryContainer
                    )
                }

                
                if (favorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (compact) 5.dp else 8.dp)
                            .size(if (compact) 22.dp else 28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = S.favoriteLabel,
                            tint = FavoritePink,
                            modifier = Modifier.size(if (compact) 14.dp else 18.dp)
                        )
                    }
                }

                if (translated) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(if (compact) 5.dp else 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(scheme.primary.copy(alpha = 0.92f))
                            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = S.translatedBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = if (compact) 9.sp else 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onPrimary
                        )
                    }
                }

                
                DropdownMenu(
                    expanded = coverMenu,
                    onDismissRequest = { coverMenu = false }
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = FavoritePink,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = { Text(if (favorite) S.unfavorite else S.favorite) },
                        onClick = {
                            coverMenu = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Text(
                                text = S.translateToggleMark,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(20.dp)
                            )
                        },
                        text = { Text(S.preTranslate) },
                        onClick = {
                            coverMenu = false
                            onPreTranslate()
                        }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = { Text(S.preTranslateDelete) },
                        onClick = {
                            coverMenu = false
                            onDeletePreTranslate()
                        }
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            Text(
                text = book.name,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = if (compact) 2.dp else 4.dp)
            )
            if (!compact) {
                Text(
                    text = if (book.kind == BookKind.FOLDER) S.folderType else S.sizeText(book.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            
            if (total > 0 && progress > 0) {
                Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
                Text(
                    text = if (compact) S.pageProgressCompact(progress, total) else S.pageProgress(progress, total),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(horizontal = if (compact) 2.dp else 4.dp)
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 3.dp else 5.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(scheme.primary)
                    )
                }
            } else if (progress > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = S.pageProgress(progress, total),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(horizontal = if (compact) 2.dp else 4.dp)
                )
            }
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    onPickFolder: () -> Unit,
    tip: String? = null,
    showChooseButton: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "漫",
                style = MaterialTheme.typography.displayLarge,
                color = scheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (tip != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = tip,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                textAlign = TextAlign.Center
            )
        }
        if (showChooseButton) {
            Spacer(Modifier.height(26.dp))
            PillButton(text = S.chooseFolder, onClick = onPickFolder)
        }
    }
}

@Composable
private fun ReadingEmptyState(onGoHome: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(scheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "读",
                style = MaterialTheme.typography.displayLarge,
                color = scheme.onTertiaryContainer
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = S.readingEmptyTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = S.readingEmptyBody,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        PillButton(text = S.goShelf, onClick = onGoHome)
    }
}
