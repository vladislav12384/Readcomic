package com.readcomic.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A small comic drawn in code, so the reader can be tried right away.
 * The balloons explain how to use the reader.
 */
class DemoComicSource : ComicSource {

    private enum class Scene { DAY, NIGHT, SUNSET, BURST, SPACE, FOREST }

    private class Panel(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val scene: Scene,
        val caption: String? = null,
        val balloon: String? = null,
        val hero: Boolean = false,
        val shout: String? = null,
    )

    private val pages: List<List<Panel>> = listOf(
        listOf(Panel(0.04f, 0.04f, 0.92f, 1.458f, Scene.BURST, shout = "READCOMIC", hero = true,
            caption = "Демо-выпуск №1. Листай!")),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 0.46f, Scene.DAY, caption = "Это демо-комикс. Он нарисован прямо в приложении."),
            Panel(0.04f, 0.54f, 0.45f, 0.5f, Scene.FOREST, hero = true, balloon = "Привет! Я покажу, как тут всё устроено."),
            Panel(0.51f, 0.54f, 0.45f, 0.5f, Scene.SUNSET),
            Panel(0.04f, 1.08f, 0.92f, 0.418f, Scene.NIGHT, caption = "Потяни правый край страницы влево — лист пойдёт за пальцем."),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.55f, 0.7f, Scene.SUNSET, hero = true, balloon = "Отпусти — и страница перелистнётся сама."),
            Panel(0.61f, 0.04f, 0.35f, 0.34f, Scene.SPACE),
            Panel(0.61f, 0.40f, 0.35f, 0.34f, Scene.DAY),
            Panel(0.04f, 0.78f, 0.92f, 0.718f, Scene.BURST, shout = "ШУРХ!", caption = "Быстрый взмах тоже листает."),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 0.5f, Scene.NIGHT, caption = "Тап по правой трети экрана — следующая страница, по левой — предыдущая."),
            Panel(0.04f, 0.58f, 0.92f, 0.918f, Scene.DAY, hero = true, balloon = "А если потянуть за уголок — загнётся именно уголок!"),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.45f, 0.6f, Scene.SPACE, caption = "Мелкий текст?"),
            Panel(0.51f, 0.04f, 0.45f, 0.6f, Scene.FOREST, hero = true, balloon = "Двойной тап или щипок — увеличение!"),
            Panel(0.04f, 0.68f, 0.92f, 0.818f, Scene.SUNSET, caption = "При увеличении страницу можно двигать пальцем. Двойной тап — обратно."),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 0.7f, Scene.BURST, shout = "МЕНЮ", caption = "Тап по центру экрана открывает меню."),
            Panel(0.04f, 0.78f, 0.92f, 0.718f, Scene.NIGHT, hero = true, balloon = "Там номер страницы и ползунок для быстрого перехода."),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 0.46f, Scene.DAY, caption = "Кнопки громкости тоже листают страницы."),
            Panel(0.04f, 0.54f, 0.45f, 0.95f, Scene.SPACE, hero = true, balloon = "Удобно читать одной рукой!"),
            Panel(0.51f, 0.54f, 0.45f, 0.45f, Scene.FOREST),
            Panel(0.51f, 1.03f, 0.45f, 0.46f, Scene.SUNSET),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 0.7f, Scene.FOREST, hero = true, balloon = "Свои комиксы добавляй кнопкой «+» в библиотеке."),
            Panel(0.04f, 0.78f, 0.92f, 0.718f, Scene.NIGHT, caption = "Поддерживаются CBZ, CBR, CB7, CBT и PDF."),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 0.5f, Scene.SUNSET, caption = "Место, где ты остановился, запоминается."),
            Panel(0.04f, 0.58f, 0.92f, 0.918f, Scene.DAY, hero = true, balloon = "Закрыл — открыл — и снова на той же странице."),
        ),
        listOf(
            Panel(0.04f, 0.04f, 0.92f, 1.458f, Scene.BURST, shout = "КОНЕЦ", hero = true,
                caption = "Приятного чтения!"),
        ),
    )

    override val pageCount get() = pages.size

    override fun loadPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val panels = pages.getOrNull(index) ?: return null
        val (width, height) = PageDecoder.fit(1000, (1000 * PAGE_HEIGHT).toInt(), maxWidth, maxHeight)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(251, 247, 238))
        val unit = width.toFloat()
        val random = Random(index * 7919 + 17)
        panels.forEach { drawPanel(canvas, unit, it, random) }

        val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 60, 60)
            textSize = unit * 0.022f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${index + 1}", unit * 0.5f, height - unit * 0.012f, number)
        return bitmap
    }

    override fun close() = Unit

    private fun drawPanel(canvas: Canvas, unit: Float, panel: Panel, random: Random) {
        val rect = RectF(panel.x * unit, panel.y * unit, (panel.x + panel.w) * unit, (panel.y + panel.h) * unit)
        canvas.withClip(rect) {
            drawScene(this, rect, panel.scene, random)
            drawHalftone(this, rect, unit)
            if (panel.hero) drawHero(this, rect, unit)
            panel.shout?.let { drawShout(this, rect, unit, it) }
        }

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.008f
            color = Color.rgb(20, 20, 24)
        }
        canvas.drawRect(rect, border)

        panel.caption?.let { drawCaption(canvas, rect, unit, it) }
        panel.balloon?.let { drawBalloon(canvas, rect, unit, it) }
    }

    private fun drawScene(canvas: Canvas, r: RectF, scene: Scene, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun gradient(top: Int, bottom: Int) {
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(r, paint)
            paint.shader = null
        }
        when (scene) {
            Scene.DAY -> {
                gradient(Color.rgb(79, 195, 247), Color.rgb(225, 245, 254))
                paint.color = Color.rgb(255, 213, 79)
                canvas.drawCircle(r.left + r.width() * 0.8f, r.top + r.height() * 0.25f, r.width() * 0.09f, paint)
                paint.color = Color.WHITE
                repeat(3) {
                    val cx = r.left + r.width() * random.nextFloat()
                    val cy = r.top + r.height() * (0.1f + 0.3f * random.nextFloat())
                    val s = r.width() * 0.05f
                    canvas.drawOval(cx - s * 2, cy - s, cx + s * 2, cy + s, paint)
                    canvas.drawCircle(cx - s * 0.6f, cy - s * 0.7f, s, paint)
                }
                hills(canvas, r, Color.rgb(102, 187, 106), 0.72f, random)
                hills(canvas, r, Color.rgb(56, 142, 60), 0.84f, random)
            }
            Scene.NIGHT -> {
                gradient(Color.rgb(26, 35, 126), Color.rgb(74, 20, 140))
                paint.color = Color.WHITE
                repeat(40) {
                    canvas.drawCircle(
                        r.left + r.width() * random.nextFloat(), r.top + r.height() * 0.6f * random.nextFloat(),
                        r.width() * 0.004f * (1 + random.nextFloat()), paint,
                    )
                }
                paint.color = Color.rgb(255, 245, 200)
                canvas.drawCircle(r.left + r.width() * 0.18f, r.top + r.height() * 0.22f, r.width() * 0.07f, paint)
                var x = r.left
                while (x < r.right) {
                    val w = r.width() * (0.08f + 0.1f * random.nextFloat())
                    val top = r.top + r.height() * (0.35f + 0.35f * random.nextFloat())
                    paint.color = Color.rgb(13, 16, 51)
                    canvas.drawRect(x, top, x + w, r.bottom, paint)
                    paint.color = Color.rgb(255, 235, 59)
                    val step = r.width() * 0.025f
                    var wy = top + step
                    while (wy < r.bottom - step) {
                        var wx = x + step * 0.6f
                        while (wx < x + w - step) {
                            if (random.nextFloat() < 0.45f) canvas.drawRect(wx, wy, wx + step * 0.5f, wy + step * 0.6f, paint)
                            wx += step
                        }
                        wy += step * 1.3f
                    }
                    x += w + r.width() * 0.01f
                }
            }
            Scene.SUNSET -> {
                gradient(Color.rgb(255, 112, 67), Color.rgb(255, 202, 40))
                paint.color = Color.rgb(255, 241, 118)
                canvas.drawCircle(r.centerX(), r.top + r.height() * 0.62f, r.width() * 0.18f, paint)
                paint.color = Color.rgb(2, 119, 189)
                canvas.drawRect(r.left, r.top + r.height() * 0.62f, r.right, r.bottom, paint)
                paint.color = Color.argb(140, 255, 255, 255)
                paint.strokeWidth = r.width() * 0.006f
                repeat(9) {
                    val y = r.top + r.height() * (0.66f + 0.33f * random.nextFloat())
                    val cx = r.left + r.width() * random.nextFloat()
                    canvas.drawLine(cx - r.width() * 0.08f, y, cx + r.width() * 0.08f, y, paint)
                }
            }
            Scene.BURST -> {
                gradient(Color.rgb(255, 179, 0), Color.rgb(255, 87, 34))
                paint.color = Color.rgb(229, 57, 53)
                val cx = r.centerX()
                val cy = r.top + r.height() * 0.42f
                val radius = r.width() + r.height()
                val rays = 22
                for (i in 0 until rays step 2) {
                    val a0 = (i * 2 * Math.PI / rays).toFloat()
                    val a1 = ((i + 1) * 2 * Math.PI / rays).toFloat()
                    val path = Path().apply {
                        moveTo(cx, cy)
                        lineTo(cx + cos(a0) * radius, cy + sin(a0) * radius)
                        lineTo(cx + cos(a1) * radius, cy + sin(a1) * radius)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            }
            Scene.SPACE -> {
                gradient(Color.rgb(11, 15, 42), Color.rgb(40, 20, 70))
                paint.color = Color.WHITE
                repeat(60) {
                    canvas.drawCircle(
                        r.left + r.width() * random.nextFloat(), r.top + r.height() * random.nextFloat(),
                        r.width() * 0.003f * (1 + 2 * random.nextFloat()), paint,
                    )
                }
                val px = r.left + r.width() * 0.6f
                val py = r.top + r.height() * 0.4f
                val pr = min(r.width(), r.height()) * 0.22f
                paint.shader = RadialGradient(px - pr * 0.4f, py - pr * 0.4f, pr * 1.4f,
                    Color.rgb(255, 138, 101), Color.rgb(121, 40, 60), Shader.TileMode.CLAMP)
                canvas.drawCircle(px, py, pr, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = pr * 0.08f
                paint.color = Color.rgb(255, 224, 178)
                canvas.drawOval(px - pr * 1.7f, py - pr * 0.35f, px + pr * 1.7f, py + pr * 0.35f, paint)
                paint.style = Paint.Style.FILL
            }
            Scene.FOREST -> {
                gradient(Color.rgb(178, 235, 242), Color.rgb(200, 230, 201))
                for (layer in 0..1) {
                    paint.color = if (layer == 0) Color.rgb(67, 160, 71) else Color.rgb(27, 94, 32)
                    var x = r.left - r.width() * 0.05f
                    while (x < r.right) {
                        val w = r.width() * (0.12f + 0.08f * random.nextFloat())
                        val top = r.top + r.height() * (0.25f + 0.25f * random.nextFloat() + layer * 0.15f)
                        val path = Path().apply {
                            moveTo(x, r.bottom)
                            lineTo(x + w / 2, top)
                            lineTo(x + w, r.bottom)
                            close()
                        }
                        canvas.drawPath(path, paint)
                        x += w * 0.7f
                    }
                }
            }
        }
    }

    private fun hills(canvas: Canvas, r: RectF, color: Int, level: Float, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path()
        path.moveTo(r.left, r.bottom)
        val y = r.top + r.height() * level
        path.lineTo(r.left, y)
        val bumps = 3
        for (i in 0 until bumps) {
            val x0 = r.left + r.width() * i / bumps
            val x1 = r.left + r.width() * (i + 1) / bumps
            path.quadTo((x0 + x1) / 2, y - r.height() * (0.08f + 0.12f * random.nextFloat()), x1, y)
        }
        path.lineTo(r.right, r.bottom)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawHalftone(canvas: Canvas, r: RectF, unit: Float) {
        val cell = (unit * 0.012f).toInt().coerceAtLeast(4)
        val tile = createBitmap(cell, cell)
        Canvas(tile).drawCircle(cell / 2f, cell / 2f, cell * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 0, 0, 0)
        })
        val paint = Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        canvas.drawRect(r, paint)
        tile.recycle()
    }

    private fun drawHero(canvas: Canvas, r: RectF, unit: Float) {
        val size = min(r.width(), r.height()) * 0.42f
        val cx = r.left + r.width() * 0.3f
        val baseY = r.bottom
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.006f
            color = Color.rgb(20, 20, 24)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Body (a red hoodie) and head.
        val body = RectF(cx - size * 0.42f, baseY - size * 0.75f, cx + size * 0.42f, baseY + size * 0.2f)
        fill.color = Color.rgb(229, 57, 53)
        canvas.drawRoundRect(body, size * 0.3f, size * 0.3f, fill)
        canvas.drawRoundRect(body, size * 0.3f, size * 0.3f, ink)
        val headY = baseY - size * 1.05f
        val headR = size * 0.38f
        fill.color = Color.rgb(255, 224, 178)
        canvas.drawCircle(cx, headY, headR, fill)
        canvas.drawCircle(cx, headY, headR, ink)

        // Hair.
        fill.color = Color.rgb(62, 39, 35)
        canvas.drawArc(cx - headR, headY - headR, cx + headR, headY + headR, 190f, 160f, true, fill)

        // Eyes with highlights and a smile.
        for (side in listOf(-1, 1)) {
            val ex = cx + side * headR * 0.38f
            val ey = headY + headR * 0.05f
            fill.color = Color.WHITE
            canvas.drawOval(ex - headR * 0.17f, ey - headR * 0.22f, ex + headR * 0.17f, ey + headR * 0.22f, fill)
            fill.color = Color.rgb(20, 20, 24)
            canvas.drawCircle(ex + headR * 0.03f, ey + headR * 0.04f, headR * 0.11f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(ex + headR * 0.07f, ey - headR * 0.03f, headR * 0.04f, fill)
        }
        canvas.drawArc(cx - headR * 0.3f, headY + headR * 0.1f, cx + headR * 0.3f, headY + headR * 0.55f,
            20f, 140f, false, ink)
        fill.color = Color.argb(90, 244, 67, 54)
        canvas.drawCircle(cx - headR * 0.62f, headY + headR * 0.32f, headR * 0.13f, fill)
        canvas.drawCircle(cx + headR * 0.62f, headY + headR * 0.32f, headR * 0.13f, fill)
    }

    private fun drawShout(canvas: Canvas, r: RectF, unit: Float, text: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = unit * 0.2f
            textSkewX = -0.15f
        }
        val maxWidth = r.width() * 0.86f
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
        val x = r.centerX()
        val y = r.top + r.height() * 0.42f + paint.textSize * 0.35f
        canvas.withRotation(-6f, x, y) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = paint.textSize * 0.12f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.rgb(20, 20, 24)
            drawText(text, x + paint.textSize * 0.04f, y + paint.textSize * 0.06f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(255, 235, 59)
            drawText(text, x, y, paint)
        }
    }

    private fun drawCaption(canvas: Canvas, r: RectF, unit: Float, text: String) {
        val textPaint = lettering(unit * 0.034f)
        val maxWidth = (r.width() * 0.8f).toInt()
        val layout = layout(text.uppercase(), textPaint, maxWidth)
        val pad = unit * 0.015f
        val box = RectF(r.left + unit * 0.02f, r.top + unit * 0.02f,
            r.left + unit * 0.02f + layout.width + pad * 2, r.top + unit * 0.02f + layout.height + pad * 2)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 241, 118) }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.005f
            color = Color.rgb(20, 20, 24)
        }
        canvas.drawRect(box, fill)
        canvas.drawRect(box, ink)
        canvas.withTranslation(box.left + pad, box.top + pad) { layout.draw(this) }
    }

    private fun drawBalloon(canvas: Canvas, r: RectF, unit: Float, text: String) {
        val textPaint = lettering(unit * 0.036f)
        val maxWidth = (r.width() * 0.5f).toInt().coerceAtLeast((unit * 0.3f).toInt())
        val layout = layout(text.uppercase(), textPaint, maxWidth)
        val padX = unit * 0.05f
        val padY = unit * 0.035f
        val right = r.right - unit * 0.03f
        val top = r.top + r.height() * 0.08f
        val oval = RectF(right - layout.width - padX * 2, top, right, top + layout.height + padY * 2)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = unit * 0.005f
            color = Color.rgb(20, 20, 24)
            strokeJoin = Paint.Join.ROUND
        }
        // Tail pointing down-left towards the hero.
        val tail = Path().apply {
            moveTo(oval.left + oval.width() * 0.25f, oval.bottom - oval.height() * 0.2f)
            lineTo(oval.left - unit * 0.02f, oval.bottom + unit * 0.09f)
            lineTo(oval.left + oval.width() * 0.42f, oval.bottom - oval.height() * 0.1f)
            close()
        }
        canvas.drawPath(tail, fill)
        canvas.drawPath(tail, ink)
        canvas.drawOval(oval, fill)
        canvas.drawOval(oval, ink)
        // Cover the seam between tail and balloon.
        canvas.drawOval(RectF(oval.left + ink.strokeWidth, oval.top + ink.strokeWidth,
            oval.right - ink.strokeWidth, oval.bottom - ink.strokeWidth), fill)

        canvas.withTranslation(oval.centerX() - layout.width / 2f, oval.centerY() - layout.height / 2f) {
            layout.draw(this)
        }
    }

    private fun lettering(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 24)
        textSize = size
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private fun layout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout {
        val natural = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        // Shrink the box to the longest line so balloons hug their text.
        var widest = 0f
        for (i in 0 until natural.lineCount) widest = maxOf(widest, natural.getLineWidth(i))
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widest.toInt() + 2)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    companion object {
        /** Page height for a width of 1, the classic US comic proportion. */
        const val PAGE_HEIGHT = 1.538f
    }
}
