package com.readcomic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readcomic.data.ScanInterval
import com.readcomic.data.ScanProgress
import com.readcomic.data.WatchedFolder
import java.text.DateFormat
import java.util.Date

/** Watched folders and what can be done with them, for the library screens. */
class FolderState(
    val folders: List<WatchedFolder>,
    val comicsIn: (WatchedFolder) -> Int,
    val scanning: ScanProgress?,
    val interval: ScanInterval,
    val onAddFolder: () -> Unit,
    val onRemoveFolder: (WatchedFolder) -> Unit,
    val onScanNow: () -> Unit,
    val onIntervalChange: (ScanInterval) -> Unit,
)

/** Adding to the collection: files, or a folder to watch, and the folders already watched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionSheet(folders: FolderState, onAddFiles: () -> Unit, onDismiss: () -> Unit) {
    var leaving by remember { mutableStateOf<WatchedFolder?>(null) }
    leaving?.let { folder ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Не следить за папкой?") },
            text = { Text("Комиксы из «${folder.name}» уйдут с полок. Сами файлы останутся на месте.") },
            confirmButton = {
                TextButton(onClick = {
                    folders.onRemoveFolder(folder)
                    leaving = null
                }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Отмена") } },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1915),
        contentColor = Color(0xFFF3E6D0),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                "Пополнить коллекцию",
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
            Spacer(Modifier.height(16.dp))
            Choice(
                icon = Icons.Filled.Add,
                title = "Файлы комиксов",
                subtitle = "CBZ, CBR, CB7, CBT, EPUB, PDF, ZIP, RAR, 7z",
                onClick = {
                    onDismiss()
                    onAddFiles()
                },
            )
            Spacer(Modifier.height(10.dp))
            Choice(
                icon = FolderIcon,
                title = "Папку",
                subtitle = "Всё, что в ней есть и появится потом, само встанет на полки. Папка с картинками — тоже комикс.",
                onClick = folders.onAddFolder,
            )

            if (folders.folders.isNotEmpty() || folders.scanning != null) {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Папки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = folders.onScanNow, enabled = folders.scanning == null) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Проверить сейчас")
                    }
                }
                folders.scanning?.let { scan ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Смотрю «${scan.folder}»" + if (scan.found > 0) " · новых ${scan.added} из ${scan.found}" else "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF3E6D0).copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                }
                Spacer(Modifier.height(8.dp))
                for (folder in folders.folders) {
                    FolderRow(folder, folders.comicsIn(folder), onRemove = { leaving = folder })
                }
                Spacer(Modifier.height(16.dp))
                Text("Искать новые", style = MaterialTheme.typography.labelLarge, color = Color(0xFFF3E6D0).copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (interval in ScanInterval.entries) {
                        FilterChip(
                            selected = interval == folders.interval,
                            onClick = { folders.onIntervalChange(interval) },
                            label = { Text(interval.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFF3E6D0)),
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF3A2A1C))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF3E6D0).copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun FolderRow(folder: WatchedFolder, comics: Int, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(FolderIcon, contentDescription = null, tint = Color(0xFFF3E6D0).copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(comicsCount(comics))
                    if (folder.lastScan > 0) {
                        append(" · проверена ")
                        append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(folder.lastScan)))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF3E6D0).copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Не следить за папкой", tint = Color(0xFFF3E6D0).copy(alpha = 0.6f))
        }
    }
}

/** A folder, for adding and watching folders. */
val FolderIcon: ImageVector by lazy {
    Builder("Folder", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 6f)
            quadTo(3f, 4.5f, 4.5f, 4.5f)
            horizontalLineTo(9.2f)
            lineTo(11.2f, 6.5f)
            horizontalLineTo(19.5f)
            quadTo(21f, 6.5f, 21f, 8f)
            verticalLineTo(18f)
            quadTo(21f, 19.5f, 19.5f, 19.5f)
            horizontalLineTo(4.5f)
            quadTo(3f, 19.5f, 3f, 18f)
            close()
        }
    }.build()
}
