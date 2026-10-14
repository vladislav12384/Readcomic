package com.readcomic.curl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Blocking page decoder, called from a background thread. */
fun interface PageLoader {
    fun load(index: Int, maxWidth: Int, maxHeight: Int): Bitmap?
}

/**
 * GL textures of the pages around the current one. Bitmaps are decoded in the background and
 * uploaded on the render thread. The page the reader zooms into gets a sharper version.
 *
 * Everything except the decoding runs on the render thread.
 */
class PageTextureCache(
    private val scope: CoroutineScope,
    private val runOnGlThread: (Runnable) -> Unit,
    private val onLoaded: (index: Int, aspect: Float) -> Unit,
) {
    class Texture(val id: Int, val width: Int, val height: Int, val hiRes: Boolean)

    private val textures = HashMap<Int, Texture>()
    private val inFlight = HashMap<Int, Boolean>()
    private val failed = HashSet<Int>()
    private var generation = 0
    private var maxTextureSize = 2048
    private var anisotropy = 1f
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)

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
        generation++
    }

    operator fun get(index: Int): Texture? = textures[index]

    /**
     * Keeps exactly [wanted] in memory (requesting missing ones in order of priority),
     * with a high resolution version for [hiResIndex].
     */
    fun update(wanted: List<Int>, hiResIndex: Int?, viewWidth: Float, viewHeight: Float) {
        val iterator = textures.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in wanted) {
                GLES30.glDeleteTextures(1, intArrayOf(entry.value.id), 0)
                iterator.remove()
            }
        }
        val limit = min(maxTextureSize, 4096)
        for (index in wanted) {
            if (index in failed || index in inFlight) continue
            val hi = index == hiResIndex
            val texture = textures[index]
            if (texture != null && (texture.hiRes || !hi)) continue
            val factor = if (hi) 2.2f else 1.35f
            val width = min((viewWidth * factor).toInt(), limit)
            val height = min((viewHeight * factor).toInt(), limit)
            request(index, hi, width, height)
        }
    }

    private fun request(index: Int, hiRes: Boolean, maxWidth: Int, maxHeight: Int) {
        val loader = loader ?: return
        inFlight[index] = hiRes
        val requestGeneration = generation
        scope.launch(dispatcher) {
            val bitmap = try {
                loader.load(index, maxWidth, maxHeight)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load page $index", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Out of memory loading page $index", e)
                null
            }
            runOnGlThread {
                if (requestGeneration != generation) {
                    bitmap?.recycle()
                    return@runOnGlThread
                }
                inFlight.remove(index)
                if (bitmap == null) {
                    if (!hiRes) failed.add(index)
                    return@runOnGlThread
                }
                textures.remove(index)?.let { GLES30.glDeleteTextures(1, intArrayOf(it.id), 0) }
                textures[index] = upload(bitmap, hiRes)
                onLoaded(index, bitmap.width.toFloat() / bitmap.height)
                bitmap.recycle()
            }
        }
    }

    private fun upload(bitmap: Bitmap, hiRes: Boolean): Texture {
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
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return Texture(ids[0], bitmap.width, bitmap.height, hiRes)
    }

    private companion object {
        const val TAG = "PageTextureCache"
        const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
    }
}
