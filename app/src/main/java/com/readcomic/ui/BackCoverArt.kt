package com.readcomic.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

/** Where the back of the comic in hand comes from. */
enum class BackCovers(val label: String, val note: String) {
    FILE("Из файла", "Последняя страница файла, а если её нет — цвет обложки"),
    MISSING("Своя, если нет", "Последняя страница, а если её нет — нарисованная по обложке"),
    ALWAYS("Всегда своя", "Нарисованная по обложке, даже если последняя страница есть"),
}

/**
 * A back cover for a comic that has none, made from its front the way the spine is: the front's
 * picture turned over and blurred into a backdrop in the spine's colours, so that it runs on
 * round the spine; on it the title, the number, who wrote it and when, what it is about if the
 * file says (else a small picture of the front), and at the bottom a barcode and the imprint.
 */
object BackCoverArt {
    class Text(
        val title: String,
        val number: String?,
        val credits: List<String>,
        val summary: String?,
        val pages: Int,
    )

    fun draw(front: Bitmap?, colors: SpinePalette.Colors, text: Text, seed: Int, width: Int, height: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val background = colors.background
        val light = Color.rgb(243, 237, 226)
        val black = Color.rgb(30, 25, 20)
        // Light letters unless dark ones stand out clearly better (the bottom is darkened too).
        val dark = SpinePalette.contrast(light, background) * 1.15f >= SpinePalette.contrast(black, background)
        val ink = if (dark) light else black
        val accent = colors.ink.takeIf { SpinePalette.contrast(it, background) >= 2.5f } ?: ink

        canvas.drawColor(background)
        if (front != null) backdrop(canvas, front, w, h)
        // In the spine's colour, darker towards the bottom, a little lighter in the middle.
        val tint = Paint().apply { color = background; alpha = 150 }
        canvas.drawRect(0f, 0f, w, h, tint)
        val fall = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, fall)
        val glow = Paint().apply {
            shader = RadialGradient(w * 0.5f, h * 0.42f, max(w, h) * 0.7f, Color.argb(if (dark) 34 else 50, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, glow)

        // The title in big condensed capitals, two lines at most.
        val margin = w * 0.08f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            color = ink
            letterSpacing = 0.02f
            textSize = 1f
        }
        val shown = text.title.uppercase()
        val oneLine = (w - 2 * margin) / max(titlePaint.measureText(shown), 1f)
        titlePaint.textSize = min(w * 0.105f, max(oneLine, w * 0.062f))
        var y = h * 0.075f
        y += drawBlock(canvas, shown, titlePaint, margin, y, w - 2 * margin, maxLines = 2, center = true)

        // Number, who wrote it, when.
        val line = listOfNotNull(text.number?.let { "№ $it" }).plus(text.credits).joinToString("  ·  ")
        if (line.isNotEmpty()) {
            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textSize = w * 0.04f
                color = ink
                alpha = 215
            }
            y += w * 0.02f
            y += drawBlock(canvas, line, small, margin, y, w - 2 * margin, maxLines = 1, center = true)
        }
        y += w * 0.04f
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawRect(w / 2f - w * 0.08f, y, w / 2f + w * 0.08f, y + w * 0.006f, rule)
        y += w * 0.07f

