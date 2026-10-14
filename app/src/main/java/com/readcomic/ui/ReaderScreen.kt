package com.readcomic.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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
import com.readcomic.data.ComicEntry
import com.readcomic.data.ComicOpener
import com.readcomic.data.ComicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Lets the activity forward volume keys to the open reader. */
interface VolumeKeyHost {
    var volumeKeyListener: ((next: Boolean) -> Unit)?
}

@Composable
fun ReaderScreen(
    entry: ComicEntry,
    backgroundTint: Int,
    onBackgroundTintChange: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var source by remember(entry.uri) { mutableStateOf<ComicSource?>(null) }
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var page by remember(entry.id) { mutableIntStateOf(entry.currentPage) }
    var overlay by remember { mutableStateOf(false) }
    var curlView by remember { mutableStateOf<PageCurlView?>(null) }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)

    LaunchedEffect(entry.uri) {
        try {
            source = withContext(Dispatchers.IO) { ComicOpener.open(context, entry.uri) }
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

    ImmersiveMode(showSystemBars = overlay)
    VolumeKeys { next -> if (next) curlView?.turnNext() else curlView?.turnPrevious() }

    val tint = ReaderBackgrounds[backgroundTint.coerceIn(0, ReaderBackgrounds.lastIndex)]
    LaunchedEffect(curlView, tint) {
        curlView?.setBackgroundTint(tint.red, tint.green, tint.blue)
    }

    Box(Modifier.fillMaxSize().background(tint)) {
        val opened = source
        when {
            opened != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PageCurlView(ctx).apply {
                            keepScreenOn = true
                            callbacks = object : PageCurlView.Callbacks {
                                override fun onPageChanged(index: Int) {
                                    page = index
                                    latestOnPageChanged(index)
                                }

                                override fun onCenterTap() {
                                    overlay = !overlay
                                }
                            }
                            setComic(PageLoader { index, w, h -> opened.loadPage(index, w, h) }, opened.pageCount, entry.currentPage)
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

            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = overlay || error != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = entry.title,
                pageLabel = source?.let { "${page + 1} / ${it.pageCount}" } ?: "",
                onBack = onBack,
            )
        }

        val count = source?.pageCount ?: 0
        AnimatedVisibility(
            visible = overlay && count > 0,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                page = page,
                count = count,
                backgroundTint = backgroundTint,
                onJump = { target -> curlView?.jumpTo(target) },
                onBackgroundTintChange = onBackgroundTintChange,
            )
        }
    }
}

@Composable
private fun TopBar(title: String, pageLabel: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.55f))))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            pageLabel,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun BottomBar(
    page: Int,
    count: Int,
    backgroundTint: Int,
    onJump: (Int) -> Unit,
    onBackgroundTintChange: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(page.toFloat()) }
    val sliderValue = if (dragging) dragValue else page.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Фон", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            ReaderBackgrounds.forEachIndexed { index, color ->
                Box(
                    Modifier
                        .size(26.dp)
                        .background(color, CircleShape)
                        .border(
                            width = if (index == backgroundTint) 2.dp else 1.dp,
                            color = if (index == backgroundTint) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f),
                            shape = CircleShape,
                        )
                        .clickable { onBackgroundTintChange(index) },
                )
            }
        }
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
private fun GlLifecycle(view: PageCurlView?) {
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
