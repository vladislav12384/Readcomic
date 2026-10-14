package com.readcomic.shelf

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/** Pictures for the shelf, decoded in the background (called off the render thread). */
interface ShelfArt {
    /** The small cover saved in the library. */
    fun cover(id: String): Bitmap?

    /** The paper label with the title, shown on the shelf under the comic. */
    fun label(id: String): Bitmap?

    /** A sharp front ([back] = false) or back cover for the comic in hand; may be slow. */
    fun page(id: String, back: Boolean): Bitmap?
}

/** GL textures for the shelf, keyed like "cover:<id>". Everything but decoding runs on the render thread. */
class ShelfArtCache(
    private val scope: CoroutineScope,
    private val requestRender: () -> Unit,
) {
    private class Decoded(val key: String, val bitmap: Bitmap?, val generation: Int)

    private val textures = LinkedHashMap<String, Int>(64, 0.75f, true)
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()
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
        while (true) decoded.poll()?.bitmap?.recycle() ?: break
        generation++
    }

    operator fun get(key: String): Int? = textures[key]

    fun request(key: String) {
        if (key in textures || key in inFlight || key in failed) return
        val art = art ?: return
        inFlight += key
        val requestGeneration = generation
        scope.launch(dispatcher) {
            if (requestGeneration != generation) return@launch
            val (kind, id) = key.split(':', limit = 2)
            val bitmap = try {
                when (kind) {
                    "cover" -> art.cover(id)
                    "label" -> art.label(id)
                    "front" -> art.page(id, back = false)
                    else -> art.page(id, back = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No picture for $key", e)
                null
            }
            decoded += Decoded(key, bitmap, requestGeneration)
            requestRender()
        }
    }

    /** Uploads decoded pictures within a small time budget. Returns true if more are waiting. */
    fun uploadPending(budgetMillis: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val next = decoded.poll() ?: return false
            if (next.generation != generation) {
                next.bitmap?.recycle()
                continue
            }
            inFlight -= next.key
            val bitmap = next.bitmap
            if (bitmap == null) {
                failed += next.key
                continue
            }
            textures.remove(next.key)?.let { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
            textures[next.key] = upload(bitmap)
            bitmap.recycle()
            trim()
            if (SystemClock.uptimeMillis() - start >= budgetMillis) return decoded.isNotEmpty()
        }
    }

    /** Keeps the most recently used textures; sharp covers are big, so only a few of them. */
    private fun trim() {
        val sharp = textures.keys.filter { it.startsWith("front:") || it.startsWith("back:") }
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
    }
}
