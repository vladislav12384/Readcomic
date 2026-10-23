package com.readcomic.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.readcomic.data.ComicEntry
import com.readcomic.data.CoverAspects
import com.readcomic.shelf.CaseLight
import com.readcomic.shelf.ShelfItem
import com.readcomic.shelf.ShelfSize
import com.readcomic.shelf.ShelfStyle
import com.readcomic.shelf.ShelfView
import com.readcomic.shelf.UnboxPhase
import com.readcomic.shelf.UnboxState
import com.readcomic.update.AppUpdater
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    comics: List<ComicEntry>,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    shelfMode: Boolean,
    shelfScroll: Float,
    shelfTheme: Int,
    shelfHintDone: Boolean,
    onShelfModeChange: (Boolean) -> Unit,
    onShelfScroll: (Float) -> Unit,
    onShelfThemeChange: (Int) -> Unit,
    onShelfHintSeen: () -> Unit,
    folders: FolderState,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
    order: ShelfOrder = ShelfOrder.TITLE,
    onOrderChange: (ShelfOrder) -> Unit = {},
    /** How the plain grid looks. */
    gridLook: GridLook = GridLook(),
    onSetSeries: (ComicEntry, String?) -> Unit = { _, _ -> },
    /** A drawn back cover for [ComicEntry] (true), or its own. */
    onDrawnBackChange: (ComicEntry, Boolean) -> Unit = { _, _ -> },
    shelfSize: ShelfSize = ShelfSize.NORMAL,
    onShelfSizeChange: (ShelfSize) -> Unit = {},
    /** The look of the bookcase. */
    shelfStyle: ShelfStyle = ShelfStyle.WOOD,
    onShelfStyleChange: (ShelfStyle) -> Unit = {},
    /** How many comics a shelf holds at least (2 to 4). */
    shelfColumns: Int = 2,
    onShelfColumnsChange: (Int) -> Unit = {},
    /** How bright the lights of the display case are. */
    caseLight: CaseLight = CaseLight.BRIGHT,
    onCaseLightChange: (CaseLight) -> Unit = {},
    /** Reading [ComicEntry] again from its first page. */
    onStartOver: (ComicEntry) -> Unit = {},
    /** The box of new comics: what it does, and what the reader does with it. */
    unboxing: UnboxSettings = UnboxSettings(),
    /** The card of a comic in hand still says how to turn it and open it. */
    handHint: Boolean = false,
    onHandHintShown: () -> Unit = {},
    /** The library has been read: an empty one is shown as empty only then. */
    loaded: Boolean = true,
    /** A newer build of the app, and looking for one. */
    updates: UpdateUi = UpdateUi(),
) {
    UpdateInstallEffect(updates)
    var toRemove by remember { mutableStateOf<ComicEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<ComicEntry?>(null) }
    val working = busy || folders.scanning != null

    if (shelfMode) {
        ShelfLibrary(
            comics = comics,
            loaded = loaded,
            busy = working,
            snackbar = snackbar,
            coverFile = coverFile,
            theme = shelfTheme,
            hintDone = shelfHintDone,
            initialScroll = shelfScroll,
            onScroll = onShelfScroll,
            onShowGrid = { onShelfModeChange(false) },
            onThemeChange = onShelfThemeChange,
            onHintSeen = onShelfHintSeen,
            onAdd = { adding = true },
            onOpen = onOpen,
            onRemove = { toRemove = it },
            order = order,
            onOrderChange = onOrderChange,
            onNameSeries = { naming = it },
            onDrawnBackChange = onDrawnBackChange,
            shelfSize = shelfSize,
            onShelfSizeChange = onShelfSizeChange,
            shelfStyle = shelfStyle,
            onShelfStyleChange = onShelfStyleChange,
            shelfColumns = shelfColumns,
            onShelfColumnsChange = onShelfColumnsChange,
            caseLight = caseLight,
            onCaseLightChange = onCaseLightChange,
            onStartOver = onStartOver,
            unboxing = unboxing,
            handHint = handHint,
            onHandHintShown = onHandHintShown,
            updates = updates,
        )
    } else {
        GridLibrary(
            comics = comics,
            loaded = loaded,
            busy = working,
            snackbar = snackbar,
            coverFile = coverFile,
            onShowShelves = { onShelfModeChange(true) },
            onAdd = { adding = true },
            onOpen = onOpen,
            onRemove = { toRemove = it },
            order = order,
            onOrderChange = onOrderChange,
            updates = updates,
            look = gridLook,
        )
    }

    naming?.let { entry ->
        val current = comics.firstOrNull { it.id == entry.id } ?: entry
        SeriesDialog(
            entry = current,
            known = remember(comics) { comics.mapNotNull { it.series }.distinct().sortedWith(CollectionOrder::compareNames) },
            onSave = { name ->
                onSetSeries(current, name)
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    if (adding) {
        CollectionSheet(folders = folders, onAddFiles = onAdd, onDismiss = { adding = false })
    }

    toRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Убрать из библиотеки?") },
            text = { Text("«${entry.title}» пропадёт из коллекции. Сам файл останется на месте.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(entry)
                    toRemove = null
                }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Отмена") } },
        )
    }
}

// region Shelves

/**
 * The collection as a wall of wooden shelves: tap a comic to take it into your hands, turn it
 * around with a finger, read it from there.
 */
@Composable
private fun ShelfLibrary(
    comics: List<ComicEntry>,
    loaded: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    theme: Int,
    hintDone: Boolean,
    initialScroll: Float,
    onScroll: (Float) -> Unit,
    onShowGrid: () -> Unit,
    onThemeChange: (Int) -> Unit,
    onHintSeen: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
    order: ShelfOrder,
    onOrderChange: (ShelfOrder) -> Unit,
    onNameSeries: (ComicEntry) -> Unit,
    onDrawnBackChange: (ComicEntry, Boolean) -> Unit,
    shelfSize: ShelfSize,
    onShelfSizeChange: (ShelfSize) -> Unit,
    shelfStyle: ShelfStyle,
    onShelfStyleChange: (ShelfStyle) -> Unit,
    shelfColumns: Int,
    onShelfColumnsChange: (Int) -> Unit,
    caseLight: CaseLight,
    onCaseLightChange: (CaseLight) -> Unit,
    onStartOver: (ComicEntry) -> Unit,
    unboxing: UnboxSettings,
    handHint: Boolean,
    onHandHintShown: () -> Unit,
    updates: UpdateUi,
) {
    val context = LocalContext.current
    // New comics wait in their box until unwrapped; only the others stand on the shelves.
    val shelved = remember(comics) { comics.filter { !it.wrapped } }
    val boxed = remember(comics) { comics.filter { it.wrapped }.sortedWith(compareBy({ it.addedAt }, { it.title })) }
    var unbox by remember { mutableStateOf(UnboxState(UnboxPhase.NONE, 0)) }
    val unpacking = unbox.phase == UnboxPhase.SEALED || unbox.phase == UnboxPhase.TEARING || unbox.phase == UnboxPhase.BUSY
    val currentUnboxing by rememberUpdatedState(unboxing)
    var view by remember { mutableStateOf<ShelfView?>(null) }
    // Where the header of a display ends on screen, pixels (it is measured once laid out).
    var headerBottom by remember { mutableFloatStateOf(0f) }
    var heldId by remember { mutableStateOf<String?>(null) }
    // The small panels from the corner buttons: the wall paint, the order.
    var picking by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val placed = remember(shelved, order) { CollectionOrder.arrange(shelved, order) }
    val ordered = remember(placed) { placed.map { it.entry } }
    val found = remember(comics, order, query) {
        if (query.isBlank()) emptyList() else ordered.filter(CollectionOrder.matcher(query))
    }
    // The shapes of the covers: those known already (kept on disk between runs) at once, so the
    // shelves never wait for them; checked against the files in the background, which also reads
    // any not known yet (a new comic) and keeps them for next time.
    val cachedAspects = remember(comics) { LibraryShelfArt.knownAspects(comics, coverFile) }
    val checkedAspects by produceState<Pair<List<ComicEntry>, Map<String, Float>>?>(null, comics) {
        val checked = withContext(Dispatchers.IO) {
            val shapes = comics.associate { it.id to (LibraryShelfArt.coverAspect(coverFile(it)) ?: DEFAULT_ASPECT) }
            CoverAspects.save(comics.map(coverFile))
            shapes
        }
        value = comics to checked
    }
    val aspects: Map<String, Float> = checkedAspects?.takeIf { it.first === comics }?.second
        ?: cachedAspects
        ?: checkedAspects?.second
        ?: emptyMap()
    val currentComics by rememberUpdatedState(comics)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnHintSeen by rememberUpdatedState(onHintSeen)
    // The first few times a comic is taken its card says how to turn it and open it.
    var handHintFor by remember { mutableStateOf<String?>(null) }
    val currentHandHint by rememberUpdatedState(handHint)
    val currentOnHandHintShown by rememberUpdatedState(onHandHintShown)
    LaunchedEffect(heldId) {
        val id = heldId ?: return@LaunchedEffect
        if (currentHandHint && handHintFor != id) {
            handHintFor = id
            currentOnHandHintShown()
        }
    }

    LaunchedEffect(view, placed, aspects) {
        val shelf = view ?: return@LaunchedEffect
        val known = aspects
        val items = placed.map { place ->
            val entry = place.entry
            ShelfItem(
                id = entry.id,
                aspect = known[entry.id] ?: DEFAULT_ASPECT,
                pageCount = entry.pageCount,
                inProgress = entry.currentPage > 0 && !entry.isFinished,
                label = place.label,
                series = place.series,
                drawnBack = entry.drawnBack,
            )
        }
        val art = LibraryShelfArt(context.applicationContext, comics.associateBy { it.id }, coverFile)
        shelf.setItems(items, art)
    }
    LaunchedEffect(view, boxed, aspects) {
        val shelf = view ?: return@LaunchedEffect
        val known = aspects
        shelf.setBox(
            boxed.map { entry ->
                ShelfItem(id = entry.id, aspect = known[entry.id] ?: DEFAULT_ASPECT, pageCount = entry.pageCount, inProgress = false, label = entry.title)
            },
        )
    }
    LaunchedEffect(view, unboxing.sound) {
        view?.setSoundEnabled(unboxing.sound)
    }
    // Once the box has been opened, the hint about it is not needed any more.
    LaunchedEffect(unpacking) {
        if (unpacking) currentUnboxing.onHintSeen()
    }
    LaunchedEffect(view, theme) {
        val paint = ShelfThemes[theme]
        view?.setWallColor(paint.red, paint.green, paint.blue)
    }
    LaunchedEffect(view, shelfStyle) {
        view?.setStyle(shelfStyle)
    }
    LaunchedEffect(view, caseLight) {
        view?.setCaseLight(caseLight)
    }
    LaunchedEffect(view, shelfColumns) {
        view?.setColumns(shelfColumns)
    }
    LaunchedEffect(view, shelfSize) {
        view?.setShelfSize(shelfSize)
    }

    // While searching, the shelves show what is found and leave the rest in the shade.
    LaunchedEffect(view, searching, found) {
        view?.highlight(if (searching && query.isNotBlank()) found.map { it.id }.toSet() else null)
    }
    fun closeSearch() {
        searching = false
        query = ""
    }

    val held = comics.firstOrNull { it.id == heldId }
    BackHandler(enabled = held != null || picking || ordering || searching || unpacking) {
        when {
            unpacking -> view?.putAwayBox()
            picking -> picking = false
            ordering -> ordering = false
            searching -> closeSearch()
            else -> view?.putBack()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ShelfRoom)) {
        val landscape = maxWidth > maxHeight
        // Where panels open: under the corner buttons, or under the header of a display.
        val underHeader = if (shelfStyle.showcase) 84.dp else 64.dp
        LaunchedEffect(view, headerBottom, shelfStyle.showcase) {
            view?.setTopRoom(if (shelfStyle.showcase) headerBottom else 0f)
        }

        AndroidView(
            factory = { ctx ->
                ShelfView(ctx).also { shelf ->
                    shelf.setClearColor(ShelfRoom.red, ShelfRoom.green, ShelfRoom.blue)
                    // Known before the first frame, so that only this bookcase is ever built.
                    shelf.setStyle(shelfStyle)
                    shelf.setCaseLight(caseLight)
                    // Walking into the room for the first time: the shelf lights come on.
                    if (initialScroll.isNaN()) shelf.switchLightsOn() else shelf.restoreScroll(initialScroll)
                    shelf.callbacks = object : ShelfView.Callbacks {
                        override fun onHeldChanged(id: String?) {
                            heldId = id
                            if (id != null) currentOnHintSeen()
                        }

                        override fun onOpen(id: String) {
                            currentComics.firstOrNull { it.id == id }?.let(currentOnOpen)
                        }

                        override fun onUnwrapped(id: String) {
                            currentUnboxing.onUnwrap(id)
                        }

                        override fun onUnboxState(state: UnboxState) {
                            unbox = state
                        }
                    }
                    view = shelf
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        GlLifecycle(view)
        DisposableEffect(view) {
            val shelf = view
            onDispose { shelf?.let { onScroll(it.scroll) } }
        }

        // A soft vignette, like a photo of the room.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val vignette = Brush.radialGradient(
                        0.6f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.3f),
                        center = Offset(size.width / 2f, size.height * 0.45f),
                        radius = max(size.width, size.height) * 0.75f,
                    )
                    onDrawBehind { drawRect(vignette) }
                },
        )
        // Keeps the status bar readable over a light wall.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.1f))),
        )

        if (shelfStyle.showcase && held == null) {
            // Under the header of a display: the dark of the room, fading out just below it, so
            // the bookcase seems to start under the header.
            val scrimHeight = with(LocalDensity.current) { headerBottom.toDp() } + 14.dp
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(scrimHeight)
                    .background(
                        Brush.verticalGradient(
                            0f to ShelfRoom.copy(alpha = 0.97f),
                            0.82f to ShelfRoom.copy(alpha = 0.9f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        AnimatedVisibility(
            visible = shelfStyle.showcase && held == null && !searching && !unpacking,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ShowcaseHeader(
                count = shelved.size + boxed.size,
                style = shelfStyle,
                busy = busy,
                onMenu = {
                    ordering = false
                    picking = !picking
                },
                onSearch = {
                    picking = false
                    ordering = false
                    searching = true
                },
                onShowGrid = onShowGrid,
                onOrder = {
                    picking = false
                    ordering = !ordering
                },
                onBottom = { headerBottom = it },
            )
        }
        AnimatedVisibility(
            visible = !shelfStyle.showcase && held == null && !searching && !unpacking,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            ShelfToolbar(
                theme = theme,
                style = shelfStyle,
                busy = busy,
                onSearch = {
                    picking = false
                    ordering = false
                    searching = true
                },
                onOrder = {
                    picking = false
                    ordering = !ordering
                },
                onPickTheme = {
                    ordering = false
                    picking = !picking
                },
                onShowGrid = onShowGrid,
            )
        }

        // A newer build of the app, under the corner buttons.
        AnimatedVisibility(
            visible = updates.shown && held == null && !searching && !unpacking && !picking && !ordering,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(SafeInsets)
                .padding(start = 12.dp, end = 12.dp, top = underHeader)
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
        ) {
            UpdateBanner(updates)
        }

        // Not while the library is still being read: it is not empty, only not here yet.
        if (loaded && shelved.isEmpty() && boxed.isEmpty()) {
            EmptyShelves(Modifier.align(Alignment.Center))
        }

        val boxHint = !unboxing.hintDone && unbox.phase == UnboxPhase.PEEK && held == null && !picking && !ordering && !searching
        AnimatedVisibility(
            visible = !hintDone && !boxHint && !unpacking && held == null && shelved.isNotEmpty() && !picking && !ordering && !searching,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(SafeInsets)
                .padding(start = 20.dp, end = 20.dp, bottom = 92.dp),
        ) {
            Text(
                "Коснись комикса — он окажется у тебя в руках",
                style = MaterialTheme.typography.labelLarge,
                color = ShelfInk,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC1B1713))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // The box of new comics peeking in: a word about it, the first time.
        AnimatedVisibility(
            visible = boxHint,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(SafeInsets)
                .padding(start = 20.dp, end = 20.dp, bottom = if (landscape) 96.dp else 150.dp),
        ) {
            Text(
                "Новые комиксы ждут в коробке — коснись её",
                style = MaterialTheme.typography.labelLarge,
                color = ShelfInk,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC1B1713))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(
            visible = unpacking,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(SafeInsets)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) {
            UnboxBar(
                state = unbox,
                onClose = { view?.putAwayBox() },
                onUnwrapAll = unboxing.onUnwrapAll,
            )
        }

        AnimatedVisibility(
            visible = held == null && !searching && !unpacking,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(SafeInsets)
                .padding(20.dp),
        ) {
            if (shelfStyle.glass) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Добавить") },
                    containerColor = Color(0xE00C1424),
                    contentColor = Color(0xFFE6F0FF),
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                    modifier = Modifier.border(1.dp, GlassRim, CircleShape),
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Добавить") },
                    containerColor = ShelfInk,
                    contentColor = Color(0xFF3A2A1C),
                )
            }
        }

        // The comic in hand keeps clear of its card and of the status bar: what they take from
        // each edge of the screen, in pixels.
        // The size of the card is kept for the next one (cards are much alike); until the first
        // is measured, about what it will be, so the comic goes straight to its place.
        var cardSize by remember(landscape) { mutableStateOf(IntSize.Zero) }
        val insets = SafeInsets
        val layoutDirection = LocalLayoutDirection.current
        val handRoom = with(LocalDensity.current) {
            val left = insets.getLeft(this, layoutDirection).toFloat()
            val top = insets.getTop(this).toFloat()
            val right = insets.getRight(this, layoutDirection).toFloat()
            val bottom = insets.getBottom(this).toFloat()
            val known = cardSize != IntSize.Zero
            if (landscape) {
                val width = if (known) cardSize.width.toFloat() else (maxWidth * 0.4f).coerceAtMost(380.dp).toPx()
                Rect(left, top, right + 16.dp.toPx() + width, bottom)
            } else {
                val height = if (known) cardSize.height.toFloat() else 156.dp.toPx()
                Rect(left, top, right, bottom + 12.dp.toPx() + height)
            }
        }
        LaunchedEffect(view, handRoom) {
            view?.setHandRoom(handRoom.left, handRoom.top, handRoom.right, handRoom.bottom)
        }

        // The card of the comic in hand: at the bottom in portrait, on the right in landscape.
        AnimatedContent(
            targetState = held,
            contentKey = { it?.id },
            transitionSpec = {
                if (landscape) {
                    (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { it / 2 } + fadeOut())
                } else {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut())
                }
            },
            label = "held",
            modifier = if (landscape) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(SafeInsets)
                    .padding(16.dp)
                    .width((maxWidth * 0.4f).coerceAtMost(380.dp))
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(SafeInsets)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
            },
        ) { entry ->
            if (entry != null) {
                HeldComicCard(
                    entry = entry,
                    coverFile = coverFile(entry),
                    hint = entry.id == handHintFor,
                    onRead = { if (entry.isFinished) onStartOver(entry) else onOpen(entry) },
                    onRemove = { onRemove(entry) },
                    onPutBack = { view?.putBack() },
                    onSeries = { onNameSeries(entry) },
                    onStartOver = { onStartOver(entry) },
                    drawnBack = entry.drawnBack,
                    onDrawnBackChange = { on ->
                        onDrawnBackChange(entry, on)
                        // Turned round at once, to see the back that is now there.
                        view?.showBack()
                    },
                    modifier = Modifier.onSizeChanged { cardSize = it },
                )
            } else {
                Spacer(Modifier.fillMaxWidth())
            }
        }

        // Choosing the paint of the wall or the order; a tap anywhere else closes it.
        if (picking || ordering) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            picking = false
                            ordering = false
                        }
                    },
            )
        }
        AnimatedVisibility(
            visible = ordering && held == null,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(SafeInsets)
                .padding(top = underHeader, end = 12.dp),
        ) {
            OrderPicker(selected = order) {
                onOrderChange(it)
                ordering = false
            }
        }

        AnimatedVisibility(
            visible = searching,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(SafeInsets)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(max = 640.dp),
        ) {
            SearchPanel(
                query = query,
                onQueryChange = { query = it },
                found = found,
                coverFile = coverFile,
                onPick = { entry ->
                    closeSearch()
                    view?.focus(entry.id)
                },
                onClose = ::closeSearch,
            )
        }
        AnimatedVisibility(
            visible = picking && held == null,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            // In a display the settings open from the menu button on the left.
            modifier = Modifier
                .align(if (shelfStyle.showcase) Alignment.TopStart else Alignment.TopEnd)
                .windowInsetsPadding(SafeInsets)
                .padding(top = underHeader, start = 12.dp, end = 12.dp),
        ) {
            ThemePicker(
                selected = theme,
                onSelect = onThemeChange,
                style = shelfStyle,
                onStyleChange = onShelfStyleChange,
                columns = shelfColumns,
                onColumnsChange = onShelfColumnsChange,
                caseLight = caseLight,
                onCaseLightChange = onCaseLightChange,
                size = shelfSize,
                onSizeChange = onShelfSizeChange,
                unboxing = unboxing,
                updates = updates,
                onCheckNow = {
                    // Back to the shelves, where the banner shows the check and what came of it.
                    picking = false
                    updates.onCheckNow()
                },
            )
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(SafeInsets)
                .padding(bottom = 80.dp),
        )
    }
}

