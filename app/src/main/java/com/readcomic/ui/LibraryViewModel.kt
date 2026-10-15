package com.readcomic.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readcomic.curl.PageTurnController.ReadingMode
import com.readcomic.data.ComicEntry
import com.readcomic.data.ComicSource
import com.readcomic.data.LibraryRepository
import com.readcomic.data.ScanInterval
import com.readcomic.data.ScanReport
import com.readcomic.data.WatchedFolder
import com.readcomic.shelf.ShelfSize
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryRepository.get(application)
    private val prefs = application.getSharedPreferences("settings", 0)

    val comics: StateFlow<List<ComicEntry>> = library.comics
    val folders: StateFlow<List<WatchedFolder>> = library.folders
    val scanning = library.scanning
    val scanInterval: StateFlow<ScanInterval> = library.scanInterval

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Comic to open in the reader (set when the app is launched with a file). */
    private val _openRequest = MutableStateFlow<String?>(null)
    val openRequest: StateFlow<String?> = _openRequest.asStateFlow()

    /** The table the comic lies on while reading; earlier versions had plain colours instead. */
    private val _table = MutableStateFlow(
        runCatching { ReadingTable.valueOf(prefs.getString(KEY_TABLE, null) ?: "") }
            .getOrElse { ReadingTable.fromOldBackground(prefs.getInt(KEY_BACKGROUND, 0)) },
    )
    val table: StateFlow<ReadingTable> = _table.asStateFlow()

    private val _night = MutableStateFlow(
        NightSettings(
            mode = runCatching { NightMode.valueOf(prefs.getString(KEY_NIGHT, null) ?: "") }.getOrDefault(NightMode.OFF),
            warmth = prefs.getFloat(KEY_NIGHT_WARMTH, NightSettings().warmth),
            brightness = prefs.getFloat(KEY_NIGHT_BRIGHTNESS, NightSettings().brightness),
        ),
    )
    val night: StateFlow<NightSettings> = _night.asStateFlow()

    private val _readingMode = MutableStateFlow(
        runCatching { ReadingMode.valueOf(prefs.getString(KEY_READING_MODE, null) ?: "") }.getOrDefault(ReadingMode.SPREAD),
    )
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private val _orientation = MutableStateFlow(
        runCatching { OrientationLock.valueOf(prefs.getString(KEY_ORIENTATION, null) ?: "") }.getOrDefault(OrientationLock.SYSTEM),
    )
    val orientation: StateFlow<OrientationLock> = _orientation.asStateFlow()

    /** The library as a wall of shelves (true) or a plain grid of covers. */
    private val _shelfMode = MutableStateFlow(prefs.getBoolean(KEY_SHELF_MODE, true))
    val shelfMode: StateFlow<Boolean> = _shelfMode.asStateFlow()

    /** Where the shelves were scrolled to, to come back there after reading. */
    var shelfScroll = Float.NaN

    /** Paint of the wall behind the shelves, an index into [ShelfThemes]. */
    private val _shelfTheme = MutableStateFlow(prefs.getInt(KEY_SHELF_THEME, 0).coerceIn(0, ShelfThemes.lastIndex))
    val shelfTheme: StateFlow<Int> = _shelfTheme.asStateFlow()

    /** How the comics stand on the shelves (and in the grid). */
    private val _shelfOrder = MutableStateFlow(
        runCatching { ShelfOrder.valueOf(prefs.getString(KEY_SHELF_ORDER, null) ?: "") }.getOrDefault(ShelfOrder.TITLE),
    )
    val shelfOrder: StateFlow<ShelfOrder> = _shelfOrder.asStateFlow()

    /** How large the comics look on the shelves. */
    private val _shelfSize = MutableStateFlow(
        runCatching { ShelfSize.valueOf(prefs.getString(KEY_SHELF_SIZE, null) ?: "") }.getOrDefault(ShelfSize.NORMAL),
    )
    val shelfSize: StateFlow<ShelfSize> = _shelfSize.asStateFlow()

    /** Whether comics being added go into their series; remembered for the next time. */
    private val _groupSeries = MutableStateFlow(prefs.getBoolean(KEY_GROUP_SERIES, false))
    val groupSeries: StateFlow<Boolean> = _groupSeries.asStateFlow()

    /** Whether new comics arrive wrapped in a box, to be unwrapped by hand before they go on the shelf. */
    private val _wrapNew = MutableStateFlow(library.wrapNew)
    val wrapNew: StateFlow<Boolean> = _wrapNew.asStateFlow()

    /** The rustle of paper and the rest of the sounds of unpacking. */
    private val _unboxSound = MutableStateFlow(prefs.getBoolean(KEY_UNBOX_SOUND, true))
    val unboxSound: StateFlow<Boolean> = _unboxSound.asStateFlow()

    /** Whether the reader has already opened the box of new comics once. */
    private val _boxHintDone = MutableStateFlow(prefs.getBoolean(KEY_BOX_HINT, false))
    val boxHintDone: StateFlow<Boolean> = _boxHintDone.asStateFlow()

    /** Whether the reader has already found out that comics can be taken off the shelf. */
    private val _shelfHintDone = MutableStateFlow(prefs.getBoolean(KEY_SHELF_HINT, false))
    val shelfHintDone: StateFlow<Boolean> = _shelfHintDone.asStateFlow()

    init {
        // New comics may have appeared in the watched folders while the app was closed.
        viewModelScope.launch {
            library.awaitReady()
            library.scheduleScans()
            if (library.folders.value.isNotEmpty()) report(library.scanAll(), quiet = true)
        }
    }

    fun coverFile(entry: ComicEntry) = library.coverFile(entry.id)

    fun addComics(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val failed = library.addFiles(uris, _groupSeries.value)
            _busy.value = false
            if (failed > 0) {
                _messages.tryEmit(if (uris.size == 1) "Не удалось открыть файл" else "Не удалось открыть файлов: $failed")
            }
        }
    }

    /** A file opened from another app: add it (if possible) and open it right away. */
    fun openExternal(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val id = library.addExternal(uri)
            _busy.value = false
            if (id == null) _messages.tryEmit("Не удалось открыть файл") else _openRequest.value = id
        }
    }

    fun consumeOpenRequest() {
        _openRequest.value = null
    }

    fun remove(entry: ComicEntry) {
        viewModelScope.launch { library.remove(entry) }
    }

    fun updateProgress(id: String, firstPage: Int, lastPage: Int) = library.updateProgress(id, firstPage, lastPage)

    /** Puts [entry] into the series [name], or out of its series (null). */
    fun setSeries(entry: ComicEntry, name: String?) = library.setSeries(entry.id, name)

    fun setShelfOrder(order: ShelfOrder) {
        _shelfOrder.value = order
        prefs.edit { putString(KEY_SHELF_ORDER, order.name) }
    }

    fun setShelfSize(size: ShelfSize) {
        _shelfSize.value = size
        prefs.edit { putString(KEY_SHELF_SIZE, size.name) }
    }

    /** The paper is off [id]: it goes on its shelf. */
    fun unwrap(id: String) = library.unwrap(listOf(id))

    /** Everything in the box goes on the shelves at once. */
    fun unwrapAll() = library.unwrap(comics.value.filter { it.wrapped }.map { it.id })

    fun setWrapNew(enabled: Boolean) {
        _wrapNew.value = enabled
        library.wrapNew = enabled
    }

    fun setUnboxSound(enabled: Boolean) {
        _unboxSound.value = enabled
        prefs.edit { putBoolean(KEY_UNBOX_SOUND, enabled) }
    }

    fun boxHintSeen() {
        if (_boxHintDone.value) return
        _boxHintDone.value = true
        prefs.edit { putBoolean(KEY_BOX_HINT, true) }
    }

    /** Reading [entry] again from the first page. */
    fun startOver(entry: ComicEntry) = library.updateProgress(entry.id, 0, 0)

    fun setGroupSeries(enabled: Boolean) {
        _groupSeries.value = enabled
        prefs.edit { putBoolean(KEY_GROUP_SERIES, enabled) }
    }

    suspend fun pageAspects(entry: ComicEntry, source: ComicSource, onProgress: (Float) -> Unit): FloatArray =
        library.pageAspects(entry, source, onProgress)

    // region Watched folders

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val report = library.addFolder(uri, _groupSeries.value)
            if (report.failed.isNotEmpty()) {
                _messages.tryEmit("Не получилось прочитать папку")
            } else {
                _messages.tryEmit(if (report.added > 0) "Из папки добавлено: ${comicsCount(report.added)}" else "В папке пока нет комиксов — буду следить за новыми")
            }
        }
    }

    fun removeFolder(folder: WatchedFolder) {
        viewModelScope.launch { library.removeFolder(folder) }
    }

    fun scanNow() {
        viewModelScope.launch { report(library.scanAll(), quiet = false) }
    }

    fun setScanInterval(interval: ScanInterval) = library.setScanInterval(interval)

    private fun report(report: ScanReport, quiet: Boolean) {
        when {
            report.failed.isNotEmpty() -> _messages.tryEmit("Нет доступа к папке «${report.failed.first()}» — добавь её заново")
            report.added > 0 -> _messages.tryEmit("Новые комиксы: ${comicsCount(report.added)}")
            !quiet -> _messages.tryEmit("Новых комиксов нет")
        }
    }

    // endregion

    fun setReadingMode(mode: ReadingMode) {
        _readingMode.value = mode
        prefs.edit { putString(KEY_READING_MODE, mode.name) }
    }

    fun setShelfTheme(index: Int) {
        _shelfTheme.value = index
        prefs.edit { putInt(KEY_SHELF_THEME, index) }
    }

    fun shelfHintSeen() {
        if (_shelfHintDone.value) return
        _shelfHintDone.value = true
        prefs.edit { putBoolean(KEY_SHELF_HINT, true) }
    }

    fun setShelfMode(enabled: Boolean) {
        _shelfMode.value = enabled
        prefs.edit { putBoolean(KEY_SHELF_MODE, enabled) }
    }

    fun setOrientation(lock: OrientationLock) {
        _orientation.value = lock
        prefs.edit { putString(KEY_ORIENTATION, lock.name) }
    }

    fun setTable(table: ReadingTable) {
        _table.value = table
        prefs.edit { putString(KEY_TABLE, table.name) }
    }

    fun setNight(settings: NightSettings) {
        _night.value = settings
        prefs.edit {
            putString(KEY_NIGHT, settings.mode.name)
            putFloat(KEY_NIGHT_WARMTH, settings.warmth)
            putFloat(KEY_NIGHT_BRIGHTNESS, settings.brightness)
        }
    }

    companion object {
        private const val KEY_BACKGROUND = "background"
        private const val KEY_TABLE = "table"
        private const val KEY_NIGHT = "night"
        private const val KEY_NIGHT_WARMTH = "night_warmth"
        private const val KEY_NIGHT_BRIGHTNESS = "night_brightness"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_SHELF_MODE = "shelf_mode"
        private const val KEY_SHELF_THEME = "shelf_theme"
        private const val KEY_SHELF_HINT = "shelf_hint"
        private const val KEY_SHELF_ORDER = "shelf_order"
        private const val KEY_SHELF_SIZE = "shelf_size"
        private const val KEY_GROUP_SERIES = "group_series"
        private const val KEY_UNBOX_SOUND = "unbox_sound"
        private const val KEY_BOX_HINT = "box_hint"
    }
}
