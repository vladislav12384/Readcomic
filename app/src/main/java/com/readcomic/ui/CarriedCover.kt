package com.readcomic.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.readcomic.data.ComicEntry
import com.readcomic.data.ComicOpener
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The comic being carried from the shelf to the reader. The shelf brings it to the middle of the
 * screen at the size the reader starts at; until the reader draws it itself, its cover is shown
 * there as a plain picture, decoded on the way (the small saved cover at once, then the first
 * page at the screen's resolution, so nothing turns blurry in between).
 */
class CarriedCover(val id: String) {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    private var released = false

    suspend fun load(context: Context, entry: ComicEntry, coverFile: File) {
        withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(coverFile.path)?.let(LibraryShelfArt::frontHalf)
        }?.let { if (!released) image = it.asImageBitmap() }

        val metrics = context.resources.displayMetrics
        val side = min(max(metrics.widthPixels, metrics.heightPixels), MAX_SIDE)
        withContext(Dispatchers.IO) {
            runCatching {
                ComicOpener.open(context, entry.uri).use { source ->
                    // Twice as wide: a wraparound cover is cut in half.
                    source.loadPage(0, side * 2, side)?.let(LibraryShelfArt::frontHalf)
                }
            }.getOrNull()
        }?.let { if (!released) image = it.asImageBitmap() }
    }

    /** The reader has the comic now: the picture is not needed any more. */
    fun release() {
        released = true
        image = null
    }

    private companion object {
        const val MAX_SIDE = 1600
    }
}
