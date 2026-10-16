package com.readcomic.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A back cover for a comic whose own back is missing or is not a cover, drawn in the manner of
 * the comic's print: a panel with a detail of the front cover as a halftone screen in the cover's
 * own colours, the issue number in a corner badge, the series, the writer, the year, the summary
 * if the file has one, and a barcode.
 */
internal object DrawnBackCover {
    /** What is printed on the back. */
    class Issue(val title: String, val writer: String?, val year: Int?, val summary: String?, val seed: String)

    /**
     * Draws the back of a comic with the front [cover] (null if there is none), in [colors]
     * (those of its spine), [aspect] times as wide as high.
     */
    fun draw(cover: Bitmap?, colors: SpinePalette.Colors, issue: Issue, aspect: Float): Bitmap {
        val height = HEIGHT
        val width = (height * aspect.coerceIn(0.5f, 0.9f)).roundToInt()
        val w = width.toFloat()
        val h = height.toFloat()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val ground = colors.background
        val ink = colors.ink
        // Small print needs more contrast than the big letters of a spine.
        val text = if (SpinePalette.contrast(ink, ground) >= 4.5f) ink else if (SpinePalette.luminance(ground) < 0.4f) SpinePalette.LIGHT_INK else SpinePalette.DARK_INK
        canvas.drawColor(ground)

        val margin = w * 0.065f
        val (series, number) = BackCoverLayout.splitIssue(issue.title)
        val summary = issue.summary?.replace(Regex("""\s+"""), " ")?.trim()?.ifEmpty { null }
        val drop = w * 0.014f

        // Along the bottom: the barcode and the imprint.
        val stripHeight = h * 0.095f
        val stripBottom = h - margin * 0.85f
        val stripTop = stripBottom - stripHeight

        // Under the panel: the series, then who and when (then what it is about, if the file says).
        val room = w - 2f * margin
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.02f
            color = text
            textSize = w * 0.1f
        }
        val shout = series.uppercase()
        title.textSize *= (room / title.measureText(shout)).coerceIn(0.6f, 1f)
        val fitted = TextUtils.ellipsize(shout, title, room, TextUtils.TruncateAt.END)
        val byline = listOfNotNull(issue.writer?.trim()?.ifEmpty { null }, issue.year?.toString()).joinToString(" · ")
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textSize = w * 0.045f
            color = text
            alpha = 220
        }
        val titleHeight = title.descent() - title.ascent()
        val bylineHeight = if (byline.isEmpty()) 0f else small.descent() - small.ascent() + w * 0.004f

        // The panel, with a solid block behind it as in the comic's own pages; without a summary
        // it takes the room down to the title.
        val panelBottom = if (summary != null) {
            margin + h * 0.5f
        } else {
            (stripTop - margin * 0.9f - bylineHeight - titleHeight - margin * 0.75f - drop).coerceAtLeast(margin + h * 0.4f)
        }
        val panel = RectF(margin, margin, w - margin, panelBottom)
        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        canvas.drawRect(panel.left + drop, panel.top + drop, panel.right + drop, panel.bottom + drop, inkPaint)
        canvas.drawRect(panel, Paint().apply { color = ground })
        canvas.withClip(panel) { halftone(this, cover, panel, ink, darkInk = SpinePalette.luminance(ink) < SpinePalette.luminance(ground), pitch = w / 62f) }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.011f
            color = ink
        }
        canvas.drawRect(panel, frame)

        // The number in a round badge over the panel's corner.
        if (number != null) badge(canvas, number, panel.left + w * 0.035f, panel.top + w * 0.035f, w * 0.095f, ground, ink)

        barcode(canvas, issue.seed, RectF(w - margin - w * 0.34f, stripTop, w - margin, stripBottom))
        imprint(canvas, margin, stripTop, stripHeight, ground, text)

        var y = panel.bottom + drop + margin * 0.75f
        canvas.drawText(fitted, 0, fitted.length, margin, y - title.ascent(), title)
        y += titleHeight
        if (byline.isNotEmpty()) {
            val line = TextUtils.ellipsize(byline, small, room, TextUtils.TruncateAt.END)
            canvas.drawText(line, 0, line.length, margin, y - small.ascent() + w * 0.004f, small)
            y += bylineHeight
        }

        if (summary != null) {
            y += margin * 0.35f
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text
                alpha = 110
                strokeWidth = max(2f, w * 0.0035f)
            }
            canvas.drawLine(margin, y, w - margin, y, rule)
            y += margin * 0.4f
            val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = w * 0.038f
                color = text
                alpha = 235
            }
            val spacing = 1.18f
            val lineHeight = (body.descent() - body.ascent()) * spacing
            val lines = floor((stripTop - margin * 0.45f - y) / lineHeight).toInt()
            if (lines >= 1) {
                val layout = StaticLayout.Builder.obtain(summary, 0, summary.length, body, room.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, spacing)
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                canvas.withTranslation(margin, y) { layout.draw(this) }
            }
        }
        return bitmap
    }

    /** A detail of the [cover] as a screen of [ink] dots in [panel]; a soft ramp of dots without a cover. */
    private fun halftone(canvas: Canvas, cover: Bitmap?, panel: RectF, ink: Int, darkInk: Boolean, pitch: Float) {
        val pw = panel.width()
        val ph = panel.height()
        // One sample for every half a dot is plenty; a small picture keeps it quick.
        val gw = ceil(pw / pitch * 2f).toInt().coerceAtLeast(2)
        val gh = ceil(ph / pitch * 2f).toInt().coerceAtLeast(2)
        val tones = cover?.takeIf { !it.isRecycled && it.width > 1 && it.height > 1 }?.let { picture ->
            // The middle of the cover, below its logo: the part with the drawing.
            val target = pw / ph
            val cw: Int
            val ch: Int
            if (picture.width.toFloat() / picture.height > target) {
                ch = picture.height
                cw = (ch * target).toInt().coerceIn(1, picture.width)
            } else {
                cw = picture.width
                ch = (cw / target).toInt().coerceIn(1, picture.height)
            }
            val left = (picture.width - cw) / 2
            val top = (picture.height * 0.58f - ch / 2f).toInt().coerceIn(0, picture.height - ch)
            val crop = Bitmap.createBitmap(picture, left, top, cw, ch)
            val small = crop.scale(gw, gh)
            if (crop !== picture && crop !== small) crop.recycle()
            val pixels = IntArray(gw * gh)
            small.getPixels(pixels, 0, gw, 0, 0, gw, gh)
            if (small !== picture) small.recycle()
            BackCoverLayout.tones(FloatArray(pixels.size) { lightness(pixels[it]) }, darkInk)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val centres = BackCoverLayout.screen(pw, ph, pitch)
        var i = 0
        while (i < centres.size) {
            val x = centres[i]
            val y = centres[i + 1]
            i += 2
            val tone = if (tones != null) {
                val gx = (x / pw * gw).toInt().coerceIn(0, gw - 1)
                val gy = (y / ph * gh).toInt().coerceIn(0, gh - 1)
                tones[gy * gw + gx]
            } else {
                0.12f + 0.45f * ((x / pw + y / ph) / 2f).coerceIn(0f, 1f)
            }
            val r = BackCoverLayout.dotRadius(tone, pitch)
            if (r >= 0.6f) canvas.drawCircle(panel.left + x, panel.top + y, r, dot)
        }
    }

    /** How light [color] looks, 0 to 1 (its sRGB values weighed as the eye weighs them). */
    private fun lightness(color: Int) =
        (0.2126f * ((color shr 16) and 0xFF) + 0.7152f * ((color shr 8) and 0xFF) + 0.0722f * (color and 0xFF)) / 255f

    /** "№ 12" in a round badge of [ink], ringed with [ground], centred at [cx], [cy]. */
    private fun badge(canvas: Canvas, number: String, cx: Float, cy: Float, radius: Float, ground: Int, ink: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.12f
            color = ground
        }
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius * 0.86f, ring)
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = radius * 0.34f
            color = ground
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("№", cx, cy - radius * 0.3f, small)
        val digits = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = radius * 0.78f
            color = ground
            textAlign = Paint.Align.CENTER
        }
        digits.textSize *= min(1f, radius * 1.3f / digits.measureText(number))
        canvas.drawText(number, cx, cy + radius * 0.12f - (digits.descent() + digits.ascent()) / 2f, digits)
    }

    /** A UPC label on white paper in [box]. */
    private fun barcode(canvas: Canvas, seed: String, box: RectF) {
        canvas.drawRect(box, Paint().apply { color = LABEL_PAPER })
        val digits = BackCoverLayout.upcDigits(seed)
        val modules = BackCoverLayout.upcModules(digits)
        val pad = box.height() * 0.1f
        val module = (box.width() - 2f * pad) / modules.size
        val bar = Paint().apply { color = BAR }
        val font = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = box.height() * 0.17f
            color = BAR
            textAlign = Paint.Align.CENTER
        }
        val top = box.top + pad
        val bottom = box.bottom - pad - font.textSize * 0.95f
        modules.forEachIndexed { index, on ->
            if (!on) return@forEachIndexed
            val x = box.left + pad + index * module
            val end = if (BackCoverLayout.isGuard(index)) bottom + font.textSize * 0.6f else bottom
            canvas.drawRect(x, top, x + module, end, bar)
        }
        val baseline = box.bottom - pad
        val left = box.left + pad
        canvas.drawText(digits.substring(1, 6), left + module * 24f, baseline, font)
        canvas.drawText(digits.substring(6, 11), left + module * 71f, baseline, font)
        font.textSize *= 0.8f
        canvas.drawText(digits.substring(0, 1), box.left + pad * 0.45f, baseline, font)
        canvas.drawText(digits.substring(11), box.right - pad * 0.45f, baseline, font)
    }

    /** The publisher's mark: a small round seal and the name, at [left] of the strip. */
    private fun imprint(canvas: Canvas, left: Float, top: Float, height: Float, ground: Int, ink: Int) {
        val r = height * 0.3f
        val cx = left + r
        val cy = top + height / 2f
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        val letter = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textSize = r * 1.25f
            color = ground
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("R", cx, cy - (letter.descent() + letter.ascent()) / 2f, letter)
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = height * 0.2f
            letterSpacing = 0.18f
            color = ink
        }
        canvas.drawText("READCOMIC", cx + r * 1.5f, cy - height * 0.02f, name)
        val line = TextPaint(name).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            textSize = height * 0.14f
            letterSpacing = 0.1f
            color = ColorUtils.setAlphaComponent(ink, 190)
        }
        canvas.drawText("КОЛЛЕКЦИЯ", cx + r * 1.5f, cy + height * 0.2f, line)
    }

    private const val HEIGHT = 1400
    private val LABEL_PAPER = Color.rgb(247, 245, 238)
    private val BAR = Color.rgb(24, 22, 20)
}
