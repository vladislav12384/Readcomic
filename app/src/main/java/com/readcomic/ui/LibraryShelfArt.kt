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
import androidx.core.graphics.withTranslation
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

    override fun label(text: String): Bitmap? {
        val bitmap = createBitmap(LABEL_WIDTH, LABEL_HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = INK_LIGHT
        }
        canvas.drawRect(9f, 9f, LABEL_WIDTH - 9f, LABEL_HEIGHT - 9f, border)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textSize = 46f
            color = INK
            textAlign = Paint.Align.CENTER
        }
        val title = TextUtils.ellipsize(text, paint, LABEL_WIDTH - 64f, TextUtils.TruncateAt.END)
        val baseline = LABEL_HEIGHT / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(title, 0, title.length, LABEL_WIDTH / 2f, baseline, paint)
        return bitmap
    }

    override fun sign(name: String, aspect: Float): Bitmap {
        // Engraved like the name plate: a rule round the edge and the name in spaced capitals.
        val height = SIGN_HEIGHT
        val width = (height * aspect).toInt().coerceIn(height * 2, 2048)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2.5f
        }
        canvas.drawRect(10f, 10f, width - 10f, height - 10f, line)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = height * 0.46f
            letterSpacing = 0.16f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        val shown = TextUtils.ellipsize(name.uppercase(), text, width - height * 0.6f, TextUtils.TruncateAt.END)
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(shown, 0, shown.length, width / 2f, baseline, text)
        return bitmap
    }

    override fun box(count: Int): Bitmap {
        // The front of a shipping carton: stencilled name and arrows, and a white label.
        val width = BOX_WIDTH
        val height = BOX_HEIGHT
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val ink = Color.argb(225, 38, 24, 14)
        val logo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = height * 0.25f
            letterSpacing = 0.04f
            color = ink
        }
        // As wide as the room left of the arrows.
        logo.textSize *= ((width * 0.5f) / logo.measureText("READCOMIC")).coerceAtMost(1f)
        canvas.drawText("READCOMIC", width * 0.05f, height * 0.42f, logo)
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            textSize = height * 0.085f
            letterSpacing = 0.12f
            color = ink
        }
        small.textSize *= ((width * 0.48f) / small.measureText("КОМИКСЫ · ОБРАЩАТЬСЯ БЕРЕЖНО")).coerceAtMost(1f)
        canvas.drawText("КОМИКСЫ · ОБРАЩАТЬСЯ БЕРЕЖНО", width * 0.055f, height * 0.58f, small)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            strokeWidth = height * 0.018f
        }
        canvas.drawLine(width * 0.05f, height * 0.66f, width * 0.53f, height * 0.66f, line)
        // "This way up": two arrows over a bar.
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val ax = width * 0.6f
        val top = height * 0.22f
        val bottom = height * 0.66f
        for (k in 0..1) {
            val cx = ax + k * height * 0.15f
            val shaft = height * 0.035f
            canvas.drawRect(cx - shaft, top + height * 0.1f, cx + shaft, bottom - height * 0.08f, arrow)
            val path = android.graphics.Path().apply {
                moveTo(cx, top)
                lineTo(cx - height * 0.075f, top + height * 0.12f)
                lineTo(cx + height * 0.075f, top + height * 0.12f)
                close()
            }
            canvas.drawPath(path, arrow)
        }
        canvas.drawRect(ax - height * 0.08f, bottom - height * 0.05f, ax + height * 0.23f, bottom, arrow)

        // The label, stuck on a little crooked.
        canvas.withTranslation(width * 0.81f, height * 0.5f) {
            rotate(-2.5f)
            val w = height * 0.95f
            val h = height * 0.72f
            val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 243, 236) }
            drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, paper)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.rgb(60, 52, 46)
            }
            drawRect(-w / 2f + 10f, -h / 2f + 10f, w / 2f - 10f, h / 2f - 10f, frame)
            val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                textSize = h * 0.13f
                letterSpacing = 0.12f
                color = Color.rgb(60, 52, 46)
                textAlign = Paint.Align.CENTER
            }
            drawText("НОВЫЕ КОМИКСЫ", 0f, -h * 0.25f, heading)
            val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = h * if (count < 100) 0.42f else 0.32f
                color = Color.rgb(150, 34, 28)
                textAlign = Paint.Align.CENTER
            }
            drawText("× $count", 0f, h * 0.17f, number)
            // A barcode along the bottom.
            val bar = Paint().apply { color = Color.rgb(40, 36, 32) }
            var x = -w * 0.36f
            var k = count * 7 + 3
            while (x < w * 0.36f) {
                k = (k * 1103515245 + 12345) and 0x7fffffff
                val thick = 2f + (k % 4)
                drawRect(x, h * 0.27f, x + thick, h * 0.4f, bar)
                x += thick + 2f + (k / 7 % 3)
            }
        }
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
        // A stapled issue has no printed spine: the covers run round its fold (drawn from the cover itself).
        if (binding == Binding.STAPLED) return null
        val thickness = ComicShape.thickness(entry.pageCount)
        // As wide as the spine is thick, at the same scale as along it: letters keep their shape.
        val height = SPINE_HEIGHT
        val width = (height * thickness / ShelfController.COMIC_HEIGHT).toInt().coerceAtLeast(8)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val colors = spineColors(entry)
        val hardcover = binding == Binding.HARDCOVER
        val background = if (hardcover) SpinePalette.cloth(colors.background) else colors.background
        canvas.drawColor(background)
        if (thickness < 0.004f) return bitmap

        // Gold foil on cloth, the cover's own colour on a paperback.
        val ink = if (hardcover) FOIL else colors.ink
        val rule = Paint().apply { color = ink }
        val band = (height * 0.004f).coerceAtLeast(3f)
        for (y in listOf(height * 0.03f, height * 0.97f - band)) canvas.drawRect(0f, y, width.toFloat(), y + band, rule)
        if (hardcover) {
            for (y in listOf(height * 0.04f, height * 0.96f - band)) canvas.drawRect(0f, y, width.toFloat(), y + band * 0.5f, rule)
        }

        // "Series #12": the number goes into a badge at the top, the series down the middle.
        val numbered = NUMBER.find(entry.title)
        val title = numbered?.let { entry.title.substring(0, it.range.first).trim() }?.ifEmpty { null } ?: entry.title
        var top = height * 0.06f
        if (numbered != null) {
            val number = numbered.groupValues[1]
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
            val side = width * 0.78f
            val left = (width - side) / 2f
            canvas.drawRoundRect(left, top, left + side, top + side, side * 0.18f, side * 0.18f, badge)
            val digits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = side * if (number.length <= 2) 0.58f else 0.42f
                color = background
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(number, width / 2f, top + side / 2f - (digits.descent() + digits.ascent()) / 2f, digits)
            top += side + width * 0.3f
        }
        var bottom = height * 0.94f
        entry.writer?.let { writer ->
            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textSize = width * 0.3f
                color = ink
                alpha = 210
                textAlign = Paint.Align.CENTER
            }
            val room = height * 0.2f
            val text = TextUtils.ellipsize(writer, small, room, TextUtils.TruncateAt.END)
            val center = bottom - room / 2f
            canvas.withTranslation(width / 2f, center) {
                rotate(90f)
                drawText(text, 0, text.length, 0f, -(small.descent() + small.ascent()) / 2f, small)
            }
            bottom -= room + width * 0.3f
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = width * 0.46f
            letterSpacing = 0.06f
            color = ink
            textAlign = Paint.Align.CENTER
        }
        // The title runs down the spine, as on most comics and books.
        val fitted = TextUtils.ellipsize(title, text, bottom - top, TextUtils.TruncateAt.END)
        canvas.withTranslation(width / 2f, (top + bottom) / 2f) {
            rotate(90f)
            drawText(fitted, 0, fitted.length, 0f, -(text.descent() + text.ascent()) / 2f, text)
        }
        return bitmap
    }

    /** Spine colours from the cover; a wraparound cover's own spine is its middle. */
    private fun spineColors(entry: ComicEntry): SpinePalette.Colors {
        val file = coverFile(entry).takeIf { it.exists() } ?: return SpinePalette.pick(IntArray(0), 0, 0, 0)
        val cover = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?: return SpinePalette.pick(IntArray(0), 0, 0, 0)
        val pixels = IntArray(cover.width * cover.height)
        cover.getPixels(pixels, 0, cover.width, 0, 0, cover.width, cover.height)
        val wide = isWide(cover)
        val width = cover.width
        cover.recycle()
        return if (wide) {
            SpinePalette.pick(pixels, width, (width * 0.47f).toInt(), (width * 0.53f).toInt() + 1)
        } else {
            SpinePalette.pick(pixels, width, 0, (width * 0.08f).toInt() + 1)
        }
    }

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
        private val NUMBER = Regex("""#\s*(\S{1,4})\s*$""")
        private const val SIGN_HEIGHT = 112
        private const val PLAQUE_WIDTH = 1024
        private const val PLAQUE_HEIGHT = 166
        private const val SHARP_WIDTH = 900
        private const val SHARP_HEIGHT = 1400
        private const val PAPER = 0xFFEFE8DA.toInt()
        private const val INK = 0xFF3A2A1C.toInt()
        private const val INK_LIGHT = 0xFF9A8670.toInt()

        fun isWide(bitmap: Bitmap) = bitmap.width > bitmap.height * 1.1f

        /** The front cover: the right half of a wraparound cover, otherwise the whole picture. */
        fun frontHalf(bitmap: Bitmap) = if (isWide(bitmap)) half(bitmap, right = true) else bitmap

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

        private const val BOX_WIDTH = 1024
        private const val BOX_HEIGHT = 364

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
