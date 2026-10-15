package com.readcomic

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readcomic.ui.FolderState
import com.readcomic.ui.LibraryScreen
import com.readcomic.ui.LibraryViewModel
import com.readcomic.ui.MessagesEffect
import com.readcomic.ui.OrientationLock
import com.readcomic.ui.ReadcomicTheme
import com.readcomic.ui.ReaderScreen
import com.readcomic.ui.VolumeKeyHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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

    BackHandler(enabled = openId != null) { openId = null }

    // What is on screen follows [openId] behind a dark curtain. The reader may keep its own
    // orientation: the screen then turns while nothing but the curtain is shown, instead of the
    // whole library spinning round.
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    val curtain = remember { Animatable(0f) }
    val activity = LocalActivity.current
    val screenOrientation by rememberUpdatedState(LocalConfiguration.current.orientation)
    val lock by rememberUpdatedState(orientation)
    LaunchedEffect(openId) {
        val target = openId
        if (target == shownId) {
            curtain.animateTo(0f, tween(CURTAIN_OUT_MILLIS, easing = LinearOutSlowInEasing))
            return@LaunchedEffect
        }
        curtain.animateTo(1f, tween(CURTAIN_IN_MILLIS, easing = FastOutLinearInEasing))
        val window = activity?.window
        try {
            // Behind the curtain the turn needs no animation of its own.
            window?.setRotationAnimation(WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT)
            val before = screenOrientation
            shownId = target
            if (lock != OrientationLock.SYSTEM) {
                val landscape = before == Configuration.ORIENTATION_LANDSCAPE
                val turns = when {
                    // Into the reader: does its orientation differ from the library's?
                    target != null -> (lock == OrientationLock.LANDSCAPE) != landscape
                    // Back to the library: without auto-rotation the system goes back to its own.
                    else -> !autoRotation(activity)
                }
                withTimeoutOrNull(if (turns) ROTATION_WAIT_MILLIS else ROTATION_MAYBE_MILLIS) {
                    snapshotFlow { screenOrientation }.first { it != before }
                }
            }
            // A few frames for the new screen to lay out at its size.
            delay(SETTLE_MILLIS)
            curtain.animateTo(0f, tween(CURTAIN_OUT_MILLIS, easing = LinearOutSlowInEasing))
        } finally {
            window?.setRotationAnimation(WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val entry = comics.firstOrNull { it.id == shownId }
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

        if (curtain.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = curtain.value }
                    .background(Curtain)
                    // Nothing underneath can be touched while it is drawn.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}

private fun Window.setRotationAnimation(animation: Int) {
    attributes = attributes.apply { rotationAnimation = animation }
}

/** Whether the screen turns by itself with the phone (the quick setting "auto-rotate"). */
private fun autoRotation(activity: Activity?): Boolean =
    activity != null && Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

private val Curtain = Color(0xFF0C0B0A)
private const val CURTAIN_IN_MILLIS = 220
private const val CURTAIN_OUT_MILLIS = 380
private const val SETTLE_MILLIS = 90L
private const val ROTATION_WAIT_MILLIS = 900L
private const val ROTATION_MAYBE_MILLIS = 350L