package com.readcomic.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readcomic.data.ComicEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    comics: List<ComicEntry>,
    busy: Boolean,
    snackbar: SnackbarHostState,
    coverFile: (ComicEntry) -> File,
    onAdd: () -> Unit,
    onOpen: (ComicEntry) -> Unit,
    onRemove: (ComicEntry) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var toRemove by remember { mutableStateOf<ComicEntry?>(null) }
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
                        onLongClick = { toRemove = entry },
                    )
                }
            }
        }
    }

    toRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Убрать из библиотеки?") },
            text = { Text("«${entry.title}» пропадёт из списка. Сам файл останется на месте.") },
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

/** Shows one-off messages from the view model. */
@Composable
fun MessagesEffect(messages: kotlinx.coroutines.flow.SharedFlow<String>, snackbar: SnackbarHostState) {
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(it) }
    }
}
