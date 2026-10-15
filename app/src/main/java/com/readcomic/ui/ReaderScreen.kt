package com.readcomic.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.opengl.GLSurfaceView
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.readcomic.curl.PageCurlView
import com.readcomic.curl.PageLoader
import com.readcomic.curl.PageTurnController.ReadingMode
import com.readcomic.data.ComicEntry
import com.readcomic.data.ComicOpener
import com.readcomic.data.ComicSource
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the activity forward volume keys to the open reader. */
interface VolumeKeyHost {
    var volumeKeyListener: ((next: Boolean) -> Unit)?
}

/** Screen orientation while reading. */
enum class OrientationLock { SYSTEM, PORTRAIT, LANDSCAPE }

@Composable
fun ReaderScreen(
    entry: ComicEntry,
    table: ReadingTable,
    night: NightSettings,
    readingMode: ReadingMode,
    orientation: OrientationLock,
    pageAspects: suspend (ComicSource, onProgress: (Float) -> Unit) -> FloatArray,
    onTableChange: (ReadingTable) -> Unit,
    onNightChange: (NightSettings) -> Unit,
    onReadingModeChange: (ReadingMode) -> Unit,
    onOrientationChange: (OrientationLock) -> Unit,
    onPageChanged: (first: Int, last: Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var source by remember(entry.uri) { mutableStateOf<ComicSource?>(null) }
    var aspects by remember(entry.uri) { mutableStateOf<FloatArray?>(null) }
    var preparing by remember(entry.uri) { mutableFloatStateOf(0f) }
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var page by remember(entry.id) { mutableIntStateOf(entry.currentPage) }
    var lastPage by remember(entry.id) { mutableIntStateOf(entry.lastVisiblePage) }
    // Two layers: the menu (page navigation) and, on demand, the reading settings.
    var overlay by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var curlView by remember { mutableStateOf<PageCurlView?>(null) }
    // The comic arrives closed on the table and waits for a touch to open.
    var closed by remember(entry.id) { mutableStateOf(true) }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)

    LaunchedEffect(entry.uri) {
        try {
            val opened = withContext(Dispatchers.IO) { ComicOpener.open(context, entry.uri) }
            source = opened
            // Page shapes tell which images are two-page spreads; cached after the first time.
            // A solid RAR / 7z is unpacked in the same pass, so its pages are instant afterwards.
            aspects = pageAspects(opened) { progress -> preparing = progress }
            if (opened.needsPreparation) launch(Dispatchers.IO) { opened.prepare() }
        } catch (e: Exception) {
            error = "Не удалось открыть комикс.\n${e.message ?: ""}".trim()
        }
    }
    DisposableEffect(source) {
        val opened = source
        onDispose {
            if (opened != null) CoroutineScope(Dispatchers.IO).launch { opened.close() }
        }
    }

    ImmersiveMode(showSystemBars = overlay || settings)
    OrientationEffect(orientation)
    VolumeKeys { next ->
        overlay = false
        settings = false
        if (next) curlView?.turnNext() else curlView?.turnPrevious()
    }
    // Back closes what is open before it leaves the comic.
    BackHandler(enabled = overlay || settings) {
        if (settings) settings = false else overlay = false
    }

    LaunchedEffect(curlView, table) {
        curlView?.setTable(table.kind, table.vignette)
    }
    // Night comes and goes softly; "evenings" is checked again every minute.
    val clock by produceState(LocalTime.now()) {
        while (true) {
            delay(60_000L)
            value = LocalTime.now()
        }
    }
    val nightStrength by animateFloatAsState(
        targetValue = if (night.activeAt(clock)) 1f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "night",
    )
    LaunchedEffect(curlView, nightStrength, night.warmth, night.brightness) {
        curlView?.setNight(nightStrength, night.warmth, night.brightness)
    }
    // Shown before the first frame of the table: its colour, darker at night.
    val tint = lerp(table.color, Color.Black, 0.6f * nightStrength)
    LaunchedEffect(curlView, readingMode) {
        curlView?.setReadingMode(readingMode)
    }

    Box(Modifier.fillMaxSize().background(tint)) {
        val opened = source
        val shapes = aspects
        when {
            opened != null && shapes != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PageCurlView(ctx).apply {
                            keepScreenOn = true
                            callbacks = object : PageCurlView.Callbacks {
                                override fun onPageChanged(first: Int, last: Int) {
                                    page = first
                                    lastPage = last
                                    latestOnPageChanged(first, last)
                                }

                                override fun onCenterTap() {
                                    if (settings) settings = false else overlay = !overlay
                                }

                                override fun onReadingGesture() {
                                    overlay = false
                                    settings = false
                                }

                                override fun onClosedChanged(isClosed: Boolean) {
                                    closed = isClosed
                                }
                            }
                            setReadingMode(readingMode)
                            setComic(PageLoader { index, w, h -> opened.loadPage(index, w, h) }, shapes, entry.currentPage, closed = true)
                            curlView = this
                        }
                    },
                )
                GlLifecycle(curlView)
            }

            error != null -> Text(
                error!!,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                if (preparing > 0f) {
                    // First opening of a big archive: one pass to measure (and unpack) the pages.
                    Text("Подготовка комикса… ${(preparing * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { preparing }, modifier = Modifier.width(220.dp))
                } else {
                    CircularProgressIndicator()
                }
            }
        }

        // A quiet hint while the comic lies closed.
        AnimatedVisibility(
            visible = closed && opened != null && shapes != null && error == null,
            enter = fadeIn(tween(durationMillis = 600, delayMillis = 900)),
            exit = fadeOut(tween(durationMillis = 200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(barInsets)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "Коснись, чтобы открыть",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }

        // Tapping anywhere outside the settings closes them.
        if (settings) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        settings = false
                    },
            )
        }

        AnimatedVisibility(
            visible = overlay || settings || error != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = entry.title,
                pageLabel = source?.let {
                    val pages = if (lastPage > page) "${page + 1}–${lastPage + 1}" else "${page + 1}"
                    "стр. $pages из ${it.pageCount}"
                } ?: "",
                settingsOpen = settings,
                onSettings = { settings = !settings },
                onBack = onBack,
            )
        }

        val count = source?.pageCount ?: 0
        AnimatedVisibility(
            visible = overlay && !settings && count > 0,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                source = source,
                page = page,
                lastPage = lastPage,
                count = count,
                onJump = { target -> curlView?.jumpTo(target) },
            )
        }

        AnimatedVisibility(
            visible = settings,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SettingsPanel(
                table = table,
                night = night,
                readingMode = readingMode,
                orientation = orientation,
                onTableChange = onTableChange,
                onNightChange = onNightChange,
                onReadingModeChange = onReadingModeChange,
                onOrientationChange = onOrientationChange,
            )
        }
    }
}