/** Round buttons in the corner: search, the order, the wall paint and the switch to a plain grid. */
@Composable
private fun ShelfToolbar(
    theme: Int,
    style: ShelfStyle,
    busy: Boolean,
    onSearch: () -> Unit,
    onOrder: () -> Unit,
    onPickTheme: () -> Unit,
    onShowGrid: () -> Unit,
) {
    val glass = style.glass
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.windowInsetsPadding(SafeInsets).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(onClick = onSearch, description = "Поиск", glass = glass) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = ShelfInk, modifier = Modifier.size(22.dp))
            }
            RoundButton(onClick = onOrder, description = "Порядок", glass = glass) {
                Icon(SortIcon, contentDescription = null, tint = ShelfInk, modifier = Modifier.size(22.dp))
            }
            RoundButton(onClick = onPickTheme, description = "Цвет стены", glass = glass) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (style.paint) ShelfThemes[theme].swatch else GlassSwatch)
                        .border(2.dp, ShelfInk, CircleShape),
                )
            }
            RoundButton(onClick = onShowGrid, description = "Сеткой", glass = glass) {
                Icon(GridIcon, contentDescription = null, tint = ShelfInk, modifier = Modifier.size(22.dp))
            }
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                Modifier.width(98.dp).clip(RoundedCornerShape(50)),
                color = ShelfInk,
                trackColor = Color.Black.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun RoundButton(
    onClick: () -> Unit,
    description: String,
    glass: Boolean = false,
    /** Ringed with light, as the button of the view one is in. */
    accent: Boolean = false,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    val look = if (glass) {
        // Dark glass with a cold rim, as the display case is lit.
        Modifier
            .shadow(if (accent) 12.dp else 8.dp, CircleShape, ambientColor = GlassGlow, spotColor = GlassGlow)
            .clip(CircleShape)
            .background(GlassFill)
            .border(if (accent) 1.5.dp else 1.dp, if (accent) GlassAccent else GlassRim, CircleShape)
    } else {
        Modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xB31B1713))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .then(look)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        content()
    }
}

