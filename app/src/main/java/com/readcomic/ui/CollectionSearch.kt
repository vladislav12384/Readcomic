package com.readcomic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readcomic.data.ComicEntry
import com.readcomic.data.Series
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Glass = Color(0xEB1B1713)
private val Ink = Color(0xFFF3E6D0)

/**
 * Searching the collection: a field on dark glass and, under it, a strip of the covers found.
 * A tap on a cover picks that comic ([onPick]).
 */
@Composable
fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    found: List<ComicEntry>,
    coverFile: (ComicEntry) -> File,
    onPick: (ComicEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Glass,
            contentColor = Ink,
            shadowElevation = 14.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp).padding(start = 18.dp, end = 4.dp)) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Ink.copy(alpha = 0.7f))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Название, серия, автор, год",
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 17.sp,
                            color = Ink.copy(alpha = 0.45f),
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 17.sp, fontFamily = FontFamily.Serif),
                        cursorBrush = SolidColor(Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            found.singleOrNull()?.let(onPick)
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                IconButton(onClick = { if (query.isEmpty()) onClose() else onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = if (query.isEmpty()) "Закрыть поиск" else "Стереть", tint = Ink.copy(alpha = 0.75f))
                }
            }
        }

        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Glass,
                contentColor = Ink,
                shadowElevation = 14.dp,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    Text(
                        if (found.isEmpty()) "Ничего не нашлось" else "Нашлось: ${comicsCount(found.size)}",
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 15.sp,
                        color = Ink.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    if (found.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(found, key = { it.id }) { entry ->
                                FoundCover(entry, coverFile(entry), onClick = {
                                    keyboard?.hide()
                                    onPick(entry)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundCover(entry: ComicEntry, coverFile: File, onClick: () -> Unit) {
    val cover by produceState(CoverThumbnails.cached(coverFile, frontOnly = true), coverFile) {
        value = withContext(Dispatchers.IO) { CoverThumbnails.load(coverFile, frontOnly = true) }
    }
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.width(84.dp).clickable(onClickLabel = entry.title, onClick = onClick)) {
        Box(
            Modifier
                .size(84.dp, 126.dp)
                .shadow(8.dp, shape)
                .clip(shape)
                .background(Color(0xFF2A231D)),
        ) {
            cover?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            // The fold of the spine, as on the shelf.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.07f to Color.Transparent),
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.title,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Ink.copy(alpha = 0.85f),
        )
    }
}

/**
 * Putting a comic into a series, or taking it out: the name to type, or one of the series
 * already on the shelves, or the one its title suggests.
 */
@Composable
fun SeriesDialog(
    entry: ComicEntry,
    known: List<String>,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val guess = remember(entry.title) {
        Series.of(entry.title).takeIf { it.number != null || it.volume != null }?.name
    }
    var name by remember(entry.id) { mutableStateOf(entry.series ?: guess ?: "") }
    val suggestions = remember(known, guess) {
        (listOfNotNull(guess) + known).distinctBy { Series.keyOf(it) }.take(8)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Серия", fontFamily = FontFamily.Serif) },
        text = {
            Column {
                Text(
                    "Выпуски одной серии стоят рядом, по порядку номеров, под латунной табличкой с её названием.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Название серии") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            val chosen = Series.keyOf(suggestion) == Series.keyOf(name)
                            Text(
                                suggestion,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (chosen) 1f else 0.4f), CircleShape)
                                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { name = suggestion }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifEmpty { null }) }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (entry.series != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Убрать из серии") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** The ways of standing comics on the shelves, to choose from. */
@Composable
fun OrderPicker(selected: ShelfOrder, onSelect: (ShelfOrder) -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xF21B1713),
        contentColor = Ink,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(vertical = 10.dp).width(250.dp)) {
            Text(
                "Как расставить",
                style = MaterialTheme.typography.labelLarge,
                color = Ink.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
            ShelfOrder.entries.forEach { order ->
                val chosen = order == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(order) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (chosen) Ink else Color.Transparent)
                            .border(1.5.dp, Ink.copy(alpha = if (chosen) 1f else 0.4f), CircleShape),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        order.label,
                        fontFamily = FontFamily.Serif,
                        fontStyle = if (chosen) FontStyle.Italic else FontStyle.Normal,
                        fontSize = 17.sp,
                        color = if (chosen) Ink else Ink.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** Three lines getting shorter: how the comics are ordered. */
val SortIcon: ImageVector by lazy {
    ImageVector.Builder("Sort", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            for ((y, length) in listOf(6f to 16f, 11f to 11f, 16f to 6f)) {
                moveTo(4f, y)
                horizontalLineTo(4f + length)
                verticalLineTo(y + 2f)
                horizontalLineTo(4f)
                close()
            }
        }
    }.build()
}

/** Comics stacked one behind the other: a series. */
val SeriesIcon: ImageVector by lazy {
    ImageVector.Builder("Series", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            // The front comic...
            moveTo(4f, 8f)
            horizontalLineTo(14f)
            verticalLineTo(21f)
            horizontalLineTo(4f)
            close()
        }
        path(fill = SolidColor(Color.Black), fillAlpha = 0.6f) {
            // ...and the edges of two more behind it.
            moveTo(7f, 5f)
            horizontalLineTo(17f)
            verticalLineTo(18f)
            horizontalLineTo(15.5f)
            verticalLineTo(6.5f)
            horizontalLineTo(7f)
            close()
        }
        path(fill = SolidColor(Color.Black), fillAlpha = 0.35f) {
            moveTo(10f, 2f)
            horizontalLineTo(20f)
            verticalLineTo(15f)
            horizontalLineTo(18.5f)
            verticalLineTo(3.5f)
            horizontalLineTo(10f)
            close()
        }
    }.build()
}
