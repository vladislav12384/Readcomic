package com.readcomic.shelf

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Pictures for the shelf, decoded in the background (called off the render thread). */
interface ShelfArt {
    /** The small cover saved in the library. */
    fun cover(id: String): Bitmap?

    /** The paper label saying [text] ("Saga", "№ 12"), shown on the shelf under a comic. */
    fun label(text: String): Bitmap?

    /** A sharp front ([back] = false) or back cover for the comic in hand; may be slow. */
    fun page(id: String, back: Boolean): Bitmap?

    /**
     * A back cover drawn for the comic in hand, for comics whose own back is missing or is not a
     * cover (the reader turns it on for each such comic); may be slow.
     */
    fun drawnBack(id: String): Bitmap? = null

    /** What is printed on the spine: its colour and, on thicker comics, the title. */
    fun spine(id: String): Bitmap?

    /** The engraving of the name plate (white letters on black) for a collection of [count] comics. */
    fun plaque(count: Int): Bitmap?

    /** The engraving of the sign of the series [name], [aspect] times as wide as high. */
    fun sign(name: String, aspect: Float): Bitmap?

    /**
     * The print on the front of the box of new comics, with its label saying how many are in it
     * ([count]); transparent where the cardboard shows.
     */
    fun box(count: Int): Bitmap? = null
}

/**
 * Pictures of the shelf kept while the app runs, so that coming back from reading the covers,
 * spines and labels are there at once instead of being decoded all over again. Not the sharp
 * covers of the comic in hand: they are big, and only one is needed at a time.
 *
 * It gives memory back as soon as the shelves are not on screen: leaving them (for reading, or
 * with the app put aside) only what was seen last is kept, enough for a quick way back; with the
 * app in the background, nothing. Pictures let go are left to the garbage collector, never
 * recycled here, so one on its way to the screen can never be pulled from under it.
 */
internal object ShelfArtMemory {
    private val budget = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, Bitmap>(budget) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun keeps(key: String) = !isSharp(key)

    /** The big pictures of the comic in hand: only a few at a time, never kept for later. */
    fun isSharp(key: String) = key.startsWith("front:") || key.startsWith("back:") || key.startsWith("drawnback:")

    operator fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        if (keeps(key)) cache.put(key, bitmap)
    }

    /** The shelves are left: keep only the pictures seen last (the ones that were on screen). */
    fun keepWarm() = cache.trimToSize(minOf(WARM_BYTES, budget / 3))

    /** The system wants memory back: [level] as in [android.content.ComponentCallbacks2.onTrimMemory]. */
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> keepWarm()
        }
    }

    private const val WARM_BYTES = 24 * 1024 * 1024
}

