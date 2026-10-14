package com.readcomic.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readcomic.data.ComicEntry
import com.readcomic.curl.PageTurnController.ReadingMode
import com.readcomic.data.ComicOpener
import com.readcomic.data.ComicSource
import com.readcomic.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = LibraryStore(application)
    private val prefs = application.getSharedPreferences("settings", 0)
    private val saveLock = Mutex()

    private val _comics = MutableStateFlow<List<ComicEntry>>(emptyList())
    val comics: StateFlow<List<ComicEntry>> = _comics.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Comic to open in the reader (set when the app is launched with a file). */
    private val _openRequest = MutableStateFlow<String?>(null)
    val openRequest: StateFlow<String?> = _openRequest.asStateFlow()

    private val _backgroundTint = MutableStateFlow(prefs.getInt(KEY_BACKGROUND, 0))
    val backgroundTint: StateFlow<Int> = _backgroundTint.asStateFlow()

    private val _readingMode = MutableStateFlow(
        runCatching { ReadingMode.valueOf(prefs.getString(KEY_READING_MODE, null) ?: "") }.getOrDefault(ReadingMode.AUTO),
    )
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private val _orientation = MutableStateFlow(
        runCatching { OrientationLock.valueOf(prefs.getString(KEY_ORIENTATION, null) ?: "") }.getOrDefault(OrientationLock.SYSTEM),
    )
    val orientation: StateFlow<OrientationLock> = _orientation.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            var list = store.load()
            if (!prefs.getBoolean(KEY_DEMO_ADDED, false)) {
                runCatching { import(ComicOpener.DEMO_URI, "Демо: как пользоваться") }.getOrNull()?.let { list = list + it }
                prefs.edit { putBoolean(KEY_DEMO_ADDED, true) }
                store.save(list)
            }
            _comics.value = list
        }
    }

    fun coverFile(entry: ComicEntry) = store.coverFile(entry.id)

    fun addComics(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            var failed = 0
            for (uri in uris) {
                try {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
                val entry = runCatching {
                    import(uri.toString(), ComicOpener.titleFromName(ComicOpener.displayName(app, uri)))
                }.getOrNull()
                if (entry == null) failed++ else upsert(entry)
            }
            _busy.value = false
            if (failed > 0) {
                _messages.tryEmit(
                    if (uris.size == 1) "Не удалось открыть файл" else "Не удалось открыть файлов: $failed",
                )
            }
        }
    }

    /** A file opened from another app: add it (if possible) and open it right away. */
    fun openExternal(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = LibraryStore.idFor(uri.toString())
            if (_comics.value.none { it.id == id }) {
                _busy.value = true
                try {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
                val entry = runCatching {
                    import(uri.toString(), ComicOpener.titleFromName(ComicOpener.displayName(app, uri)))
                }.getOrNull()
                _busy.value = false
                if (entry == null) {
                    _messages.tryEmit("Не удалось открыть файл")
                    return@launch
                }
                upsert(entry)
            }
            _openRequest.value = id
        }
    }

    fun consumeOpenRequest() {
        _openRequest.value = null
    }

    fun remove(entry: ComicEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            _comics.update { list -> list.filter { it.id != entry.id } }
            persist()
            store.deleteCover(entry.id)
            if (!ComicOpener.isDemo(entry.uri)) {
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(
                        entry.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    fun updateProgress(id: String, firstPage: Int, lastPage: Int) {
        _comics.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(currentPage = firstPage, lastVisiblePage = lastPage, lastReadAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { persist() }
    }

    fun setReadingMode(mode: ReadingMode) {
        _readingMode.value = mode
        prefs.edit { putString(KEY_READING_MODE, mode.name) }
    }

    fun setOrientation(lock: OrientationLock) {
        _orientation.value = lock
        prefs.edit { putString(KEY_ORIENTATION, lock.name) }
    }

    /**
     * Width / height of every page of an opened comic (0 where unknown). Reading them means a
     * pass over the whole archive, so the result is cached per comic.
     */
    suspend fun pageAspects(
        entry: ComicEntry,
        source: ComicSource,
        onProgress: (Float) -> Unit,
    ): FloatArray = withContext(Dispatchers.IO) {
        val count = source.pageCount
        if (count != entry.pageCount) {
            _comics.update { list -> list.map { if (it.id == entry.id) it.copy(pageCount = count) else it } }
            persist()
        }
        val demo = ComicOpener.isDemo(entry.uri)
        if (!demo) store.loadAspects(entry.id, count)?.let { return@withContext it }
        val sizes = runCatching { source.readPageSizes(onProgress) }.getOrDefault(emptyList())
        val aspects = FloatArray(count) { i -> sizes.getOrNull(i)?.let { (w, h) -> w.toFloat() / h } ?: 0f }
        if (!demo && sizes.isNotEmpty()) store.saveAspects(entry.id, aspects)
        aspects
    }

    fun setBackgroundTint(index: Int) {
        _backgroundTint.value = index
        prefs.edit { putInt(KEY_BACKGROUND, index) }
    }

    private fun import(uri: String, title: String): ComicEntry {
        val id = LibraryStore.idFor(uri)
        ComicOpener.open(app, uri).use { source ->
            source.loadPage(0, COVER_WIDTH, COVER_HEIGHT)?.let { cover ->
                store.saveCover(id, cover)
                cover.recycle()
            }
            val existing = _comics.value.firstOrNull { it.id == id }
            return ComicEntry(
                id = id,
                uri = uri,
                title = existing?.title ?: title,
                pageCount = source.pageCount,
                currentPage = (existing?.currentPage ?: 0).coerceIn(0, source.pageCount - 1),
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastReadAt = existing?.lastReadAt ?: 0L,
                lastVisiblePage = (existing?.lastVisiblePage ?: 0).coerceIn(0, source.pageCount - 1),
            )
        }
    }

    private suspend fun upsert(entry: ComicEntry) {
        _comics.update { list ->
            if (list.any { it.id == entry.id }) list.map { if (it.id == entry.id) entry else it } else list + entry
        }
        persist()
    }

    private suspend fun persist() = saveLock.withLock { store.save(_comics.value) }

    companion object {
        private const val KEY_DEMO_ADDED = "demo_added"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_ORIENTATION = "orientation"
        const val COVER_WIDTH = 360
        const val COVER_HEIGHT = 560
    }
}