/**
 * Room for the system bars and the camera cutout on every side, including the navigation buttons
 * that sit on the right or left in landscape. Taken even while the bars are hidden, so the menu
 * does not jump when they slide in together with it.
 */
@OptIn(ExperimentalLayoutApi::class)
private val barInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

@Composable
private fun TopBar(
    title: String,
    pageLabel: String,
    settingsOpen: Boolean,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.55f))))
            .windowInsetsPadding(barInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pageLabel.isNotEmpty()) {
                Text(pageLabel, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Настройки чтения",
                tint = if (settingsOpen) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
}

/** Page navigation only: thumbnails and a slider. Settings live in their own panel. */
@Composable
private fun BottomBar(
    source: ComicSource?,
    page: Int,
    lastPage: Int,
    count: Int,
    onJump: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(page.toFloat()) }
    val sliderValue = if (dragging) dragValue else page.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.9f))))
            .windowInsetsPadding(barInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        if (source != null) PageStrip(source, count, first = page, last = lastPage, onJump = onJump)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                "${sliderValue.roundToInt() + 1}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp),
            )
            if (count > 1) {
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onJump(dragValue.roundToInt())
                    },
                    valueRange = 0f..(count - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                "$count",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.width(40.dp).padding(start = 8.dp),
            )
        }
    }
}

