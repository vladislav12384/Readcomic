package com.readcomic.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap

/** PDF comics through the platform renderer (which is single threaded, hence the lock). */
class PdfComicSource(pfd: ParcelFileDescriptor) : ComicSource {
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    override fun loadPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        synchronized(lock) {
            if (closed || index !in 0 until pageCount) return null
            renderer.openPage(index).use { page ->
                // Page size is in points; render at whatever resolution fits the requested box.
                val scale = minOf(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    override fun readPageSizes(onProgress: (Float) -> Unit): List<Pair<Int, Int>?> = synchronized(lock) {
        if (closed) return emptyList()
        List(pageCount) { index ->
            onProgress((index + 1f) / pageCount)
            renderer.openPage(index).use { it.width to it.height }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            renderer.close()
        }
    }
}