/** The glass of the buttons over the display case. */
private val GlassFill = Brush.verticalGradient(listOf(Color(0xA6233554), Color(0xD90A111F)))
private val GlassRim = Brush.linearGradient(listOf(Color(0xCCA9D4FF), Color(0x334A6A9A), Color(0x99A9D4FF)))
private val GlassGlow = Color(0xFF3B82F6)
private val GlassAccent = Brush.linearGradient(listOf(Color(0xFF7CC4FF), Color(0xFF2F7BFF), Color(0xFF9AD6FF)))

/**
 * The header of the display case: the collection named in the middle, the settings and search
 * on the left, the plain grid and the order on the right.
 */
@Composable
private fun ShowcaseHeader(
    count: Int,
    style: ShelfStyle,
    busy: Boolean,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onShowGrid: () -> Unit,
    onOrder: () -> Unit,
    /** Where the header ends, in pixels from the top of the screen. */
    onBottom: (Float) -> Unit,
) {
    Column(
        Modifier
            .windowInsetsPadding(SafeInsets)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .onGloballyPositioned { onBottom(it.boundsInRoot().bottom) },
    ) {
        // Two buttons either side, so that the name stands right in the middle.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val side = 42.dp * 2 + 8.dp
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterStart)) {
                RoundButton(onClick = onMenu, description = "Настройки", glass = true, size = 42.dp) {
                    Icon(Icons.Filled.Menu, contentDescription = null, tint = ShowcaseInk, modifier = Modifier.size(22.dp))
                }
                RoundButton(onClick = onSearch, description = "Поиск", glass = true, size = 42.dp) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = ShowcaseInk, modifier = Modifier.size(22.dp))
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).width((maxWidth - side * 2 - 12.dp).coerceAtLeast(60.dp)),
            ) {
                // As large as fits between the buttons, down to small screens.
                BasicText(
                    "КОЛЛЕКЦИЯ",
                    style = TextStyle(color = ShowcaseInk, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 19.sp),
                )
                Text(
                    comicsCount(count).uppercase(),
                    color = ShowcaseInk.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterEnd)) {
                RoundButton(onClick = onShowGrid, description = "Сеткой", glass = true, accent = true, size = 42.dp) {
                    Icon(GridIcon, contentDescription = null, tint = ShowcaseInk, modifier = Modifier.size(20.dp))
                }
                RoundButton(onClick = onOrder, description = "Порядок", glass = true, size = 42.dp) {
                    Icon(MoreHorizIcon, contentDescription = null, tint = ShowcaseInk, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                Modifier.align(Alignment.CenterHorizontally).width(98.dp).clip(RoundedCornerShape(50)),
                color = ShowcaseInk,
                trackColor = Color.Black.copy(alpha = 0.3f),
            )
        }
    }
}

