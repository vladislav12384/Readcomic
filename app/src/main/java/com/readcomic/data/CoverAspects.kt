package com.readcomic.data

import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The shapes (width / height) of the saved cover thumbnails, so that the shelves can be laid
 * out without opening every cover: known in memory while the app runs, and kept in a small file
 * between runs, so that a large library is on its shelves at once after a restart too.
 */
object CoverAspects {
    private class Known(val modified: Long, val aspect: Float)

    private val known = ConcurrentHashMap<String, Known>()

    @Volatile
    private var store: File? = null

    @Volatile
    private var changed = false

    /** Reads the shapes kept last time from [file]; off the main thread, before the shelves need them. */
    fun load(file: File) {
        store = file
        runCatching {
            if (!file.exists()) return
            file.forEachLine { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@forEachLine
                val modified = parts[0].toLongOrNull() ?: return@forEachLine
                val aspect = parts[1].toFloatOrNull()?.takeIf { it > 0f } ?: return@forEachLine
                known.putIfAbsent(parts[2], Known(modified, aspect))
            }
        }
    }

    /** Width / height of the front cover saved in [file] (half of a wraparound one), null if there is none. */
    fun of(file: File): Float? {
        val modified = file.lastModified()
        if (modified == 0L) return null
        known[file.path]?.let { if (it.modified == modified) return it.aspect }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var width = bounds.outWidth.toFloat()
        if (width > bounds.outHeight * 1.1f) width /= 2f
        val aspect = width / bounds.outHeight
        known[file.path] = Known(modified, aspect)
        changed = true
        return aspect
    }

    /** The shape of [file] if it is known already (no file is read), else null. */
    fun cached(file: File): Float? = known[file.path]?.aspect

    /** Keeps what is known for [files] in the file it was loaded from, if anything changed; off the main thread. */
    @Synchronized
    fun save(files: Collection<File>) {
        val target = store ?: return
        if (!changed) return
        changed = false
        runCatching {
            val text = buildString {
                for (file in files) {
                    val entry = known[file.path] ?: continue
                    append(entry.modified).append('\t').append(entry.aspect).append('\t').append(file.path).append('\n')
                }
            }
            val tmp = File(target.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }
}
