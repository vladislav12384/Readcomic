package com.readcomic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readcomic.ui.FolderState
import com.readcomic.ui.LibraryScreen
import com.readcomic.ui.LibraryViewModel
import com.readcomic.ui.MessagesEffect
import com.readcomic.ui.ReadcomicTheme
import com.readcomic.ui.ReaderScreen
import com.readcomic.ui.VolumeKeyHost

class MainActivity : ComponentActivity(), VolumeKeyHost {
    private val viewModel: LibraryViewModel by viewModels()

    override var volumeKeyListener: ((next: Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ReadcomicTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        viewModel.openExternal(uri)
    }

    // While a comic is open the volume keys turn pages instead of changing the volume.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val listener = volumeKeyListener
        if (listener != null && isVolumeKey(keyCode)) {
            listener(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeyListener != null && isVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
}

@Composable
private fun App(viewModel: LibraryViewModel) {
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val openRequest by viewModel.openRequest.collectAsStateWithLifecycle()
    val table by viewModel.table.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val shelfMode by viewModel.shelfMode.collectAsStateWithLifecycle()
    val shelfTheme by viewModel.shelfTheme.collectAsStateWithLifecycle()
    val shelfHintDone by viewModel.shelfHintDone.collectAsStateWithLifecycle()
    val shelfOrder by viewModel.shelfOrder.collectAsStateWithLifecycle()
    val groupSeries by viewModel.groupSeries.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    MessagesEffect(viewModel.messages, snackbar)

    LaunchedEffect(openRequest) {
        openRequest?.let {
            openId = it
            viewModel.consumeOpenRequest()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addComics(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addFolder)
    }
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val scanInterval by viewModel.scanInterval.collectAsStateWithLifecycle()

    val opened = comics.firstOrNull { it.id == openId }
    BackHandler(enabled = opened != null) { openId = null }

    AnimatedContent(
        targetState = opened?.id,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { id ->
        val entry = comics.firstOrNull { it.id == id }
        if (entry != null) {
            key(entry.id) {
                ReaderScreen(
                    entry = entry,
                    table = table,
                    night = night,
                    readingMode = readingMode,
                    orientation = orientation,
                    pageAspects = { source, onProgress -> viewModel.pageAspects(entry, source, onProgress) },
                    onTableChange = viewModel::setTable,
                    onNightChange = viewModel::setNight,
                    onReadingModeChange = viewModel::setReadingMode,
                    onOrientationChange = viewModel::setOrientation,
                    onPageChanged = { first, last -> viewModel.updateProgress(entry.id, first, last) },
                    onBack = { openId = null },
                )
            }
        } else {
            LibraryScreen(
                comics = comics,
                busy = busy,
                snackbar = snackbar,
                coverFile = viewModel::coverFile,
                shelfMode = shelfMode,
                shelfScroll = viewModel.shelfScroll,
                shelfTheme = shelfTheme,
                shelfHintDone = shelfHintDone,
                onShelfModeChange = viewModel::setShelfMode,
                onShelfScroll = { viewModel.shelfScroll = it },
                onShelfThemeChange = viewModel::setShelfTheme,
                onShelfHintSeen = viewModel::shelfHintSeen,
                folders = FolderState(
                    folders = folders,
                    comicsIn = { folder -> comics.count { it.folder == folder.uri } },
                    scanning = scanning,
                    interval = scanInterval,
                    onAddFolder = { folderPicker.launch(null) },
                    onRemoveFolder = viewModel::removeFolder,
                    onScanNow = viewModel::scanNow,
                    onIntervalChange = viewModel::setScanInterval,
                    groupSeries = groupSeries,
                    onGroupSeriesChange = viewModel::setGroupSeries,
                ),
                onAdd = { picker.launch(arrayOf("*/*")) },
                onOpen = { openId = it.id },
                onRemove = viewModel::remove,
                order = shelfOrder,
                onOrderChange = viewModel::setShelfOrder,
                onSetSeries = viewModel::setSeries,
            )
        }
    }
}
