package com.yingcolor.comicviewer.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yingcolor.comicviewer.S
import com.yingcolor.comicviewer.data.Book
import com.yingcolor.comicviewer.data.BookKind
import com.yingcolor.comicviewer.data.ComicRepository
import com.yingcolor.comicviewer.data.Prefs
import com.yingcolor.comicviewer.data.ShelfIndex
import com.yingcolor.comicviewer.data.UiSettings
import com.yingcolor.comicviewer.util.naturalCompare
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

    var books by mutableStateOf<List<Book>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    /** True only while the private index is being read (a few hundred ms). */
    var restoring by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Category chip selected on the shelf; empty means "all". */
    var filterGroup by mutableStateOf("")

    /** Reading-state filter: 0 = all, 1 = finished, 2 = currently reading. */
    var readFilter by mutableStateOf(0)

    /**
     * Show the remembered shelf first, then quietly verify the folder in the
     * background. Only a real add / remove / rename / size change rewrites the
     * list and the index, so a library of thousands needs no startup wait.
     */
    fun load(roots: List<Uri>, force: Boolean = false) {
        if (roots.isEmpty()) {
            loadJob?.cancel()
            loadedKey = null
            books = emptyList()
            loading = false
            return
        }
        val folder = roots.joinToString("|") { it.toString() }
        val sameFolder = loadedKey == folder
        if (sameFolder && loadJob?.isActive == true) return
        // Activity restarts (rotation, theme switch) must not walk the tree again.
        if (sameFolder && !force && books.isNotEmpty() &&
            System.currentTimeMillis() - lastVerifiedAt < VERIFY_INTERVAL_MS
        ) return

        loadedKey = folder
        loadJob?.cancel()
        restoring = false
        loadJob = viewModelScope.launch {
            error = null
            // Never delete the index here: a cold start also has loadedKey == null,
            // and wiping it would force the full walk we are trying to avoid. A
            // different folder is handled by index.load()'s folder check and by
            // index.save() overwriting the file after a successful scan.
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
                    // An empty result with a populated cache is usually a transient
                    // provider / permission hiccup. Never wipe a good shelf for it.
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
        }
    }

    /** The shelf's small "refresh" chip forces an immediate re-check. */
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
    onGoHome: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var columnMenu by remember { mutableStateOf(false) }
    // Sorting with naturalCompare() is the expensive part of a big shelf. Do it
    // once per books/sort change, then only filter for each keystroke instead of
    // re-sorting the whole library on every character typed into search.
    val sorted = remember(books, settings.sortMode, refreshKey) {
        sortBooks(books, settings.sortMode, prefs)
    }
    // Categories are the first folder below each library root, so a library
    // organised as 作者 / 类型 / … can be narrowed down with one tap.
    val groups = remember(sorted) {
        sorted.asSequence()
            .map { it.group }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith { a, b -> naturalCompare(a, b) }
            .toList()
    }
    // A saved category can disappear when its folder is removed; never leave the
    // shelf filtered by something that no longer exists.
    val groupFilter = vm.filterGroup.takeIf { it in groups } ?: ""
    val readFilter = vm.readFilter
    val favorites = settings.favorites
    val displayed = remember(sorted, query, groupFilter, readFilter, refreshKey) {
        val base = if (groupFilter.isEmpty()) sorted else sorted.filter { it.group == groupFilter }
        val q = query.trim()
        val searched = if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
        // The "currently reading" tab already arrives pre-filtered; narrowing it
        // again with the shelf's progress filter would hide part of that list.
        if (mode == SHELF_MODE_READING || readFilter == 0) searched
        else searched.filter { book ->
            val page = prefs.progress(book.id)
            val total = prefs.pageCount(book.id)
            val finished = total > 0 && page >= total - 1
            if (readFilter == 1) finished else page > 0 && !finished
        }
    }
    val scheme = MaterialTheme.colorScheme
    val columns = settings.gridColumns

    // Collapsing large title: ONE continuous scroll value drives the title's
    // size, weight and opacity and the expandable region's height. The toolbar
    // below stays pinned. Crucially this state is read ONLY inside the header
    // composable: reading it here used to recompose the whole screen (toolbar,
    // menus and the entire grid) on every single scroll frame.
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
    val showTopButton by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
        }
    }

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
            // Only this small composable subscribes to the scroll progress now.
            // The toolbar and the (potentially 10,000-item) grid stay untouched
            // while the title shrinks.
            CollapsingHeader(
                collapse = collapseState,
                mode = mode,
                loading = loading,
                restoring = restoring,
                count = displayed.size
            )

            // Pinned toolbar: always visible, including when the big title is gone.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(S.search, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                )
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
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = S.refresh,
                        tint = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = S.settings,
                        tint = scheme.onSurfaceVariant
                    )
                }
            }

            // A separate row rather than another toolbar chip: the search row is
            // already full on a 3-column phone, and the three states read better
            // as a visible segmented control than hidden inside a dropdown.
            if (mode == SHELF_MODE_HOME && books.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                SegmentedChoice(
                    options = S.readFilterOptions,
                    selected = readFilter,
                    onSelect = { vm.readFilter = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                )
                Spacer(Modifier.height(6.dp))
            }

            when {
                error != null -> EmptyState(
                    title = S.folderError,
                    body = error,
                    onPickFolder = onPickFolder
                )
                // Reading the index takes a moment; do not flash "no comics yet".
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
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = if (columns >= 4) 12.dp else 18.dp,
                        end = if (columns >= 4) 12.dp else 18.dp,
                        top = 8.dp,
                        bottom = 100.dp
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
                            onClick = { onOpenBook(book) }
                        )
                    }
                }
            }
        }

        // Small "back to top" FAB, like the one on Kazumi's grid pages. It only
        // appears once the list has actually been scrolled.
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
                    .padding(end = 20.dp, bottom = 78.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = S.backToTop
                )
            }
        }

        // With several library folders, "change folder" is the wrong promise:
        // this opens the list where folders are added or removed.
        PillButton(
            text = S.manageFolders,
            onClick = onManageFolders,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 16.dp),
            filled = false
        )
    }
}

/**
 * Isolating the scroll subscription here is the single biggest UI win on a large
 * shelf: the title interpolates every frame, but only these three Text nodes
 * recompose — not the search field, menus or the grid of covers.
 */
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
    onClick: () -> Unit
) {
    var coverMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val compact = columns >= 4
    val pad = when {
        columns >= 5 -> 5.dp
        columns == 4 -> 6.dp
        columns == 3 -> 8.dp
        else -> 10.dp
    }
    // A five-column card is tiny on screen: asking for a 360 px thumbnail would
    // waste both decode time and memory. Request only what the layout can show.
    val coverPx = when (columns) {
        5 -> 160
        4 -> 200
        3 -> 260
        else -> 360
    }
    val cover by produceState<Bitmap?>(
        // A cached cover is available synchronously: no spinner-to-image flicker
        // when scrolling a card back into view.
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
    // Shape morph on press: a short ease-out, in step with the scale change.
    val restingCorner = if (compact) 18f else 24f
    val cardCorner by animateDpAsState(
        targetValue = (if (pressed) restingCorner * 0.65f else restingCorner).dp,
        animationSpec = motionTween(MotionSpec.SHORT, MotionSpec.EaseOutCubic),
        label = "cardCorner"
    )
    // Re-read SharedPreferences whenever we come back from the reader.
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

                // A favourite shows a pink heart on the cover's top-right corner.
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

                // Long-press menu anchored to the cover itself.
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
            // A book that was only opened (still on the cover) must not show a bar.
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
