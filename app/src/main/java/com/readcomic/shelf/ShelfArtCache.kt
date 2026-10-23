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
     * [skipped]: not made at all, it was no longer wanted when its turn came.
     */
    private class Decoded(val key: String, val bitmap: Bitmap?, val generation: Int, val kept: Boolean = false, val skipped: Boolean = false)

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
            decoded += Decoded(key, bitmap, requestGeneration, kept = keep)
            requestRender()
        }
    }

    /**
     * Uploads decoded pictures within a small time budget. Returns true if more are waiting.
     *
     * The big pictures of a comic in hand ([ShelfArtMemory.isSharp]) take a frame of their own
     * to upload, which shows as a jolt in anything moving: they wait for a [calm] frame (the
     * small cover stands in for them meanwhile). Those of a comic that is no longer [sharpWanted]
     * (it went back to its shelf before they were ready) are dropped.
     */
    fun uploadPending(budgetMillis: Long, calm: Boolean = true, sharpWanted: (String) -> Boolean = { true }): Boolean {
        val start = SystemClock.uptimeMillis()
        val later = ArrayList<Decoded>(0)
        var more = false
        while (true) {
            val next = decoded.poll() ?: break
            if (next.generation != generation) {
                if (!next.kept) next.bitmap?.recycle()
                continue
            }
            if (ShelfArtMemory.isSharp(next.key) && next.bitmap != null && !next.skipped) {
                if (!sharpWanted(next.key.substringAfter(':'))) {
                    inFlight -= next.key
                    wanted.remove(next.key)
                    if (!next.kept) next.bitmap.recycle()
                    continue
                }
                if (!calm) {
                    later += next
                    continue
                }
            }
            inFlight -= next.key
            wanted.remove(next.key)
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
            if (SystemClock.uptimeMillis() - start >= budgetMillis) {
                more = decoded.isNotEmpty()
                break
            }
        }
        // Back in line for a calm frame.
        decoded.addAll(later)
        return more || later.isNotEmpty()
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
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    private companion object {
        const val TAG = "ShelfArtCache"
        const val MAX_TEXTURES = 96
        const val MAX_SHARP = 6

        /** A picture not asked for in that long is no longer on screen. */
        const val STALE_MILLIS = 400L
    }
}