private val ShowcaseInk = Color(0xFFEFF4FF)

/** Three dots in a row: more. */
private val MoreHorizIcon: ImageVector by lazy {
    ImageVector.Builder("MoreHoriz", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            for (x in listOf(5f, 12f, 19f)) {
                moveTo(x + 2f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x - 2f, y1 = 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = x + 2f, y1 = 12f)
                close()
            }
        }
    }.build()
}

/** The swatch on the wall button when the wall is not painted (the display case). */
private val GlassSwatch = Color(0xFF13203A)

@Composable
private fun ThemePicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    style: ShelfStyle,
    onStyleChange: (ShelfStyle) -> Unit,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    caseLight: CaseLight,
    onCaseLightChange: (CaseLight) -> Unit,
    size: ShelfSize,
    onSizeChange: (ShelfSize) -> Unit,
    unboxing: UnboxSettings,
    updates: UpdateUi,
    onCheckNow: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xF21B1713),
        contentColor = ShelfInk,
        shadowElevation = 12.dp,
    ) {
        // Scrolls when the screen is low (a phone on its side).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Полки", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            PillRow(ShelfStyle.entries, style, { it.label }, onStyleChange, wrap = true)
            if (style.showcase) {
                Spacer(Modifier.height(16.dp))
                Text("Подсветка", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                PillRow(CaseLight.entries, caseLight, { it.label }, onCaseLightChange)
            }
            if (style.paint) {
                Spacer(Modifier.height(16.dp))
                WallPaints(selected, onSelect)
            }
            Spacer(Modifier.height(16.dp))
            Text("Комиксы на полках", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            PillRow(ShelfSize.entries, size, { it.label }, onSizeChange)
            Spacer(Modifier.height(16.dp))
            Text("В ряд", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            PillRow(listOf(2, 3, 4), columns, { "$it" }, onColumnsChange)
            Spacer(Modifier.height(16.dp))
            Text("Новые комиксы", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
            SettingSwitch("Приходят в коробке, распаковывать самому", unboxing.wrapNew, unboxing.onWrapNewChange)
            SettingSwitch("Звук распаковки", unboxing.sound, unboxing.onSoundChange)
            Spacer(Modifier.height(16.dp))
            Text("Обновления", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
            SettingSwitch("Проверять раз в день", updates.enabled, updates.onEnabledChange)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 300.dp)) {
                Text(
                    "Сборка ${updates.currentBuild}" + if (updates.preview) " · β" else "",
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                val checking = updates.state == AppUpdater.State.Checking
                TextButton(onClick = onCheckNow, enabled = !checking) {
                    Text(if (checking) "Проверяю…" else "Проверить сейчас", color = ShelfInk.copy(alpha = if (checking) 0.5f else 1f))
                }
            }
        }
    }
}

/** The paint of the wall: a swatch for each, the chosen one named under them. */
@Composable
private fun WallPaints(selected: Int, onSelect: (Int) -> Unit) {
    Text("Стена", style = MaterialTheme.typography.labelLarge, color = ShelfInk.copy(alpha = 0.6f))
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfThemes.forEachIndexed { index, paint ->
            val chosen = index == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(paint.swatch)
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) ShelfInk else ShelfInk.copy(alpha = 0.3f), CircleShape)
                    .clickable(onClickLabel = paint.name) { onSelect(index) },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        ShelfThemes[selected].name,
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 17.sp,
    )
}

