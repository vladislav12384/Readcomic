package com.readcomic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import com.readcomic.data.ComicEntry
import com.readcomic.data.ComicOpener
import com.readcomic.shelf.Binding
import com.readcomic.shelf.ComicShape
import com.readcomic.shelf.ShelfArt
import com.readcomic.shelf.ShelfController
import java.io.File
import kotlin.math.abs

/** Pictures of the library's comics for the shelf. Called on background threads. */
class LibraryShelfArt(
    private val context: Context,
    private val entries: Map<String, ComicEntry>,
    private val coverFile: (ComicEntry) -> File,
) : ShelfArt {

    override fun cover(id: String): Bitmap? {
        val file = entries[id]?.let(coverFile)?.takeIf { it.exists() } ?: return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        return frontHalf(bitmap)
    }

    override fun label(id: String): Bitmap? {
        val entry = entries[id] ?: return null
        val bitmap = createBitmap(LABEL_WIDTH, LABEL_HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = INK_LIGHT
        }
        canvas.drawRect(9f, 9f, LABEL_WIDTH - 9f, LABEL_HEIGHT - 9f, border)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textSize = 46f
            color = INK
            textAlign = Paint.Align.CENTER
        }
        val title = TextUtils.ellipsize(entry.title, text, LABEL_WIDTH - 64f, TextUtils.TruncateAt.END)
        val baseline = LABEL_HEIGHT / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(title, 0, title.length, LABEL_WIDTH / 2f, baseline, text)
        return bitmap
    }

    override fun page(id: String, back: Boolean): Bitmap? {
        val entry = entries[id] ?: return null
        ComicOpener.open(context, entry.uri).use { source ->
            val first = source.loadPage(0, SHARP_WIDTH * 2, SHARP_HEIGHT) ?: return null
            if (isWide(first)) {
                // A wraparound cover: the back is the left half, the front the right half.
                return half(first, right = !back)
            }
            if (!back) return frontHalf(first)
            first.recycle()
            if (source.pageCount < 2) return null
            val last = source.loadPage(source.pageCount - 1, SHARP_WIDTH * 2, SHARP_HEIGHT) ?: return null
            // A back cover that is not cover-shaped (a spread, a tiny strip) would look stretched.
            val coverAspect = coverAspect(entry) ?: return last
            if (abs(last.width.toFloat() / last.height - coverAspect) > 0.15f) {
                last.recycle()
                return null
            }
            return last
        }
    }

    override fun spine(id: String): Bitmap? {
        val entry = entries[id] ?: return null
        val binding = ComicShape.binding(entry.pageCount)
        val thickness = ComicShape.thickness(entry.pageCount)
        // As wide as the spine is thick, at the same scale as along it: letters keep their shape.
        val height = SPINE_HEIGHT
        val width = (height * thickness / ShelfController.COMIC_HEIGHT).toInt().coerceAtLeast(8)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val background = spineColor(entry)
        canvas.drawColor(background)
        if (binding == Binding.STAPLED || thickness < 0.004f) return bitmap

        val light = luminance(background) < 0.5f
        val ink = when {
            binding == Binding.HARDCOVER -> FOIL
            light -> Color.rgb(240, 234, 222)
            else -> Color.rgb(28, 22, 18)
        }
        val rule = Paint().apply { color = ink }
        val bandHeight = (height * 0.004f).coerceAtLeast(3f)
        // Thin bands near the ends, like on bound books.
        for (y in listOf(height * 0.035f, height * 0.965f - bandHeight)) canvas.drawRect(0f, y, width.toFloat(), y + bandHeight, rule)
        if (binding == Binding.HARDCOVER) {
            for (y in listOf(height * 0.045f, height * 0.955f - bandHeight)) canvas.drawRect(0f, y, width.toFloat(), y + bandHeight * 0.5f, rule)
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = width * 0.46f
            letterSpacing = 0.06f
            color = ink
            textAlign = Paint.Align.CENTER
        }
        // The title runs down the spine, as on most comics and books.
        val title = TextUtils.ellipsize(entry.title, text, height * 0.82f, TextUtils.TruncateAt.END)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(90f)
        canvas.drawText(title, 0, title.length, 0f, -(text.descent() + text.ascent()) / 2f, text)
        canvas.restore()
        return bitmap
    }

    /** The colour of the spine: the middle of a wraparound cover, else the cover's spine edge. */
    private fun spineColor(entry: ComicEntry): Int {
        val file = coverFile(entry).takeIf { it.exists() } ?: return Color.rgb(60, 50, 44)
        val cover = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?: return Color.rgb(60, 50, 44)
        val wide = isWide(cover)
        val x0 = if (wide) (cover.width * 0.48f).toInt() else 0
        val x1 = if (wide) (cover.width * 0.52f).toInt() else (cover.width * 0.06f).toInt().coerceAtLeast(1)
        val stripWidth = (x1.coerceAtMost(cover.width) - x0).coerceAtLeast(1)
        val strip = IntArray(stripWidth * cover.height)
        cover.getPixels(strip, 0, stripWidth, x0, 0, stripWidth, cover.height)
        cover.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        val n = strip.size.toLong()
        for (pixel in strip) {
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
        }
        if (n == 0L) return Color.rgb(60, 50, 44)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun luminance(color: Int) =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

    override fun plaque(count: Int): Bitmap {
        val bitmap = createBitmap(PLAQUE_WIDTH, PLAQUE_HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f
        }
        canvas.drawRect(14f, 14f, PLAQUE_WIDTH - 14f, PLAQUE_HEIGHT - 14f, line)
        line.strokeWidth = 1.5f
        canvas.drawRect(22f, 22f, PLAQUE_WIDTH - 22f, PLAQUE_HEIGHT - 22f, line)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 62f
            letterSpacing = 0.22f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("КОЛЛЕКЦИЯ", PLAQUE_WIDTH / 2f, 94f, title)
        val subtitle = TextPaint(title).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textSize = 30f
            letterSpacing = 0.08f
        }
        canvas.drawText(comicsCount(count), PLAQUE_WIDTH / 2f, 134f, subtitle)
        return bitmap
    }

    private fun coverAspect(entry: ComicEntry): Float? = coverAspect(coverFile(entry))

    companion object {
        private const val LABEL_WIDTH = 640
        private const val LABEL_HEIGHT = 100
        private const val SPINE_HEIGHT = 1536
        private val FOIL = Color.rgb(217, 178, 106)
        private const val PLAQUE_WIDTH = 1024
        private const val PLAQUE_HEIGHT = 166
        private const val SHARP_WIDTH = 900
        private const val SHARP_HEIGHT = 1400
        private const val PAPER = 0xFFEFE8DA.toInt()
        private const val INK = 0xFF3A2A1C.toInt()
        private const val INK_LIGHT = 0xFF9A8670.toInt()

        private fun isWide(bitmap: Bitmap) = bitmap.width > bitmap.height * 1.1f

        /** The front cover: the right half of a wraparound cover, otherwise the whole picture. */
        private fun frontHalf(bitmap: Bitmap) = if (isWide(bitmap)) half(bitmap, right = true) else bitmap

        private fun half(bitmap: Bitmap, right: Boolean): Bitmap {
            val width = bitmap.width / 2
            val result = Bitmap.createBitmap(bitmap, if (right) bitmap.width - width else 0, 0, width, bitmap.height)
            if (result !== bitmap) bitmap.recycle()
            return result
        }

        /**
         * The most striking colour of the cover saved in [file] (ARGB), null if there is none:
         * the average of the most common vivid hue, so a red cover gives red, not a muddy mix.
         */
        fun coverAccent(file: File): Int? {
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
            val bins = 12
            val weight = FloatArray(bins)
            val red = FloatArray(bins)
            val green = FloatArray(bins)
            val blue = FloatArray(bins)
            val hsv = FloatArray(3)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            for (pixel in pixels) {
                Color.colorToHSV(pixel, hsv)
                val w = hsv[1] * hsv[1] * hsv[2] + 0.002f
                val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
                weight[bin] += w
                red[bin] += Color.red(pixel) * w
                green[bin] += Color.green(pixel) * w
                blue[bin] += Color.blue(pixel) * w
            }
            val best = weight.indices.maxBy { weight[it] }
            val w = weight[best]
            if (w <= 0f) return null
            return Color.rgb((red[best] / w).toInt(), (green[best] / w).toInt(), (blue[best] / w).toInt())
        }

        /** Width / height of the front cover saved in [file], null if there is none. */
        fun coverAspect(file: File): Float? {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var width = bounds.outWidth.toFloat()
            if (width > bounds.outHeight * 1.1f) width /= 2f
            return width / bounds.outHeight
        }
    }
}
