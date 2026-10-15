package com.readcomic.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readcomic.data.ComicEntry
import com.readcomic.data.ComicOpener
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A page of the screensaver: its picture, where it lies, where it comes from. */
private class SaverPage(val image: ImageBitmap, val tile: SaverTile, val title: String, val page: Int, val arrived: Float)

/**
 * The screensaver of the library: pages taken at random from the collection, strewn over a dark
 * table, and a camera flying slowly from one detail to the next. It comes up softly once there
 * is something to show; [visible] false fades it away.
 */
@Composable
fun ScreenSaver(comics: List<ComicEntry>, visible: Boolean, onGone: () -> Unit) {
    val context = LocalContext.current
    val seed = remember { Random.nextInt() }
    val flight = remember { SaverFlight(Random(seed), PAGES) }
    val pages = remember { mutableStateListOf<SaverPage>() }
    var time by remember { mutableFloatStateOf(0f) }
    val shown = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }

    // Pages are opened one at a time in the background; the first few are enough to begin.
    LaunchedEffect(Unit) {
        val random = Random(seed + 1)
        val picks = choosePages(comics, random)
        for ((k, pick) in picks.withIndex()) {
            val (entry, index) = pick
            val bitmap = withContext(Dispatchers.IO) { loadPage(context, entry, index) } ?: continue
            val aspect = bitmap.width.toFloat() / bitmap.height
            pages += SaverPage(bitmap.asImageBitmap(), flight.tile(pages.size, aspect), entry.title, index, time)
            if (pages.size >= START_PAGES) ready = true
            if (k == picks.lastIndex) ready = true
        }
        ready = true
    }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                time = (now - start) / 1e9f
            }
        }
    }
    LaunchedEffect(ready, visible) {
        when {
            !visible -> {
                shown.animateTo(0f, tween(FADE_OUT_MILLIS))
                onGone()
            }
            ready && pages.isNotEmpty() -> shown.animateTo(1f, tween(FADE_IN_MILLIS))
        }
    }
    // Keeps the screen on for a while, as a screensaver should; then the phone may sleep.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(view) {
        delay(KEEP_ON_MILLIS)
        view.keepScreenOn = false
    }

    if (shown.value <= 0f) return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Table)
            val tiles = pages.map { it.tile }
            // The flight starts once the screensaver is up.
            val camera = flight.camera(time, tiles, size.width, size.height)
            withTransform({
                translate(size.width / 2f, size.height / 2f)
                rotate(-camera.rotation, pivot = Offset.Zero)
                scale(camera.scale, camera.scale, pivot = Offset.Zero)
                translate(-camera.x, -camera.y)
            }) {
                for (page in pages) {
                    val t = page.tile
                    val fade = ((time - page.arrived) / 1.2f).coerceIn(0f, 1f)
                    rotate(t.rotation, pivot = Offset(t.x, t.y)) {
                        val left = t.x - t.width / 2f
                        val top = t.y - t.height / 2f
                        // A soft shadow under the sheet, then the paper with its margin.
                        for (k in 1..3) {
                            val grow = k * 5f
                            drawRect(
                                Color.Black.copy(alpha = 0.16f * fade),
                                topLeft = Offset(left - grow + 10f, top - grow + 16f),
                                size = Size(t.width + 2 * grow, t.height + 2 * grow),
                            )
                        }
                        drawRect(Paper.copy(alpha = fade), topLeft = Offset(left, top), size = Size(t.width, t.height))
                        val margin = t.width * 0.025f
                        drawImage(
                            page.image,
                            dstOffset = IntOffset((left + margin).toInt(), (top + margin).toInt()),
                            dstSize = IntSize((t.width - 2 * margin).toInt(), (t.height - 2 * margin).toInt()),
                            alpha = fade,
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
            }
            // A vignette, like an old photograph of the table.
            drawRect(
                Brush.radialGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.65f),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = max(size.width, size.height) * 0.75f,
                ),
            )
        }
        // Where the page on screen comes from, while the camera lingers on it.
        val current = pages.getOrNull(flight.currentTile)
        val linger = flight.lingering(time)
        val caption = (minOf(linger / 0.18f, (1f - linger) / 0.18f)).coerceIn(0f, 1f)
        if (current != null && caption > 0f) {
            Text(
                "${current.title} · стр. ${current.page + 1}",
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 16.sp,
                color = Color(0xFFF3E6D0).copy(alpha = 0.75f * caption),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
            )
        }
    }
}

/** Which pages to show: a few from every comic, at random, the covers too. */
private fun choosePages(comics: List<ComicEntry>, random: Random): List<Pair<ComicEntry, Int>> {
    val usable = comics.filter { it.pageCount > 0 }
    if (usable.isEmpty()) return emptyList()
    val shuffled = usable.shuffled(random)
    return List(PAGES) { k ->
        val entry = shuffled[k % shuffled.size]
        entry to random.nextInt(entry.pageCount)
    }.distinct()
}

private fun loadPage(context: Context, entry: ComicEntry, index: Int): Bitmap? = runCatching {
    ComicOpener.open(context, entry.uri).use { source -> source.loadPage(index, PAGE_WIDTH, PAGE_HEIGHT) }
}.getOrNull()

private val Table = Color(0xFF0E0C0B)
private val Paper = Color(0xFFF4EFE6)
private const val PAGES = 16
private const val START_PAGES = 3
private const val PAGE_WIDTH = 900
private const val PAGE_HEIGHT = 1000
private const val FADE_IN_MILLIS = 1600
private const val FADE_OUT_MILLIS = 420
private const val KEEP_ON_MILLIS = 10 * 60 * 1000L