/** Round-ended choices in a row (or several, [wrap]ping), the [selected] one filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> PillRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, wrap: Boolean = false) {
    if (wrap) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            options.forEach { Pill(label(it), it == selected) { onSelect(it) } }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { Pill(label(it), it == selected) { onSelect(it) } }
        }
    }
}

@Composable
private fun Pill(text: String, chosen: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontFamily = FontFamily.Serif,
        fontStyle = if (chosen) FontStyle.Italic else FontStyle.Normal,
        fontSize = 15.sp,
        color = if (chosen) Color(0xFF2A1F17) else ShelfInk,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (chosen) ShelfInk else Color.Transparent)
            .border(1.dp, ShelfInk.copy(alpha = if (chosen) 1f else 0.35f), CircleShape)
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF2A1F17),
                checkedTrackColor = ShelfInk,
                uncheckedThumbColor = ShelfInk.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = ShelfInk.copy(alpha = 0.4f),
            ),
        )
    }
}

/** What the box of new comics does, and what to call when the reader acts on it. */
class UnboxSettings(
    /** New comics arrive wrapped in the box (else they go straight on the shelves). */
    val wrapNew: Boolean = true,
    val onWrapNewChange: (Boolean) -> Unit = {},
    val sound: Boolean = true,
    val onSoundChange: (Boolean) -> Unit = {},
    /** The reader has opened the box before. */
    val hintDone: Boolean = true,
    val onHintSeen: () -> Unit = {},
    /** The paper is off the comic with this id. */
    val onUnwrap: (String) -> Unit = {},
    val onUnwrapAll: () -> Unit = {},
)

