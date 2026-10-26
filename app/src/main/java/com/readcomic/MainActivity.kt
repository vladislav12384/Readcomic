package com.readcomic

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.runtime.DisposableEffect
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
import com.readcomic.shelf.ShelfArtMemory
import com.readcomic.ui.CoverThumbnails
import com.readcomic.ui.FolderState
import com.readcomic.ui.GridLook
import com.readcomic.ui.LibraryScreen
import com.readcomic.ui.LibraryViewModel
import com.readcomic.ui.MessagesEffect
import com.readcomic.ui.OrientationLock
import com.readcomic.ui.ReadcomicTheme
import com.readcomic.ui.ReaderScreen
import com.readcomic.ui.ScreenSaver
import com.readcomic.ui.UnboxSettings
import com.readcomic.ui.UpdateUi
import com.readcomic.ui.VolumeKeyHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity(), VolumeKeyHost {
    private val viewModel: LibraryViewModel by viewModels()

    override var volumeKeyListener: ((next: Boolean) -> Unit)? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Pictures of the shelves kept for a quick way back are the first to go.
        ShelfArtMemory.onTrimMemory(level)
        CoverThumbnails.onTrimMemory(level)
    }

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

    /** When the screen was last touched (uptime millis): the screensaver comes after a while. */
    @Volatile
    var lastTouch = SystemClock.uptimeMillis()
        private set

    /** While the screensaver is up: puts it away, returning true, and the touch does nothing else. */
    var onSaverTouch: (() -> Boolean)? = null
    private var swallowing = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) swallowing = onSaverTouch?.invoke() == true
        if (swallowing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Coming back is not being idle: no screensaver straight away.
        lastTouch = SystemClock.uptimeMillis()
        onSaverTouch?.invoke()
    }

    // Any key counts too, not only touches.
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouch = SystemClock.uptimeMillis()
    }
}

/**
 * How long the library waits untouched before the screensaver: a minute, or less if the screen
 * would go dark sooner by itself.
 */
private fun saverDelay(activity: Activity?): Long {
    val screenOff = activity?.let { Settings.System.getInt(it.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000) } ?: 60_000
    return (screenOff - 8_000L).coerceIn(15_000L, 60_000L)
}

