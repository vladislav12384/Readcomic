package com.readcomic.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.readcomic.data.ComicEntry
import com.readcomic.shelf.ShelfItem
import com.readcomic.shelf.ShelfView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(
    comics: List<ComicEntry>,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    shelfMode: Boolean,
    shelfScroll: Float,
    onShelfModeChange: (Boolean) -> Unit,
    onShelfScroll: (Float) -> Unit,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
) {
    var toRemove by remember { mutableStateOf<ComicEntry?>(null) }

    if (shelfMode) {
        ShelfLibrary(
            comics = comics,
            busy = busy,
            snackbar = snackbar,
            coverFile = coverFile,
            initialScroll = shelfScroll,
            onScroll = onShelfScroll,
            onShowGrid = { onShelfModeChange(false) },
            onAdd = onAdd,
            onOpen = onOpen,
            onRemove = { toRemove = it },
        )
    } else {
        GridLibrary(
            comics = comics,
            busy = busy,
            snackbar = snackbar,
            coverFile = coverFile,
            onShowShelves = { onShelfModeChange(true) },
            onAdd = onAdd,
            onOpen = onOpen,
            onRemove = { toRemove = it },
        )
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
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    initialScroll: Float,
    onScroll: (Float) -> Unit,
    onShowGrid: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<ShelfView?>(null) }
    var heldId by remember { mutableStateOf<String?>(null) }
    // A real shelf keeps its order: the newest additions first, nothing jumps around after reading.
    val ordered = remember(comics) { comics.sortedByDescending { it.addedAt } }
    val aspects by produceState<Map<String, Float>?>(null, ordered) {
        value = withContext(Dispatchers.IO) {
            ordered.associate { it.id to (LibraryShelfArt.coverAspect(coverFile(it)) ?: DEFAULT_ASPECT) }
        }
    }
    val currentComics by rememberUpdatedState(comics)
    val currentOnOpen by rememberUpdatedState(onOpen)

    LaunchedEffect(view, ordered, aspects) {
        val shelf = view ?: return@LaunchedEffect
        val known = aspects ?: return@LaunchedEffect
        val items = ordered.map { entry ->
            ShelfItem(
                id = entry.id,
                aspect = known[entry.id] ?: DEFAULT_ASPECT,
                pageCount = entry.pageCount,
                inProgress = entry.currentPage > 0 && !entry.isFinished,
            )
        }
        val art = LibraryShelfArt(context.applicationContext, ordered.associateBy { it.id }, coverFile)
        shelf.setItems(items, art)
    }

    val held = comics.firstOrNull { it.id == heldId }
    BackHandler(enabled = held != null) { view?.putBack() }

    BoxWithConstraints(Modifier.fillMaxSize().background(ShelfRoom)) {
        val landscape = maxWidth > maxHeight

        AndroidView(
            factory = { ctx ->
                ShelfView(ctx).also { shelf ->
                    shelf.setClearColor(ShelfRoom.red, ShelfRoom.green, ShelfRoom.blue)
                    if (!initialScroll.isNaN()) shelf.restoreScroll(initialScroll)
                    shelf.callbacks = object : ShelfView.Callbacks {
                        override fun onHeldChanged(id: String?) {
                            heldId = id
                        }

                        override fun onOpen(id: String) {
                            currentComics.firstOrNull { it.id == id }?.let(currentOnOpen)
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

        // Darker corners, like a room lit by the shelf lamps only.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val vignette = Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                        center = Offset(size.width / 2f, size.height * 0.45f),
                        radius = max(size.width, size.height) * 0.75f,
                    )
                    onDrawBehind { drawRect(vignette) }
                },
        )

        // The title steps aside while a comic is in hand.
        AnimatedVisibility(
            visible = held == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ShelfHeader(
                count = comics.size,
                reading = comics.count { it.currentPage > 0 && !it.isFinished },
                busy = busy,
                onShowGrid = onShowGrid,
            )
        }

        if (comics.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
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

        AnimatedVisibility(
            visible = held == null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(SafeInsets)
                .padding(20.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Добавить") },
            )
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
                    onRead = { onOpen(entry) },
                    onRemove = { onRemove(entry) },
                    onPutBack = { view?.putBack() },
                )
            } else {
                Spacer(Modifier.fillMaxWidth())
            }
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

@Composable
private fun ShelfHeader(count: Int, reading: Int, busy: Boolean, onShowGrid: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent)),
    ) {
        Column(Modifier.windowInsetsPadding(SafeInsets).padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 36.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Коллекция",
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        color = ShelfInk,
                    )
                    Text(
                        buildString {
                            append(comicsCount(count))
                            if (reading > 0) append(" · читаю $reading")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = ShelfInk.copy(alpha = 0.65f),
                    )
                }
                IconButton(onClick = onShowGrid) {
                    Icon(GridIcon, contentDescription = "Сеткой", tint = ShelfInk.copy(alpha = 0.85f))
                }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 16.dp))
            }
        }
    }
}