/** Under the box while it is open: what to do now, putting it away, unwrapping the rest at once. */
@Composable
private fun UnboxBar(state: UnboxState, onClose: () -> Unit, onUnwrapAll: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val hint = when {
            state.phase == UnboxPhase.SEALED -> "Проведи пальцем по скотчу — или коснись коробки"
            state.phase == UnboxPhase.TEARING && state.progress < 0.12f -> "Рви бумагу пальцем: тяни, куда хочется"
            state.phase == UnboxPhase.TEARING && state.progress >= 0.4f -> "Ещё немного…"
            else -> null
        }
        AnimatedContent(targetState = hint, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "unbox hint") { text ->
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = ShelfInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC1B1713))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC1B1713)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Убрать коробку", tint = ShelfInk)
            }
            Spacer(Modifier.weight(1f))
            if (state.count > 1) {
                TextButton(
                    onClick = onUnwrapAll,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC1B1713)),
                ) {
                    Text("Распаковать все · ${state.count}", color = ShelfInk, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyShelves(modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(32.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xD91B1713))
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Text(
            "Полки ждут первых комиксов",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
            color = ShelfInk,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Нажми «Добавить» и выбери CBZ, CBR, CB7, CBT или PDF.",
            style = MaterialTheme.typography.bodyMedium,
            color = ShelfInk.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The comic in hand: title, progress and buttons, tinted with the colour of its cover. Kept
 * small, so that the comic itself has most of the screen.
 */
@Composable
private fun HeldComicCard(
    entry: ComicEntry,
    coverFile: File,
    /** Says how to turn the comic and open it (the first few times). */
    hint: Boolean,
    onRead: () -> Unit,
    onRemove: () -> Unit,
    onPutBack: () -> Unit,
    onSeries: () -> Unit,
    onStartOver: () -> Unit,
    /** Its back cover is drawn (the reader turned it on for this comic). */
    drawnBack: Boolean,
    onDrawnBackChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent by produceState(Color(0xFFB0703A), coverFile) {
        withContext(Dispatchers.IO) { LibraryShelfArt.coverAccent(coverFile) }?.let { value = Color(it) }
    }
    val tint by animateColorAsState(accent, label = "accent")
    // The button wears the cover's colour, light enough to stand out on the dark card.
    val button = if (tint.luminance() < 0.32f) lerp(tint, Color.White, 0.35f) else tint
    val onButton = if (button.luminance() > 0.4f) Color(0xFF1A1410) else Color.White
    val reading = entry.currentPage > 0 && !entry.isFinished

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        contentColor = ShelfInk,
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(
                        0f to lerp(tint, Color.Black, 0.62f).copy(alpha = 0.96f),
                        1f to lerp(tint, Color.Black, 0.86f).copy(alpha = 0.96f),
                    ),
                )
                .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(
                        entry.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val credits = listOfNotNull(entry.series?.let { "Серия «$it»" }, entry.writer, entry.year?.toString()).joinToString(" · ")
                    if (credits.isNotEmpty()) {
                        Text(
                            credits,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = ShelfInk.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            entry.isFinished -> "Прочитан · ${pagesCount(entry.pageCount)}"
                            reading -> "Стр. ${entry.currentPage + 1} из ${entry.pageCount} · ${(entry.progress * 100).roundToInt()}%"
                            else -> "${pagesCount(entry.pageCount)} · ещё не начат"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ShelfInk.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onPutBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Поставить на полку", tint = ShelfInk.copy(alpha = 0.7f))
                }
            }
            Column(Modifier.padding(end = 14.dp)) {
                if (reading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        color = button,
                        trackColor = Color.White.copy(alpha = 0.14f),
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onRead,
                        colors = ButtonDefaults.buttonColors(containerColor = button, contentColor = onButton),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                entry.isFinished -> "Перечитать"
                                reading -> "Продолжить"
                                else -> "Читать"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Started already: it can also be read again from the first page.
                    if (reading) CardAction(RestartIcon, "Начать заново", onStartOver)
                    CardAction(SeriesIcon, "Серия", onSeries)
                    // The rarer things go into a menu, so the card stays small.
                    var menu by remember { mutableStateOf(false) }
                    CardAction(Icons.Filled.MoreVert, "Ещё", onClick = { menu = true }) {
                        DropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            containerColor = CardMenu,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Нарисовать заднюю обложку", color = ShelfInk)
                                        Text(
                                            "Если у этого выпуска её нет",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ShelfInk.copy(alpha = 0.6f),
                                        )
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = drawnBack,
                                        onCheckedChange = null,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF2A1F17),
                                            checkedTrackColor = ShelfInk,
                                            uncheckedThumbColor = ShelfInk.copy(alpha = 0.7f),
                                            uncheckedTrackColor = Color.Transparent,
                                            uncheckedBorderColor = ShelfInk.copy(alpha = 0.4f),
                                        ),
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                },
                                onClick = {
                                    menu = false
                                    onDrawnBackChange(!drawnBack)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Убрать из коллекции", color = ShelfInk) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = ShelfInk.copy(alpha = 0.75f)) },
                                onClick = {
                                    menu = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
                if (hint) {
                    Text(
                        "Покрути пальцем · коснись комикса, чтобы открыть",
                        style = MaterialTheme.typography.labelSmall,
                        color = ShelfInk.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** A round button with an icon on the card of the comic in hand; [content] goes with it (a menu it opens). */
@Composable
private fun CardAction(icon: ImageVector, label: String, onClick: () -> Unit, content: @Composable () -> Unit = {}) {
    Spacer(Modifier.width(8.dp))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = ShelfInk.copy(alpha = 0.75f), modifier = Modifier.size(22.dp))
        content()
    }
}

// endregion

// region Grid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridLibrary(
    comics: List<ComicEntry>,
    loaded: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    onShowShelves: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
    order: ShelfOrder,
    onOrderChange: (ShelfOrder) -> Unit,
    updates: UpdateUi,
    look: GridLook,
) {
    GridTheme(look.style) {
        GridLibraryContent(
            comics = comics,
            loaded = loaded,
            busy = busy,
            snackbar = snackbar,
            coverFile = coverFile,
            onShowShelves = onShowShelves,
            onAdd = onAdd,
            onOpen = onOpen,
            onRemove = onRemove,
            order = order,
            onOrderChange = onOrderChange,
            updates = updates,
            look = look,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridLibraryContent(
    comics: List<ComicEntry>,
    loaded: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    onShowShelves: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
    order: ShelfOrder,
    onOrderChange: (ShelfOrder) -> Unit,
    updates: UpdateUi,
    look: GridLook,
) {
    val style = look.style
    SystemBarsFor(style)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var ordering by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    val sorted = remember(comics, order, query) {
        CollectionOrder.arrange(comics, order).map { it.entry }.filter(CollectionOrder.matcher(query))
    }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val gridState = rememberLazyGridState()
    // Once the covers scroll under the header, it takes a veil of the page's top colour.
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 8 } }
    val chrome = GridPalette.chrome(style)
    val (groundTop, _, _) = GridPalette.ground(style)
    val veil by animateColorAsState(if (scrolled) groundTop.copy(alpha = 0.94f) else groundTop.copy(alpha = 0f), label = "veil")
    // The page under the grid is drawn by gridBackground: a soft gradient in every look.
    Scaffold(
        modifier = Modifier.gridBackground(style),
        containerColor = Color.Transparent,
        // A see-through page still needs its own ink: the titles under the covers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            Column(Modifier.background(veil)) {
                GridHeader(
                    count = comics.size,
                    style = style,
                    chrome = chrome,
                    onMenu = { viewing = true },
                    menu = { GridViewMenu(expanded = viewing, look = look, updates = updates, onDismiss = { viewing = false }) },
                    onSearch = {
                        searching = !searching
                        if (!searching) query = ""
                    },
                    onShowShelves = onShowShelves,
                    onOrder = { ordering = true },
                    orderMenu = {
                        DropdownMenu(expanded = ordering, onDismissRequest = { ordering = false }) {
                            ShelfOrder.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, fontWeight = if (option == order) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        onOrderChange(option)
                                        ordering = false
                                    },
                                )
                            }
                        }
                    },
                )
                if (style == GridStyle.VINTAGE) {
                    // A double rule under the masthead, as in an old magazine.
                    val rule = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.6.dp).background(rule))
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.5.dp).background(rule))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AnimatedVisibility(visible = searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Название, серия, автор, год") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Стереть") }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                AnimatedVisibility(visible = updates.shown) {
                    UpdateBanner(updates, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                shape = CircleShape,
                containerColor = chrome.fab,
                contentColor = chrome.fabInk,
                modifier = Modifier.size(60.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить", modifier = Modifier.size(28.dp))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sorted.isEmpty() && query.isNotBlank()) {
            Text(
                "Ничего не нашлось",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(32.dp),
            )
        } else if (sorted.isEmpty()) {
            if (loaded) EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sorted, key = { it.id }) { entry ->
                    ComicCard(
                        entry = entry,
                        coverFile = coverFile(entry),
                        look = look,
                        onClick = { onOpen(entry) },
                        onLongClick = { onRemove(entry) },
                    )
                }
            }
        }
    }
}

