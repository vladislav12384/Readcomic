package com.readcomic.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A comic opened for reading. Implementations must allow [loadPage] from several threads. */
interface ComicSource : Closeable {
    val pageCount: Int

    /** Decodes a page so that it fits into [maxWidth] x [maxHeight], keeping its aspect ratio. */
    fun loadPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap?

    /** Width and height of every page (null where unknown), read without decoding whole pages. */
    fun readPageSizes(onProgress: (Float) -> Unit = {}): List<Pair<Int, Int>?> = emptyList()

    /** True while pages are slow to reach and [prepare] would make them fast (unpacking a solid archive). */
    val needsPreparation: Boolean get() = false

    /** Makes every page quick to load; may take a while, call it in the background. */
    fun prepare(onProgress: (Float) -> Unit = {}) = Unit
}

object PageDecoder {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    fun isImageName(name: String): Boolean {
        val file = name.substringAfterLast('/')
        if (file.startsWith(".") || name.contains("__MACOSX/")) return false
        return file.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    /** Image size from its header; works on a truncated file as long as the header is there. */
    fun size(bytes: ByteArray, length: Int = bytes.size): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, length, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val (targetWidth, targetHeight) = fit(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return finish(decoded, targetWidth, targetHeight)
    }

    /** Scales to the exact target size and flattens transparency onto white paper. */
    fun finish(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        var result = bitmap
        if (result.width > targetWidth || result.height > targetHeight) {
            val scaled = result.scale(targetWidth, targetHeight)
            if (scaled !== result) result.recycle()
            result = scaled
        }
        if (result.hasAlpha() || result.config != Bitmap.Config.ARGB_8888) {
            val opaque = createBitmap(result.width, result.height)
            opaque.eraseColor(Color.WHITE)
            Canvas(opaque).drawBitmap(result, 0f, 0f, null)
            result.recycle()
            result = opaque
        }
        return result
    }

    fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(1f, min(maxWidth.toFloat() / width, maxHeight.toFloat() / height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}

/** Sorts "page2.jpg" before "page10.jpg". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