        // The barcode box and the imprint take the bottom.
        val boxHeight = w * 0.16f
        val bottom = h - margin
        val room = bottom - boxHeight - w * 0.08f - y
        val summary = text.summary
        if (summary != null && room > w * 0.2f) {
            // What it is about, after a big opening quote in the accent colour.
            val quote = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = w * 0.16f
                color = accent
            }
            canvas.drawText("“", margin, y + w * 0.1f, quote)
            val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                textSize = w * 0.041f
                color = ink
            }
            val lines = max(3, (room / (body.fontSpacing * 1.22f)).toInt())
            val indent = w * 0.1f
            drawBlock(canvas, summary, body, margin + indent, y, w - 2 * margin - indent, maxLines = lines, center = false, spacing = 1.22f)
        } else if (front != null && room > w * 0.25f) {
            // Nothing to say: a small picture of the front, as on a catalogue page.
            val pictureWidth = min(w * 0.38f, room * 0.8f * front.width / front.height)
            val pictureHeight = pictureWidth * front.height / front.width
            val cx = w / 2f
            val cy = y + room / 2f
            canvas.withRotation(-4f, cx, cy) {
                val frame = RectF(cx - pictureWidth / 2f, cy - pictureHeight / 2f, cx + pictureWidth / 2f, cy + pictureHeight / 2f)
                val border = w * 0.012f
                val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(246, 243, 236)
                    setShadowLayer(w * 0.03f, w * 0.008f, w * 0.014f, Color.argb(130, 0, 0, 0))
                }
                drawRect(frame.left - border, frame.top - border, frame.right + border, frame.bottom + border, paper)
                drawBitmap(front, null, frame, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        }

        barcode(canvas, margin, bottom - boxHeight, w * 0.3f, boxHeight, seed)
        imprint(canvas, w - margin, bottom, w, ink, accent, text.pages)
        return bitmap
    }

    /**
     * The front mirrored (its spine edge now next to the spine) and blurred to soft colour: made
     * tiny, then grown back in doublings, each a smooth blend, so no blocks show.
     */
    private fun backdrop(canvas: Canvas, front: Bitmap, w: Float, h: Float) {
        var width = 8
        var height = max(2, (8f * front.height / front.width).toInt())
        var soft = front.scale(width, height)
        while (width < 128) {
            width *= 2
            height *= 2
            val next = soft.scale(width, height)
            // The front itself (a tiny one, scaled to its own size) is still needed.
            if (soft !== front) soft.recycle()
            soft = next
        }
        canvas.withScale(-1f, 1f, w / 2f, h / 2f) {
            drawBitmap(soft, null, Rect(0, 0, w.toInt(), h.toInt()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (soft !== front) soft.recycle()
    }

    /** Draws [text] from [top] in a column [width] wide at [left]; returns the height it took. */
    private fun drawBlock(canvas: Canvas, text: String, paint: TextPaint, left: Float, top: Float, width: Float, maxLines: Int, center: Boolean, spacing: Float = 1f): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width.toInt()))
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()
        canvas.withTranslation(left, top) { layout.draw(this) }
        return layout.height.toFloat()
    }

    /** A white box with a barcode and its digits, made up from [seed]. */
    private fun barcode(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, seed: Int) {
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 249, 245) }
        val r = height * 0.06f
        canvas.drawRoundRect(left, top, left + width, top + height, r, r, box)
        val bar = Paint().apply { color = Color.rgb(20, 18, 16) }
        val pad = width * 0.08f
        val barsBottom = top + height * 0.72f
        var x = left + pad
        var k = seed and 0x7fffffff
        val unit = width * 0.012f
        while (x < left + width - pad) {
            k = (k * 1103515245 + 12345) and 0x7fffffff
            val thick = unit * (1 + k % 3)
            if (x + thick > left + width - pad) break
            canvas.drawRect(x, top + height * 0.1f, x + thick, barsBottom, bar)
            x += thick + unit * (1 + (k / 7) % 2)
        }
        val digits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = height * 0.16f
            color = bar.color
            textAlign = Paint.Align.CENTER
        }
        var n = seed and 0x7fffffff
        val code = buildString {
            append("4 ")
            repeat(11) {
                n = (n * 1103515245 + 12345) and 0x7fffffff
                append((n / 13) % 10)
                if (it == 5) append(' ')
            }
        }
        canvas.drawText(code, left + width / 2f, top + height * 0.92f, digits)
    }

    /** The imprint in the bottom right corner: a round mark, the name, the page count. */
    private fun imprint(canvas: Canvas, right: Float, bottom: Float, w: Float, ink: Int, accent: Int, pages: Int) {
        val radius = w * 0.045f
        val cx = right - radius
        val cy = bottom - w * 0.1f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.006f
            color = accent
        }
        canvas.drawCircle(cx, cy, radius, ring)
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = radius * 0.95f
            color = accent
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("RC", cx, cy - (mark.descent() + mark.ascent()) / 2f, mark)
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = w * 0.028f
            letterSpacing = 0.2f
            color = ink
            alpha = 220
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("READCOMIC", right, bottom - w * 0.02f, name)
        if (pages > 0) {
            val count = TextPaint(name).apply {
                letterSpacing = 0.04f
                textSize = w * 0.026f
                alpha = 170
            }
            canvas.drawText("$pages стр.", right - radius * 2f - w * 0.03f, cy - (count.descent() + count.ascent()) / 2f, count)
        }
    }
}
