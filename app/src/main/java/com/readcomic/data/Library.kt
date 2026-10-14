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
    val currentPage: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
) {
    val progress: Float get() = if (pageCount <= 1) 0f else currentPage.toFloat() / (pageCount - 1)
}

/** The list of comics with reading progress, stored as a small JSON file plus cover thumbnails. */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

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
                    .put("lastReadAt", e.lastReadAt),
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
    }

    companion object {
        fun idFor(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }
    }
}