/** Settings that change rarely, opened with the gear and closed by tapping anywhere else. */
@Composable
private fun SettingsPanel(
    table: ReadingTable,
    night: NightSettings,
    readingMode: ReadingMode,
    orientation: OrientationLock,
    onTableChange: (ReadingTable) -> Unit,
    onNightChange: (NightSettings) -> Unit,
    onReadingModeChange: (ReadingMode) -> Unit,
    onOrientationChange: (OrientationLock) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color(0xF2201C19))
            // Taps on the panel itself must not reach the "tap outside to close" layer below.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            // On a small screen in landscape the settings scroll rather than run off the screen.
            .heightIn(max = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.8f).toDp() })
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(barInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(top = 10.dp, bottom = 12.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f)),
        )
        Text(
            "Настройки чтения",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        SettingsRow("Вид") {
            ModeChip("Авто", readingMode == ReadingMode.AUTO) { onReadingModeChange(ReadingMode.AUTO) }
            ModeChip("Разворот", readingMode == ReadingMode.SPREAD) { onReadingModeChange(ReadingMode.SPREAD) }
            ModeChip("Страница", readingMode == ReadingMode.SINGLE) { onReadingModeChange(ReadingMode.SINGLE) }
        }
        Text(
            when (readingMode) {
                ReadingMode.AUTO -> "Горизонтально — разворот, вертикально — одна страница"
                ReadingMode.SPREAD -> "Всегда две страницы, как в открытой книге"
                ReadingMode.SINGLE -> "Всегда одна страница, широкие развороты делятся пополам"
            },
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 72.dp, end = 20.dp, bottom = 6.dp),
        )
        SettingsRow("Экран") {
            ModeChip("Как в системе", orientation == OrientationLock.SYSTEM) { onOrientationChange(OrientationLock.SYSTEM) }
            ModeChip("Вертикально", orientation == OrientationLock.PORTRAIT) { onOrientationChange(OrientationLock.PORTRAIT) }
            ModeChip("Горизонтально", orientation == OrientationLock.LANDSCAPE) { onOrientationChange(OrientationLock.LANDSCAPE) }
        }
        SettingsRow("Стол") {
            ReadingTable.entries.forEach { option ->
                TableSwatch(option, selected = option == table) { onTableChange(option) }
            }
        }
        Text(
            table.label,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 72.dp, end = 20.dp, bottom = 6.dp),
        )
        SettingsRow("Ночь") {
            NightMode.entries.forEach { mode ->
                ModeChip(mode.label, night.mode == mode) { onNightChange(night.copy(mode = mode)) }
            }
        }
        AnimatedVisibility(visible = night.mode != NightMode.OFF) {
            Column {
                Text(
                    if (night.mode == NightMode.EVENING) {
                        "С ${NightSettings.EVENING_HOUR}:00 до ${NightSettings.MORNING_HOUR}:00 — тёплый свет настольной лампы"
                    } else {
                        "Тёплый свет настольной лампы, стол уходит в темноту"
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 72.dp, end = 20.dp, bottom = 2.dp),
                )
                NightSlider("Тепло", night.warmth) { onNightChange(night.copy(warmth = it)) }
                NightSlider("Свет", night.brightness) { onNightChange(night.copy(brightness = it)) }
            }
        }
    }
}

/** A setting of the night light, 0..1. */
@Composable
private fun NightSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(52.dp),
        )
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
    }
}

/** A labelled row of options that scrolls sideways on narrow screens. */
@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp),
        )
        content()
    }
}

private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(2)

/** Small pictures of all pages for quick navigation; the pages on screen are outlined. */
@Composable
private fun PageStrip(source: ComicSource, count: Int, first: Int, last: Int, onJump: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (first - 2).coerceAtLeast(0))
    val cache = remember(source) { LruCache<Int, ImageBitmap>(96) }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(count) { index ->
            val thumbnail by produceState(cache.get(index), source, index) {
                if (value == null) {
                    value = withContext(thumbnailDispatcher) {
                        runCatching { source.loadPage(index, 120, 180) }.getOrNull()?.asImageBitmap()
                    }?.also { cache.put(index, it) }
                }
            }
            val selected = index in first..last
            val shape = RoundedCornerShape(3.dp)
            Box(
                Modifier
                    .size(width = 46.dp, height = 68.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                    .clickable { onJump(index) },
            ) {
                thumbnail?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(
                    "${index + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(vertical = 1.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** "Авто" shows a spread in landscape and a single page in portrait. */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Locks the screen orientation while reading, if the reader asked for it. */
@Composable
private fun OrientationEffect(lock: OrientationLock) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, lock) {
        activity.requestedOrientation = when (lock) {
            OrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** Hides the status and navigation bars while reading; they come back with the menu. */
@Composable
private fun ImmersiveMode(showSystemBars: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(showSystemBars) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showSystemBars) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun VolumeKeys(onKey: (next: Boolean) -> Unit) {
    val host = LocalContext.current as? VolumeKeyHost ?: return
    val latest by rememberUpdatedState(onKey)
    DisposableEffect(host) {
        host.volumeKeyListener = { next -> latest(next) }
        onDispose { host.volumeKeyListener = null }
    }
}

/** GLSurfaceView has to be paused and resumed together with the activity. */
@Composable
internal fun GlLifecycle(view: GLSurfaceView?) {
    if (view == null) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