/**
 * The header of the plain grid: the collection named in the very middle, the look's menu and
 * search on the left, the shelves and the order on the right, in round buttons.
 */
@Composable
private fun GridHeader(
    count: Int,
    style: GridStyle,
    chrome: GridPalette.Chrome,
    onMenu: () -> Unit,
    menu: @Composable () -> Unit,
    onSearch: () -> Unit,
    onShowShelves: () -> Unit,
    onOrder: () -> Unit,
    orderMenu: @Composable () -> Unit,
) {
    Box(
        Modifier
            .background(chrome.bar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(SafeInsets.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = 40.dp * 2 + 8.dp
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterStart)) {
                Box {
                    // The menu wears no button, as in the header of a magazine.
                    HeaderButton(onMenu, "Меню", chrome, plain = true) {
                        Icon(Icons.Filled.Menu, contentDescription = null, tint = chrome.ink, modifier = Modifier.size(24.dp))
                    }
                    menu()
                }
                HeaderButton(onSearch, "Поиск", chrome) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = chrome.ink, modifier = Modifier.size(21.dp))
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).width((maxWidth - side * 2 - 8.dp).coerceAtLeast(60.dp)),
            ) {
                val (family, weight, italic) = when (style) {
                    GridStyle.VINTAGE -> Triple(FontFamily.Serif, FontWeight.SemiBold, FontStyle.Normal)
                    GridStyle.COMIC -> Triple(null, FontWeight.Black, FontStyle.Italic)
                    else -> Triple(null, FontWeight.Medium, FontStyle.Normal)
                }
                BasicText(
                    "КОЛЛЕКЦИЯ",
                    style = TextStyle(color = chrome.ink, fontFamily = family, fontWeight = weight, fontStyle = italic, letterSpacing = 1.5.sp),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 20.sp),
                )
                Text(
                    comicsCount(count).uppercase(),
                    color = chrome.muted,
                    fontFamily = family,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterEnd)) {
                HeaderButton(onShowShelves, "На полках", chrome) {
                    Icon(ShelfIcon, contentDescription = null, tint = chrome.ink, modifier = Modifier.size(21.dp))
                }
                Box {
                    HeaderButton(onOrder, "Порядок", chrome) {
                        Icon(MoreHorizIcon, contentDescription = null, tint = chrome.ink, modifier = Modifier.size(22.dp))
                    }
                    orderMenu()
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(onClick: () -> Unit, description: String, chrome: GridPalette.Chrome, plain: Boolean = false, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (plain) Color.Transparent else chrome.button)
            .border(1.dp, if (plain) Color.Transparent else chrome.rim, CircleShape)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        content()
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Здесь пока пусто", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Нажми «Добавить» и выбери комикс: CBZ, CBR, CB7, CBT или PDF.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicCard(entry: ComicEntry, coverFile: File, look: GridLook, onClick: () -> Unit, onLongClick: () -> Unit) {
    val style = look.style
    // In the shape of the issue the front cover alone is wanted (a wraparound one is cut in half).
    val frontOnly = look.byShape
    // Seen before: there at once; else read in the background (and kept for scrolling back).
    val cover by produceState(CoverThumbnails.cached(coverFile, frontOnly), coverFile, entry.pageCount, frontOnly) {
        value = withContext(Dispatchers.IO) { CoverThumbnails.load(coverFile, frontOnly) }
    }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        if (look.byShape) {
            GridPalette.ShapedCover(entry, cover, style)
        } else {
            GridPalette.Cover(entry, cover, style, Modifier.fillMaxWidth().aspectRatio(GridPalette.TILE_ASPECT))
        }
        if (entry.currentPage > 0) GridPalette.Progress(entry.progress, style)
        Spacer(Modifier.height(if (entry.currentPage > 0) 7.dp else 10.dp))
        Text(
            if (style == GridStyle.COMIC) entry.title.uppercase() else entry.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (style == GridStyle.VINTAGE) FontFamily.Serif else null,
            fontWeight = when (style) {
                GridStyle.COMIC -> FontWeight.Black
                GridStyle.VINTAGE -> FontWeight.SemiBold
                else -> FontWeight.Medium
            },
            // Its own ink, whatever the page around it: white on the dark look.
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                entry.isFinished -> "Прочитан"
                entry.currentPage > 0 -> "Стр. ${entry.currentPage + 1} из ${entry.pageCount}"
                else -> "${entry.pageCount} стр."
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (style == GridStyle.VINTAGE) FontFamily.Serif else null,
            fontStyle = if (style == GridStyle.VINTAGE) FontStyle.Italic else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The look of the grid: its colours and the covers in the shapes of the issues. */
@Composable
private fun GridViewMenu(expanded: Boolean, look: GridLook, updates: UpdateUi, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "Оформление",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        GridStyle.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label, fontWeight = if (option == look.style) FontWeight.Bold else FontWeight.Normal) },
                leadingIcon = {
                    if (option == look.style) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                },
                onClick = { look.onStyleChange(option) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = {
                Column {
                    Text("По форме выпуска")
                    Text(
                        "Свои пропорции, на одной линии",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = { Switch(checked = look.byShape, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp)) },
            onClick = { look.onByShapeChange(!look.byShape) },
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "Обновления",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DropdownMenuItem(
            text = { Text("Проверять раз в день") },
            trailingIcon = { Switch(checked = updates.enabled, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp)) },
            onClick = { updates.onEnabledChange(!updates.enabled) },
        )
        val checking = updates.state == AppUpdater.State.Checking
        DropdownMenuItem(
            text = {
                Column {
                    Text(if (checking) "Проверяю…" else "Проверить обновления")
                    Text(
                        "Сейчас стоит сборка ${updates.currentBuild}" + if (updates.preview) " · β" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            enabled = !checking,
            // Back to the grid, where the banner shows the check and what came of it.
            onClick = {
                onDismiss()
                updates.onCheckNow()
            },
        )
    }
}

/** Dark icons on the status and navigation bars over a light grid; back to light ones when it goes. */
@Composable
private fun SystemBarsFor(style: GridStyle) {
    // The dark look leaves the bars as the rest of the app has them.
    if (style == GridStyle.DARK) return
    val activity = LocalActivity.current ?: return
    // The comic look has a red bar under the status bar: its icons stay light.
    val lightStatus = style != GridStyle.COMIC
    DisposableEffect(activity, lightStatus) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = lightStatus
        controller.isAppearanceLightNavigationBars = true
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

// endregion

/** Shows one-off messages from the view model. */
@Composable
fun MessagesEffect(messages: kotlinx.coroutines.flow.SharedFlow<String>, snackbar: SnackbarHostState) {
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(it) }
    }
}

private const val DEFAULT_ASPECT = 0.66f
private val ShelfRoom = Color(0xFF0C0B0A)
private val ShelfInk = Color(0xFFF3E6D0)

/** The menu of the card of the comic in hand: dark, like the card. */
private val CardMenu = Color(0xFF241C16)

/** System bars, cutouts and the keyboard. */
private val SafeInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

internal fun comicsCount(n: Int) = plural(n, "комикс", "комикса", "комиксов")

private fun pagesCount(n: Int) = plural(n, "страница", "страницы", "страниц")

/** A circular arrow back to the start. */
private val RestartIcon: ImageVector by lazy {
    ImageVector.Builder("Restart", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f) {
            // Most of a circle...
            moveTo(5.2f, 9f)
            arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 6.5f, y1 = 18.2f)
        }
        path(fill = SolidColor(Color.Black)) {
            // ...with the arrow head at its start.
            moveTo(2.8f, 5.2f)
            lineTo(8.6f, 6.4f)
            lineTo(4.6f, 10.8f)
            close()
        }
    }.build()
}

/** Four tiles: show the library as a grid. */
private val GridIcon: ImageVector by lazy {
    ImageVector.Builder("Grid", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
                moveTo(x + 1f, y)
                horizontalLineTo(x + 6f)
                quadTo(x + 7f, y, x + 7f, y + 1f)
                verticalLineTo(y + 6f)
                quadTo(x + 7f, y + 7f, x + 6f, y + 7f)
                horizontalLineTo(x + 1f)
                quadTo(x, y + 7f, x, y + 6f)
                verticalLineTo(y + 1f)
                quadTo(x, y, x + 1f, y)
                close()
            }
        }
    }.build()
}

/** Comics standing on a shelf: show the library on the shelves. */
private val ShelfIcon: ImageVector by lazy {
    ImageVector.Builder("Shelf", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            // The plank.
            moveTo(2f, 19f)
            horizontalLineTo(22f)
            verticalLineTo(21f)
            horizontalLineTo(2f)
            close()
            // Two comics standing...
            moveTo(4f, 6f)
            horizontalLineTo(8f)
            verticalLineTo(18f)
            horizontalLineTo(4f)
            close()
            moveTo(9f, 8f)
            horizontalLineTo(13f)
            verticalLineTo(18f)
            horizontalLineTo(9f)
            close()
            // ...and one leaning against them.
            moveTo(14.2f, 9.6f)
            lineTo(17.1f, 8.6f)
            lineTo(20.6f, 17.4f)
            lineTo(17.7f, 18.4f)
            close()
        }
    }.build()
}