@Composable
private fun HeldComicCard(entry: ComicEntry, onRead: () -> Unit, onRemove: () -> Unit, onPutBack: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xF01E1915),
        contentColor = ShelfInk,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
                IconButton(onClick = onPutBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Поставить на полку", tint = ShelfInk.copy(alpha = 0.7f))
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        entry.isFinished -> "Прочитан · ${pagesCount(entry.pageCount)}"
                        entry.currentPage > 0 ->
                            "Стр. ${entry.currentPage + 1} из ${entry.pageCount} · ${(entry.progress * 100).roundToInt()}%"
                        else -> "${pagesCount(entry.pageCount)} · ещё не начат"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShelfInk.copy(alpha = 0.7f),
                )
                if (entry.currentPage > 0 && !entry.isFinished) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        trackColor = Color.White.copy(alpha = 0.12f),
                        drawStopIndicator = {},
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRead, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                entry.isFinished -> "Перечитать"
                                entry.currentPage > 0 -> "Продолжить"
                                else -> "Читать"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "Убрать из коллекции", tint = ShelfInk.copy(alpha = 0.6f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Покрути пальцем · нажми на комикс, чтобы открыть",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShelfInk.copy(alpha = 0.45f),
                )
            }
        }
    }
}

// endregion

// region Grid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridLibrary(
    comics: List<ComicEntry>,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    onShowShelves: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val sorted = remember(comics) {
        comics.sortedWith(compareByDescending<ComicEntry> { it.lastReadAt }.thenByDescending { it.addedAt })
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Readcomic", fontWeight = FontWeight.Bold)
                            Text(
                                "Библиотека · ${comics.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onShowShelves) {
                            Icon(ShelfIcon, contentDescription = "На полках")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Добавить") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sorted.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sorted, key = { it.id }) { entry ->
                    ComicCard(
                        entry = entry,
                        coverFile = coverFile(entry),
                        onClick = { onOpen(entry) },
                        onLongClick = { onRemove(entry) },
                    )
                }
            }
        }
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
private fun ComicCard(entry: ComicEntry, coverFile: File, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cover by produceState<ImageBitmap?>(null, coverFile, entry.pageCount) {
        value = withContext(Dispatchers.IO) {
            if (coverFile.exists()) BitmapFactory.decodeFile(coverFile.path)?.asImageBitmap() else null
        }
    }
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .shadow(10.dp, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val image = cover
            if (image != null) {
                Image(image, contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    entry.title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // Spine shading so the cover reads like a physical comic.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.06f to Color.Transparent,
                            0.9f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.12f),
                        ),
                    ),
            )
            if (entry.currentPage > 0) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    trackColor = Color.Black.copy(alpha = 0.4f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun comicsCount(n: Int) = plural(n, "комикс", "комикса", "комиксов")

private fun pagesCount(n: Int) = plural(n, "страница", "страницы", "страниц")

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
