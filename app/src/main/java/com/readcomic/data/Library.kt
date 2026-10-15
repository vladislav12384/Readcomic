package com.readcomic.data

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ComicEntry(
    val id: String,
    val uri: String,
    val title: String,
    val pageCount: Int,
    /** First page on screen when the comic was closed (reading resumes here). */
    val currentPage: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    /** Last page on screen: the right-hand one of a spread. */
    val lastVisiblePage: Int = currentPage,
    /** The watched folder it was found in, null if added by hand. */
    val folder: String? = null,
    /** File name and size, to recognise the same file reached another way. */
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val writer: String? = null,
    val year: Int? = null,
    /** The series it was put into (when adding with series on, or by hand); null stands on its own. */
    val series: String? = null,
    /**
     * Just arrived and still in its paper wrapping, in the box of new comics: it goes on the
     * shelf once the reader has torn the paper off.
     */
    val wrapped: Boolean = false,
) {
    val progress: Float get() = if (pageCount <= 1) 0f else lastVisiblePage.toFloat() / (pageCount - 1)
    val isFinished: Boolean get() = lastReadAt > 0 && lastVisiblePage >= pageCount - 1
}

/** The list of comics with reading progress, stored as a small JSON file plus cover thumbnails. */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val pagesDir = File(context.filesDir, "pages").apply { mkdirs() }

    @Synchronized
    fun load(): List<ComicEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ComicEntry(
                    id = o.getString("id"),
                    uri = o.getString("uri"),
                    title = o.getString("title"),
                    pageCount = o.getInt("pageCount"),
                    currentPage = o.optInt("currentPage", 0),
                    addedAt = o.optLong("addedAt", 0L),
                    lastReadAt = o.optLong("lastReadAt", 0L),
                    lastVisiblePage = o.optInt("lastVisiblePage", o.optInt("currentPage", 0)),
                    folder = o.optString("folder").ifEmpty { null },
                    fileName = o.optString("fileName").ifEmpty { null },
                    fileSize = o.optLong("fileSize", 0L),
                    writer = o.optString("writer").ifEmpty { null },
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    series = o.optString("series").ifEmpty { null },
                    wrapped = o.optBoolean("wrapped", false),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(entries: List<ComicEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("uri", e.uri)
                    .put("title", e.title)
                    .put("pageCount", e.pageCount)
                    .put("currentPage", e.currentPage)
                    .put("addedAt", e.addedAt)
                    .put("lastReadAt", e.lastReadAt)
                    .put("lastVisiblePage", e.lastVisiblePage)
                    .putOpt("folder", e.folder)
                    .putOpt("fileName", e.fileName)
                    .put("fileSize", e.fileSize)
                    .putOpt("writer", e.writer)
                    .putOpt("year", e.year)
                    .putOpt("series", e.series)
                    .put("wrapped", e.wrapped),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    fun coverFile(id: String) = File(coverDir, "$id.jpg")

    fun saveCover(id: String, bitmap: Bitmap) {
        coverFile(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    fun deleteCover(id: String) {
        coverFile(id).delete()
        File(pagesDir, "$id.txt").delete()
    }

    /** Cached width / height of every page, so wide two-page images are known before reading. */
    fun loadAspects(id: String, pageCount: Int): FloatArray? {
        val file = File(pagesDir, "$id.txt")
        if (!file.exists()) return null
        return try {
            file.readText().split(',').map { it.toFloat() }.toFloatArray().takeIf { it.size == pageCount }
        } catch (_: Exception) {
            null
        }
    }

    fun saveAspects(id: String, aspects: FloatArray) {
        File(pagesDir, "$id.txt").writeText(aspects.joinToString(","))
    }

    companion object {
        fun idFor(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }
    }
}