@Composable
private fun App(viewModel: LibraryViewModel) {
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val openRequest by viewModel.openRequest.collectAsStateWithLifecycle()
    val table by viewModel.table.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle()
    val doubleTapZoom by viewModel.doubleTapZoom.collectAsStateWithLifecycle()
    val edgeSpring by viewModel.edgeSpring.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val shelfMode by viewModel.shelfMode.collectAsStateWithLifecycle()
    val shelfTheme by viewModel.shelfTheme.collectAsStateWithLifecycle()
    val shelfHintDone by viewModel.shelfHintDone.collectAsStateWithLifecycle()
    val handHint by viewModel.handHint.collectAsStateWithLifecycle()
    val shelfOrder by viewModel.shelfOrder.collectAsStateWithLifecycle()
    val shelfSize by viewModel.shelfSize.collectAsStateWithLifecycle()
    val shelfStyle by viewModel.shelfStyle.collectAsStateWithLifecycle()
    val shelfColumns by viewModel.shelfColumns.collectAsStateWithLifecycle()
    val caseLight by viewModel.caseLight.collectAsStateWithLifecycle()
    val gridStyle by viewModel.gridStyle.collectAsStateWithLifecycle()
    val gridByShape by viewModel.gridByShape.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val groupSeries by viewModel.groupSeries.collectAsStateWithLifecycle()
    val wrapNew by viewModel.wrapNew.collectAsStateWithLifecycle()
    val unboxSound by viewModel.unboxSound.collectAsStateWithLifecycle()
    val boxHintDone by viewModel.boxHintDone.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()
    val checkingByHand by viewModel.checkingByHand.collectAsStateWithLifecycle()
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

    // The screensaver, after a while in the library without a touch; a touch puts it away.
    val host = activity as? MainActivity
    var saving by remember { mutableStateOf(false) }
    var saverUp by remember { mutableStateOf(false) }
    val hasComics = comics.isNotEmpty()
    LaunchedEffect(shownId, hasComics) {
        if (shownId != null || !hasComics) {
            saving = false
            return@LaunchedEffect
        }
        while (true) {
            val wait = saverDelay(activity) - (SystemClock.uptimeMillis() - (host?.lastTouch ?: 0L))
            if (wait > 0) {
                delay(wait)
                continue
            }
            saving = true
            saverUp = true
            snapshotFlow { saving }.first { !it }
        }
    }
    DisposableEffect(host) {
        host?.onSaverTouch = {
            if (saving) {
                saving = false
                true
            } else {
                false
            }
        }
        onDispose { host?.onSaverTouch = null }
    }
    BackHandler(enabled = saving) { saving = false }

    Box(Modifier.fillMaxSize()) {
        val entry = comics.firstOrNull { it.id == shownId }
        if (entry != null) {
            key(entry.id) {
                ReaderScreen(
                    entry = entry,
                    table = table,
                    night = night,
                    readingMode = readingMode,
                    doubleTapZoom = doubleTapZoom,
                    edgeSpring = edgeSpring,
                    orientation = orientation,
                    pageAspects = { source, onProgress -> viewModel.pageAspects(entry, source, onProgress) },
                    onTableChange = viewModel::setTable,
                    onNightChange = viewModel::setNight,
                    onReadingModeChange = viewModel::setReadingMode,
                    onDoubleTapZoomChange = viewModel::setDoubleTapZoom,
                    onEdgeSpringChange = viewModel::setEdgeSpring,
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
                handHint = handHint,
                onHandHintShown = viewModel::handHintShown,
                loaded = loaded,
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
                onOpen = {
                    // Opened from the plain grid while still in its box: no need to unwrap it any more.
                    if (it.wrapped) viewModel.unwrap(it.id)
                    openId = it.id
                },
                onRemove = viewModel::remove,
                order = shelfOrder,
                onOrderChange = viewModel::setShelfOrder,
                gridLook = GridLook(
                    style = gridStyle,
                    byShape = gridByShape,
                    onStyleChange = viewModel::setGridStyle,
                    onByShapeChange = viewModel::setGridByShape,
                    columns = gridColumns,
                    onColumnsChange = viewModel::setGridColumns,
                ),
                onSetSeries = viewModel::setSeries,
                onDrawnBackChange = viewModel::setDrawnBack,
                shelfSize = shelfSize,
                onShelfSizeChange = viewModel::setShelfSize,
                shelfStyle = shelfStyle,
                onShelfStyleChange = viewModel::setShelfStyle,
                shelfColumns = shelfColumns,
                onShelfColumnsChange = viewModel::setShelfColumns,
                caseLight = caseLight,
                onCaseLightChange = viewModel::setCaseLight,
                onStartOver = { entry ->
                    viewModel.startOver(entry)
                    openId = entry.id
                },
                unboxing = UnboxSettings(
                    wrapNew = wrapNew,
                    onWrapNewChange = viewModel::setWrapNew,
                    sound = unboxSound,
                    onSoundChange = viewModel::setUnboxSound,
                    hintDone = boxHintDone,
                    onHintSeen = viewModel::boxHintSeen,
                    onUnwrap = viewModel::unwrap,
                    onUnwrapAll = viewModel::unwrapAll,
                ),
                updates = UpdateUi(
                    state = update,
                    enabled = updateCheck,
                    currentBuild = viewModel.currentBuild,
                    preview = viewModel.isPreview,
                    checkingByHand = checkingByHand,
                    onEnabledChange = viewModel::setUpdateCheck,
                    onCheckNow = viewModel::checkForUpdate,
                    onDownload = viewModel::downloadUpdate,
                    onCancel = viewModel::cancelUpdate,
                    onPutOff = viewModel::putOffUpdate,
                    onInstall = viewModel::installUpdate,
                    canInstall = viewModel::canInstallUpdates,
                ),
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

        if (saverUp && shownId == null) {
            ScreenSaver(comics, visible = saving, onGone = { saverUp = false })
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