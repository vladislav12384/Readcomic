package com.readcomic.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A folder the reader picked; new comics in it are added to the library by themselves. */
data class WatchedFolder(val uri: String, val name: String, val lastScan: Long = 0L)

/** How often watched folders are looked through in the background. */
enum class ScanInterval(val hours: Long, val label: String) {
    ON_START(0, "При запуске"),
    HOURLY(1, "Каждый час"),
    SIX_HOURS(6, "Раз в 6 часов"),
    DAILY(24, "Раз в день"),
}

/** A scan in progress: which folder, comics found there so far, of them new. */
data class ScanProgress(val folder: String, val found: Int, val added: Int)

data class ScanReport(val added: Int, val removed: Int, val failed: List<String>)

/**
 * The library of the whole app: the comics with their progress, and the watched folders.
 * One per process, shared by the screens and the background folder scan.
 */
class LibraryRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = LibraryStore(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    private val saveLock = Mutex()
    private val scanLock = Mutex()
    private val ready = CompletableDeferred<Unit>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _comics = MutableStateFlow<List<ComicEntry>>(emptyList())
    val comics: StateFlow<List<ComicEntry>> = _comics.asStateFlow()

    private val _folders = MutableStateFlow(loadFolders())
    val folders: StateFlow<List<WatchedFolder>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow<ScanProgress?>(null)
    val scanning: StateFlow<ScanProgress?> = _scanning.asStateFlow()

    private val _scanInterval = MutableStateFlow(
        runCatching { ScanInterval.valueOf(prefs.getString(KEY_SCAN_INTERVAL, null) ?: "") }.getOrDefault(ScanInterval.SIX_HOURS),
    )
    val scanInterval: StateFlow<ScanInterval> = _scanInterval.asStateFlow()

    init {
        scope.launch {
            var list = store.load()
            if (!prefs.getBoolean(KEY_DEMO_ADDED, false)) {
                runCatching { import(ComicOpener.DEMO_URI, "Демо: как пользоваться") }.getOrNull()?.let { list = list + it }
                prefs.edit { putBoolean(KEY_DEMO_ADDED, true) }
                store.save(list)
            }
            _comics.value = list
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    fun coverFile(id: String) = store.coverFile(id)

    // region Comics

    /** Adds comic files the reader picked. Returns how many could not be opened. */
    suspend fun addFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        awaitReady()
        var failed = 0
        for (uri in uris) {
            keepAccess(uri)
            val entry = runCatching { importFile(uri) }.onFailure { Log.w(TAG, "Cannot add $uri", it) }.getOrNull()
            if (entry == null) failed++ else upsert(entry)
        }
        failed
    }

    /** A file opened from another app: adds it if needed. Returns its id, null if it cannot be read. */
    suspend fun addExternal(uri: Uri): String? = withContext(Dispatchers.IO) {
        awaitReady()
        val id = LibraryStore.idFor(uri.toString())
        if (_comics.value.any { it.id == id }) return@withContext id
        keepAccess(uri)
        val entry = runCatching { importFile(uri) }.getOrNull() ?: return@withContext null
        upsert(entry)
        id
    }

    suspend fun remove(entry: ComicEntry) = withContext(Dispatchers.IO) {
        forget(listOf(entry))
        // Comics from watched folders are reached through the folder's permission, not their own.
        if (entry.folder == null && !ComicOpener.isDemo(entry.uri)) {
            runCatching { app.contentResolver.releasePersistableUriPermission(entry.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun updateProgress(id: String, firstPage: Int, lastPage: Int) {
        _comics.update { list ->
            list.map {
                if (it.id == id) it.copy(currentPage = firstPage, lastVisiblePage = lastPage, lastReadAt = System.currentTimeMillis()) else it
            }
        }
        scope.launch { persist() }
    }

    /**
     * Width / height of every page of an opened comic (0 where unknown). Reading them means a
     * pass over the whole archive, so the result is cached per comic.
     */
    suspend fun pageAspects(entry: ComicEntry, source: ComicSource, onProgress: (Float) -> Unit): FloatArray =
        withContext(Dispatchers.IO) {
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

    private fun keepAccess(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun importFile(uri: Uri): ComicEntry {
        val name = ComicOpener.displayName(app, uri)
        return import(uri.toString(), ComicOpener.titleFromName(name), fileName = name, fileSize = ComicOpener.fileSize(app, uri))
    }

    /** Opens a comic once: its cover, page count and what it says about itself. */
    private fun import(uri: String, title: String, folder: String? = null, fileName: String? = null, fileSize: Long = 0L): ComicEntry {
        val id = LibraryStore.idFor(uri)
        ComicOpener.open(app, uri).use { source ->
            source.loadPage(0, COVER_WIDTH, COVER_HEIGHT)?.let { cover ->
                store.saveCover(id, cover)
                cover.recycle()
            }
            val metadata = runCatching { source.metadata }.getOrNull()
            val existing = _comics.value.firstOrNull { it.id == id }
            return ComicEntry(
                id = id,
                uri = uri,
                title = existing?.title ?: metadata?.displayTitle ?: title,
                pageCount = source.pageCount,
                currentPage = (existing?.currentPage ?: 0).coerceIn(0, source.pageCount - 1),
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastReadAt = existing?.lastReadAt ?: 0L,
                lastVisiblePage = (existing?.lastVisiblePage ?: 0).coerceIn(0, source.pageCount - 1),
                folder = folder ?: existing?.folder,
                fileName = fileName ?: existing?.fileName,
                fileSize = if (fileSize > 0) fileSize else existing?.fileSize ?: 0L,
                writer = metadata?.writer ?: existing?.writer,
                year = metadata?.year ?: existing?.year,
            )
        }
    }

    private suspend fun upsert(entry: ComicEntry) {
        _comics.update { list ->
            if (list.any { it.id == entry.id }) list.map { if (it.id == entry.id) entry else it } else list + entry
        }
        persist()
    }

    private suspend fun forget(entries: List<ComicEntry>) {
        if (entries.isEmpty()) return
        val ids = entries.map { it.id }.toSet()
        _comics.update { list -> list.filter { it.id !in ids } }
        persist()
        ids.forEach(store::deleteCover)
    }

    private suspend fun persist() = saveLock.withLock { store.save(_comics.value) }

    // endregion

    // region Watched folders

    /** Starts watching a folder picked with the system folder picker and looks through it. */
    suspend fun addFolder(uri: Uri): ScanReport = withContext(Dispatchers.IO) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "No lasting access to $uri", e)
        }
        val name = runCatching { ComicOpener.displayName(app, documentOf(uri)) }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast(':')
        val folder = WatchedFolder(uri.toString(), name?.ifEmpty { null } ?: "Папка")
        if (_folders.value.none { it.uri == folder.uri }) {
            _folders.update { it + folder }
            saveFolders()
        }
        scheduleScans()
        scan(listOf(folder))
    }

    /** Stops watching [folder]; its comics leave the library too (they can no longer be opened). */
    suspend fun removeFolder(folder: WatchedFolder) = withContext(Dispatchers.IO) {
        scanLock.withLock {
            _folders.update { list -> list.filter { it.uri != folder.uri } }
            saveFolders()
            forget(_comics.value.filter { it.folder == folder.uri })
            runCatching { app.contentResolver.releasePersistableUriPermission(folder.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        scheduleScans()
    }

    suspend fun scanAll(): ScanReport = scan(_folders.value)

    fun setScanInterval(interval: ScanInterval) {
        _scanInterval.value = interval
        prefs.edit { putString(KEY_SCAN_INTERVAL, interval.name) }
        scheduleScans()
    }

    /** Hands the background schedule to the system (none without folders or when scanning only at start). */
    fun scheduleScans() {
        FolderScanWorker.schedule(app, if (_folders.value.isEmpty()) null else _scanInterval.value)
    }

    private suspend fun scan(folders: List<WatchedFolder>): ScanReport = withContext(Dispatchers.IO) {
        awaitReady()
        scanLock.withLock {
            var added = 0
            var removed = 0
            val failed = ArrayList<String>()
            for (folder in folders) {
                if (_folders.value.none { it.uri == folder.uri }) continue
                try {
                    val (a, r) = scanFolder(folder)
                    added += a
                    removed += r
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot look through ${folder.uri}", e)
                    failed += folder.name
                }
            }
            _scanning.value = null
            ScanReport(added, removed, failed)
        }
    }

    private class Candidate(val uri: Uri, val name: String, val size: Long, val isFolder: Boolean)

    /** Adds what is new in [folder] and drops what disappeared from it. */
    private suspend fun scanFolder(folder: WatchedFolder): Pair<Int, Int> {
        _scanning.value = ScanProgress(folder.name, 0, 0)
        val found = ArrayList<Candidate>()
        walk(folder.uri.toUri(), folder.name, 0, found)
        val byId = found.associateBy { LibraryStore.idFor(it.uri.toString()) }
        val current = _comics.value
        val known = current.map { it.id }.toSet()
        // The same file added by hand earlier has another address; it is recognised by name and size.
        val sameFiles = current.filter { it.fileName != null && it.fileSize > 0 }.map { it.fileName to it.fileSize }.toSet()
        var added = 0
        for ((id, candidate) in byId) {
            if (id in known) continue
            if (!candidate.isFolder && (candidate.name to candidate.size) in sameFiles) continue
            val title = if (candidate.isFolder) candidate.name.replace('_', ' ').trim() else ComicOpener.titleFromName(candidate.name)
            val entry = runCatching {
                import(candidate.uri.toString(), title, folder.uri, candidate.name, candidate.size)
            }.onFailure { Log.w(TAG, "Cannot add ${candidate.name}", it) }.getOrNull() ?: continue
            upsert(entry)
            added++
            _scanning.value = ScanProgress(folder.name, byId.size, added)
        }
        val vanished = current.filter { it.folder == folder.uri && it.id !in byId }
        forget(vanished)
        _folders.update { list -> list.map { if (it.uri == folder.uri) it.copy(lastScan = System.currentTimeMillis()) else it } }
        saveFolders()
        return added to vanished.size
    }

    /**
     * Comic files anywhere inside [dir]; a folder holding only pictures is one comic itself
     * (scans often come as folders of page images).
     */
    private fun walk(dir: Uri, name: String, depth: Int, out: MutableList<Candidate>) {
        val children = Documents.children(app, dir)
        val files = children.filter { !it.isFolder }
        val comics = files.filter { isComicFile(it.name) }
        val pictures = files.count { PageDecoder.isImageName(it.name) }
        if (comics.isEmpty() && pictures >= MIN_FOLDER_PAGES) {
            out += Candidate(dir, name, 0L, isFolder = true)
            return
        }
        comics.forEach { out += Candidate(it.uri, it.name, it.size, isFolder = false) }
        if (depth >= MAX_DEPTH) return
        for (child in children) {
            if (child.isFolder && !child.name.startsWith(".")) walk(child.uri, child.name, depth + 1, out)
        }
    }

    private fun documentOf(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun loadFolders(): List<WatchedFolder> = runCatching {
        val array = JSONArray(prefs.getString(KEY_FOLDERS, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            WatchedFolder(o.getString("uri"), o.optString("name", "Папка"), o.optLong("lastScan", 0L))
        }
    }.getOrDefault(emptyList())

    private fun saveFolders() {
        val array = JSONArray()
        _folders.value.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name).put("lastScan", it.lastScan)) }
        prefs.edit { putString(KEY_FOLDERS, array.toString()) }
    }

    // endregion

    companion object {
        private const val TAG = "Library"
        private const val KEY_DEMO_ADDED = "demo_added"
        private const val KEY_FOLDERS = "watched_folders"
        private const val KEY_SCAN_INTERVAL = "scan_interval"
        private const val MAX_DEPTH = 8
        private const val MIN_FOLDER_PAGES = 2
        const val COVER_WIDTH = 360
        const val COVER_HEIGHT = 560

        /** Files that may be comics, judged by name when looking through folders. */
        val COMIC_EXTENSIONS = setOf("cbz", "cbr", "cb7", "cbt", "zip", "rar", "7z", "tar", "pdf", "epub")

        fun isComicFile(name: String) = !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in COMIC_EXTENSIONS

        // Holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) { instance ?: LibraryRepository(context).also { instance = it } }
    }
}
