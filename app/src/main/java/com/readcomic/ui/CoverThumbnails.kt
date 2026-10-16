package com.readcomic.ui

import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * The covers of the plain grid and of the search strip, kept while the app runs: scrolling back
 * over a large collection shows them at once instead of reading each again. Pictures let go are
 * left to the garbage collector, never recycled here, so one still on screen stays whole.
 */
internal object CoverThumbnails {
    private class Kept(val modified: Long, val image: ImageBitmap)

    private val budget = (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(32L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, Kept>(budget) {
        override fun sizeOf(key: String, value: Kept) = value.image.width * value.image.height * 4
    }

    private fun key(file: File, frontOnly: Boolean) = (if (frontOnly) "front:" else "whole:") + file.path

    /** Read before, if it was: for the first frame of a tile, without touching the disk. */
    fun cached(file: File, frontOnly: Boolean): ImageBitmap? = cache.get(key(file, frontOnly))?.image

    /** The cover saved in [file] ([frontOnly]: the front half of a wraparound one); off the main thread. */
    fun load(file: File, frontOnly: Boolean): ImageBitmap? {
        val modified = file.lastModified()
        if (modified == 0L) return null
        val key = key(file, frontOnly)
        cache.get(key)?.let { if (it.modified == modified) return it.image }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        val image = (if (frontOnly) LibraryShelfArt.frontHalf(bitmap) else bitmap).asImageBitmap()
        cache.put(key, Kept(modified, image))
        return image
    }

    /** The system wants memory back: [level] as in [ComponentCallbacks2.onTrimMemory]. */
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> cache.trimToSize(budget / 4)
        }
    }
}
