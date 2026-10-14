package com.readcomic.curl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/** Blocking page decoder, called from a background thread. */
fun interface PageLoader {
    fun load(index: Int, maxWidth: Int, maxHeight: Int): Bitmap?
}

/**
 * GL textures of the pages around the current one, in up to three qualities:
 *
 * - a small **preview** of many pages around, cheap to decode, so a page is never blank;
 * - a **sharp** version of the pages on screen and of the ones the next turns will reveal;
 * - a **zoom** version of the pages on screen while the reader zooms in.
 *
 * Bitmaps are decoded in the background; uploading them to the GPU happens on the render thread
 * with a small time budget per frame so that animations never stutter. A big page goes up in
 * bands of rows over several frames (a whole zoomed page at once would hold a frame for tens of
 * milliseconds, right while the reader pinches or turns), and is shown once it is complete.
 */
class PageTextureCache(
    private val scope: CoroutineScope,
    private val requestRender: () -> Unit,
    private val onLoaded: (index: Int, aspect: Float) -> Unit,
) {
    class Texture(val id: Int, val width: Int, val height: Int, val quality: Int)

    /** A decoded page: a small bitmap for previews, raw RGBA rows for the big ones. */
    private class Decoded(
        val index: Int,
        val quality: Int,
        val bitmap: Bitmap?,
        val pixels: ByteBuffer?,
        val width: Int,
        val height: Int,
        val generation: Int,
    )

    /** A big texture going up band by band. */
    private class Upload(val index: Int, val quality: Int, val id: Int, val width: Int, val height: Int, val pixels: ByteBuffer) {
        var row = 0
    }

    private var uploading: Upload? = null

    /** Reused pixel buffers: big pages come one after another, no need to allocate each time. */
    private val pool = ArrayList<ByteBuffer>()

    private val textures = HashMap<Int, Texture>()
    private val inFlight = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()

    @Volatile
    private var generation = 0
    private var maxTextureSize = 2048
    private var anisotropy = 1f

    // Previews are tiny and quick: they get their own lane so they never wait behind big pages.
    private val previewDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val sharpDispatcher = Dispatchers.IO.limitedParallelism(2)

    var loader: PageLoader? = null
        set(value) {
            field = value
            clear()
        }

    /** Call when a new GL context has been created: all old texture names are gone. */
    fun onContextCreated() {
        textures.clear()
        inFlight.clear()
        failed.clear()
        uploading?.let { release(it.pixels) }
        uploading = null
        drainDecoded()
        generation++
        val size = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
        maxTextureSize = max(size[0], 2048)
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        anisotropy = if (extensions.contains("GL_EXT_texture_filter_anisotropic")) {
            val value = FloatArray(1)
            GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, value, 0)
            min(value[0], 8f)
        } else {
            1f
        }
    }

    fun clear() {
        textures.values.forEach { GLES30.glDeleteTextures(1, intArrayOf(it.id), 0) }
        textures.clear()
        inFlight.clear()
        failed.clear()
        abortUpload()
        drainDecoded()
        generation++
    }

    operator fun get(index: Int): Texture? = textures[index]

    /**
     * Decides what to keep and what to load.
     *
     * @param sharp images that should be crisp, most important first
     * @param previews images that should at least have a preview, most important first
     * @param zoomed images on screen while zoomed in, worth an extra sharp version
     * @param box on-screen size of an image
     */
    fun update(sharp: List<Int>, previews: List<Int>, zoomed: Set<Int>, box: (Int) -> Pair<Float, Float>) {
        val iterator = textures.entries.iterator()
        while (iterator.hasNext()) {
            val (index, texture) = iterator.next()
            val wanted = when {
                index in zoomed -> ZOOM
                index in sharp -> SHARP
                index in previews -> PREVIEW
                else -> -1
            }
            // Drop pages far away, and big textures of pages that only need a preview now. A page on
            // screen keeps whatever it has (no flash back to a preview after zooming out).
            if (wanted < 0 || (wanted == PREVIEW && texture.quality > PREVIEW)) {
                GLES30.glDeleteTextures(1, intArrayOf(texture.id), 0)
                iterator.remove()
            }
        }

        // A big texture still going up for a page that no longer needs it is not finished.
        uploading?.let { u ->
            val wanted = when (u.index) {
                in zoomed -> ZOOM
                in sharp -> SHARP
                else -> PREVIEW
            }
            if (wanted < u.quality) abortUpload()
        }

        // First a placeholder for whatever is needed right now, then the crisp versions in order.
        for (index in sharp.take(4)) request(index, PREVIEW, box)
        for (index in sharp) request(index, SHARP, box)
        for (index in zoomed) request(index, ZOOM, box)
        for (index in previews) request(index, PREVIEW, box)
    }

    private fun request(index: Int, quality: Int, box: (Int) -> Pair<Float, Float>) {
        val loader = loader ?: return
        val existing = textures[index]?.quality ?: -1
        if (existing >= quality) return
        val key = key(index, quality)
        if (key in inFlight || key in failed) return
        // A preview is pointless once a better version is on its way.
        if (quality == PREVIEW && (key(index, SHARP) in inFlight || key(index, ZOOM) in inFlight)) return

        val (boxWidth, boxHeight) = box(index)
        val limit = min(maxTextureSize, 4096).toFloat()
        val factor = when (quality) {
            ZOOM -> 2.2f
            SHARP -> 1.35f
            else -> min(1f, PREVIEW_SIZE / max(boxWidth, boxHeight))
        }
        val width = min(boxWidth * factor, limit).toInt().coerceAtLeast(1)
        val height = min(boxHeight * factor, limit).toInt().coerceAtLeast(1)

        inFlight += key
        val requestGeneration = generation
        scope.launch(if (quality == PREVIEW) previewDispatcher else sharpDispatcher) {
            if (requestGeneration != generation) return@launch
            // Decoding yields to drawing: a busy decoder must not cost the animation a frame.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val bitmap = try {
                loader.load(index, width, height)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load page $index", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Out of memory loading page $index", e)
                null
            } finally {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
            if (bitmap == null || quality == PREVIEW) {
                decoded += Decoded(index, quality, bitmap, null, bitmap?.width ?: 0, bitmap?.height ?: 0, requestGeneration)
            } else {
                // Raw rows can be uploaded a band at a time; a bitmap only as a whole.
                val pixels = obtain(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(pixels)
                pixels.rewind()
                decoded += Decoded(index, quality, null, pixels, bitmap.width, bitmap.height, requestGeneration)
                bitmap.recycle()
            }
            requestRender()
        }
    }

    /**
     * Uploads decoded pages to the GPU, previews first, spending about [budgetMillis] (but always
     * making some progress). Returns true if more are waiting.
     */
    fun uploadPending(budgetMillis: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (true) {
            uploading?.let { u ->
                if (!continueUpload(u, start, budgetMillis)) return true
            }
            // Small previews jump the queue: they are what makes a page appear instantly.
            val next = decoded.firstOrNull { it.quality == PREVIEW } ?: decoded.peek() ?: return false
            decoded.remove(next)
            val key = key(next.index, next.quality)
            if (next.generation != generation) {
                discard(next)
                continue
            }
            inFlight -= key
            if (next.bitmap == null && next.pixels == null) {
                failed += key
                continue
            }
            val current = textures[next.index]
            if (current != null && current.quality >= next.quality) {
                discard(next)
                continue
            }
            val bitmap = next.bitmap
            if (bitmap != null) {
                current?.let { GLES30.glDeleteTextures(1, intArrayOf(it.id), 0) }
                textures[next.index] = uploadWhole(bitmap, next.quality)
                onLoaded(next.index, bitmap.width.toFloat() / bitmap.height)
                bitmap.recycle()
            } else {
                uploading = beginUpload(next)
            }
            if (next.quality != PREVIEW && SystemClock.uptimeMillis() - start >= budgetMillis) {
                return uploading != null || decoded.isNotEmpty()
            }
        }
    }

    private fun discard(decoded: Decoded) {
        decoded.bitmap?.recycle()
        decoded.pixels?.let(::release)
    }

    private fun beginUpload(page: Decoded): Upload {
        val id = newTexture()
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, mipLevels(page.width, page.height), GLES30.GL_RGBA8, page.width, page.height)
        return Upload(page.index, page.quality, id, page.width, page.height, page.pixels!!)
    }

    /** Uploads bands of rows within the budget; true once the texture is complete and in use. */
    private fun continueUpload(u: Upload, start: Long, budgetMillis: Long): Boolean {
        val bandRows = max(16, BAND_BYTES / (u.width * 4))
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, u.id)
        do {
            val rows = min(bandRows, u.height - u.row)
            u.pixels.position(u.row * u.width * 4)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, u.row, u.width, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, u.pixels)
            u.row += rows
        } while (u.row < u.height && SystemClock.uptimeMillis() - start < budgetMillis)
        if (u.row < u.height) return false

        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        textures[u.index]?.let { GLES30.glDeleteTextures(1, intArrayOf(it.id), 0) }
        textures[u.index] = Texture(u.id, u.width, u.height, u.quality)
        onLoaded(u.index, u.width.toFloat() / u.height)
        release(u.pixels)
        uploading = null
        return true
    }

    private fun abortUpload() {
        val u = uploading ?: return
        GLES30.glDeleteTextures(1, intArrayOf(u.id), 0)
        release(u.pixels)
        inFlight -= key(u.index, u.quality)
        uploading = null
    }

    private fun mipLevels(width: Int, height: Int): Int {
        var levels = 1
        var size = max(width, height)
        while (size > 1) {
            size /= 2
            levels++
        }
        return levels
    }

    private fun obtain(bytes: Int): ByteBuffer {
        val reused = synchronized(pool) {
            val i = pool.indexOfFirst { it.capacity() >= bytes && it.capacity() <= bytes * 2 }
            if (i >= 0) pool.removeAt(i) else null
        }
        val buffer = reused ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        buffer.clear()
        buffer.limit(bytes)
        return buffer
    }

    private fun release(buffer: ByteBuffer) {
        synchronized(pool) {
            if (pool.size < POOL_SIZE) pool += buffer
        }
    }

    private fun drainDecoded() {
        while (true) {
            discard(decoded.poll() ?: break)
        }
    }

    private fun uploadWhole(bitmap: Bitmap, quality: Int): Texture {
        val id = newTexture()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return Texture(id, bitmap.width, bitmap.height, quality)
    }

    /** A new bound texture with the page sampling settings. */
    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (anisotropy > 1f) {
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
        }
        return ids[0]
    }

    private fun key(index: Int, quality: Int) = index.toLong() * 4 + quality

    private companion object {
        const val TAG = "PageTextureCache"
        const val PREVIEW = 0
        const val SHARP = 1
        const val ZOOM = 2

        /** Longest side of a preview, in pixels. */
        const val PREVIEW_SIZE = 480f

        /** About this much goes to the GPU at once: well under a millisecond on phones. */
        const val BAND_BYTES = 1024 * 1024
        const val POOL_SIZE = 2

        const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
    }
}