/** GL textures for the shelf, keyed like "cover:<id>". Everything but decoding runs on the render thread. */
class ShelfArtCache(
    private val scope: CoroutineScope,
    private val requestRender: () -> Unit,
) {
    /**
     * A picture ready to upload; [kept] ones stay in [ShelfArtMemory] and must not be recycled.
     * [skipped]: not made at all, it was no longer wanted when its turn came. A big one (of the
     * comic in hand) comes as [pixels] instead of a bitmap, to go up a few rows at a time.
     */
    private class Decoded(
        val key: String,
        val bitmap: Bitmap?,
        val generation: Int,
        val kept: Boolean = false,
        val skipped: Boolean = false,
        val pixels: Pixels? = null,
    )

    /** The bytes of a picture, RGBA as GL takes them, row after row from the top, [rowBytes] apart. */
    private class Pixels(val width: Int, val height: Int, val rowBytes: Int, val bytes: ByteBuffer)

    /** A big picture on its way up: rows from the top down to [row] are in its texture already. */
    private class Partial(val key: String, val texture: Int, val pixels: Pixels, var row: Int = 0)

    /** At most one big picture goes up at a time, over a few frames, so that no frame takes long. */
    private var partial: Partial? = null

    private val textures = LinkedHashMap<String, Int>(64, 0.75f, true)
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()

    /** When each picture waiting to be made was last asked for (uptime, ms): asked for every frame it is on screen. */
    private val wanted = ConcurrentHashMap<String, Long>()
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)

    @Volatile
    private var generation = 0

    /** Where pictures come from; a new source gets another chance at pictures that failed. */
    var art: ShelfArt? = null
        set(value) {
            field = value
            failed.clear()
        }

    fun onContextCreated() {
        // Its texture went with the old context.
        partial = null
        textures.clear()
        inFlight.clear()
        failed.clear()
        wanted.clear()
        while (true) {
            val next = decoded.poll() ?: break
            if (!next.kept) next.bitmap?.recycle()
        }
        generation++
    }

    operator fun get(key: String): Int? = textures[key]

    fun request(key: String) {
        if (key in textures || key in failed) return
        wanted[key] = SystemClock.uptimeMillis()
        if (key in inFlight) return
        val art = art ?: return
        inFlight += key
        val requestGeneration = generation
        // Seen before: no need to decode it again.
        ShelfArtMemory[key]?.let { kept ->
            decoded += Decoded(key, kept, requestGeneration, kept = true)
            requestRender()
            return
        }
        scope.launch(dispatcher) {
            if (requestGeneration != generation) return@launch
            // Its turn came after the shelves had flown on past it (a fling, a jump to a search
            // result): not made now, so the pictures on screen come first; asked for again if it
            // is seen again.
            if (SystemClock.uptimeMillis() - (wanted[key] ?: 0L) > STALE_MILLIS) {
                decoded += Decoded(key, null, requestGeneration, skipped = true)
                requestRender()
                return@launch
            }
            val (kind, id) = key.split(':', limit = 2)
            val bitmap = try {
                when (kind) {
                    "cover" -> art.cover(id)
                    "label" -> art.label(id)
                    "front" -> art.page(id, back = false)
                    "back" -> art.page(id, back = true)
                    "drawnback" -> art.drawnBack(id)
                    "spine" -> art.spine(id)
                    "sign" -> art.sign(id.substringAfter(':'), id.substringBefore(':').toFloat())
                    "box" -> art.box(id.toInt())
                    else -> art.plaque(id.toInt())
                }
            } catch (e: Exception) {
                Log.w(TAG, "No picture for $key", e)
                null
            }
            val keep = bitmap != null && ShelfArtMemory.keeps(key)
            if (keep) ShelfArtMemory.put(key, bitmap!!)
            // A big one is made ready to go up in parts: uploaded at once it would take a frame of its
            // own. (If that fails it counts as no picture: its bitmap is gone either way.)
            val sharp = bitmap != null && ShelfArtMemory.isSharp(key)
            val pixels = if (sharp) pixelsOf(bitmap!!) else null
            decoded += Decoded(key, if (sharp) null else bitmap, requestGeneration, kept = keep, pixels = pixels)
            requestRender()
        }
    }

    /**
     * Uploads decoded pictures within a small time budget. Returns true if more are waiting.
     *
     * A big picture of the comic in hand ([ShelfArtMemory.isSharp]) goes up a band of rows at a
     * time, over as many frames as it takes, and is used once all of it is there (the small cover
     * stands in for it meanwhile): uploaded at once it took a frame of its own, a jolt in the
     * comic flying to the hand. Those of a comic that is no longer [sharpWanted] (it went back to
     * its shelf before they were ready) are dropped.
     */
    fun uploadPending(budgetMillis: Long, sharpWanted: (String) -> Boolean = { true }): Boolean {
        val start = SystemClock.uptimeMillis()
        fun spent() = SystemClock.uptimeMillis() - start >= budgetMillis
        partial?.let { going ->
            if (!sharpWanted(going.key.substringAfter(':'))) {
                GLES30.glDeleteTextures(1, intArrayOf(going.texture), 0)
                forget(going.key)
                partial = null
            } else if (!uploadRows(going, ::spent)) {
                return true
            }
        }
        val later = ArrayList<Decoded>(0)
        var more = false
        while (true) {
            if (spent()) {
                more = decoded.isNotEmpty()
                break
            }
            val next = decoded.poll() ?: break
            if (next.generation != generation) {
                if (!next.kept) next.bitmap?.recycle()
                continue
            }
            val pixels = next.pixels
            if (pixels != null) {
                if (!sharpWanted(next.key.substringAfter(':'))) {
                    forget(next.key)
                    continue
                }
                // One at a time; the others wait their turn.
                if (partial != null) {
                    later += next
                    continue
                }
                val going = Partial(next.key, allocate(pixels.width, pixels.height), pixels)
                partial = going
                if (!uploadRows(going, ::spent)) {
                    more = true
                    break
                }
                continue
            }
            forget(next.key)
            if (next.skipped) continue
            val bitmap = next.bitmap
            if (bitmap == null) {
                failed += next.key
                continue
            }
            textures.remove(next.key)?.let { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
            textures[next.key] = upload(bitmap)
            if (!next.kept) bitmap.recycle()
            trim()
        }
        decoded.addAll(later)
        return more || later.isNotEmpty() || partial != null
    }

    /** Not in flight any more: done with, or dropped (then asked for again if wanted again). */
    private fun forget(key: String) {
        inFlight -= key
        wanted.remove(key)
    }

    /**
     * Uploads bands of [going]'s rows until all of it is up or the time is [spent]. Once all is up
     * its texture is finished (mipmaps) and put in use; returns whether it is.
     */
    private fun uploadRows(going: Partial, spent: () -> Boolean): Boolean {
        val pixels = going.pixels
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, going.texture)
        // Rows kept with some room after them (rarely) are read as such.
        val padded = pixels.rowBytes != pixels.width * 4
        if (padded) GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, pixels.rowBytes / 4)
        try {
            while (going.row < pixels.height) {
                val rows = minOf(BAND_ROWS, pixels.height - going.row)
                pixels.bytes.position(going.row * pixels.rowBytes)
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, going.row, pixels.width, rows,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels.bytes,
                )
                going.row += rows
                if (going.row < pixels.height && spent()) return false
            }
        } finally {
            if (padded) GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        textures.remove(going.key)?.let { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
        textures[going.key] = going.texture
        forget(going.key)
        partial = null
        trim()
        return true
    }

    /** A texture of that size with nothing in it yet, set up as [upload] sets them up. */
    private fun allocate(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        setUp()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return ids[0]
    }

    /** Keeps the most recently used textures; sharp covers are big, so only a few of them. */
    private fun trim() {
        val sharp = textures.keys.filter(ShelfArtMemory::isSharp)
        sharp.dropLast(MAX_SHARP).forEach { key -> textures.remove(key)?.let { GLES30.glDeleteTextures(1, intArrayOf(it), 0) } }
        while (textures.size > MAX_TEXTURES) {
            val key = textures.keys.first()
            textures.remove(key)?.let { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
        }
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        setUp()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    /** Filtering and edges of the texture bound now. */
    private fun setUp() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** The bytes of [bitmap] (recycled), as GL takes them; off the render thread. Null if they cannot be had. */
    private fun pixelsOf(bitmap: Bitmap): Pixels? {
        var argb: Bitmap? = null
        try {
            argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (argb == null) return null
            val bytes = ByteBuffer.allocateDirect(argb.rowBytes * argb.height).order(ByteOrder.nativeOrder())
            argb.copyPixelsToBuffer(bytes)
            bytes.rewind()
            return Pixels(argb.width, argb.height, argb.rowBytes, bytes)
        } catch (e: Exception) {
            Log.w(TAG, "No bytes of a picture", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No room for the bytes of a picture", e)
            return null
        } finally {
            if (argb != null && argb !== bitmap) argb.recycle()
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "ShelfArtCache"
        const val MAX_TEXTURES = 96
        const val MAX_SHARP = 6

        /** A picture not asked for in that long is no longer on screen. */
        const val STALE_MILLIS = 400L

        /** Rows of a big picture uploaded at a time (about half a megabyte of a sharp cover). */
        const val BAND_ROWS = 128
    }
}
