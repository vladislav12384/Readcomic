package com.readcomic.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import com.readcomic.shelf.Binding
import com.readcomic.shelf.ComicShape
import com.readcomic.shelf.ShelfController
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** How the plain grid of covers looks. */
enum class GridStyle(val label: String) {
    DARK("Тёмная"),
    LIGHT("Светлая"),
    VINTAGE("Винтаж"),
    COMIC("Комикс"),
}

/** The look of the plain grid, and what to call when the reader changes it. */
class GridLook(
    val style: GridStyle = GridStyle.DARK,
    /** Covers in the shapes of the issues: their own proportions, and their thickness seen a little from aside. */
    val byShape: Boolean = false,
    val onStyleChange: (GridStyle) -> Unit = {},
    val onByShapeChange: (Boolean) -> Unit = {},
)

/** Colours of the grid in each look; the dark one is the app's own. */
internal object GridPalette {
    private val Light = lightColorScheme(
        primary = Color(0xFF9A5A1C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF2D5AE),
        onPrimaryContainer = Color(0xFF3A2200),
        secondary = Color(0xFF7A6150),
        background = Color(0xFFF6F1E8),
        onBackground = Color(0xFF2A231D),
        surface = Color(0xFFF6F1E8),
        onSurface = Color(0xFF2A231D),
        surfaceVariant = Color(0xFFE6DCCD),
        onSurfaceVariant = Color(0xFF6B5E52),
        surfaceContainer = Color(0xFFFFFBF5),
        surfaceContainerHigh = Color(0xFFFFFBF5),
    )

    private val Vintage = lightColorScheme(
        primary = Color(0xFF7A2E1A),
        onPrimary = Color(0xFFF3E9D2),
        primaryContainer = Color(0xFF6B4A2E),
        onPrimaryContainer = Color(0xFFF3E9D2),
        secondary = Color(0xFF6E5640),
        background = Color(0xFFE9DDC2),
        onBackground = Color(0xFF3E2C1C),
        surface = Color(0xFFE9DDC2),
        onSurface = Color(0xFF3E2C1C),
        surfaceVariant = Color(0xFFD8C7A3),
        onSurfaceVariant = Color(0xFF6E5640),
        surfaceContainer = Color(0xFFF1E7D0),
        surfaceContainerHigh = Color(0xFFF1E7D0),
        outline = Color(0xFF8A7154),
    )

    private val Comic = lightColorScheme(
        primary = Color(0xFFE2302A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF151515),
        onPrimaryContainer = Color(0xFFFFD43B),
        secondary = Color(0xFF1F4FB8),
        background = Color(0xFFFFD43B),
        onBackground = Color(0xFF111111),
        surface = Color(0xFFFFD43B),
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFFFF1B0),
        onSurfaceVariant = Color(0xFF3A3220),
        surfaceContainer = Color(0xFFFFFBEA),
        surfaceContainerHigh = Color(0xFFFFFBEA),
        outline = Color(0xFF111111),
    )

    /** The colour scheme of [style]; null keeps the app's dark one. */
    fun colors(style: GridStyle): ColorScheme? = when (style) {
        GridStyle.DARK -> null
        GridStyle.LIGHT -> Light
        GridStyle.VINTAGE -> Vintage
        GridStyle.COMIC -> Comic
    }

    /** The bar along the top in the comic look: red, as on the covers of old comics. */
    val ComicBar = Color(0xFFE2302A)

    /** Covers on yellowed paper look printed long ago. */
    val Sepia: ColorFilter by lazy {
        // Two thirds the picture itself, one third its sepia print.
        val keep = 0.65f
        val tone = 1f - keep
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    keep + tone * 0.393f, tone * 0.769f, tone * 0.189f, 0f, 0f,
                    tone * 0.349f, keep + tone * 0.686f, tone * 0.168f, 0f, 0f,
                    tone * 0.272f, tone * 0.534f, keep + tone * 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
}

/** The theme of the grid in [style]: the app's own when dark. */
@Composable
internal fun GridTheme(style: GridStyle, content: @Composable () -> Unit) {
    val colors = GridPalette.colors(style)
    if (colors == null) content() else MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}

/**
 * Behind the grid: yellowed newsprint with its fibres and darker edges in the vintage look, a
 * halftone screen of dots in the comic one; nothing in the others.
 */
@Composable
internal fun Modifier.gridBackground(style: GridStyle): Modifier {
    val density = LocalDensity.current.density
    val tile = remember(style, density) {
        when (style) {
            GridStyle.VINTAGE -> grainTile(density)
            GridStyle.COMIC -> dotsTile(density)
            else -> null
        }
    } ?: return this
    val base = GridPalette.colors(style)?.background ?: Color.Black
    val brush = remember(tile) { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
    return drawBehind {
        drawRect(base)
        drawRect(brush)
        if (style == GridStyle.VINTAGE) {
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to Color(0x40553A1E),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = max(size.width, size.height) * 0.75f,
                ),
            )
        } else {
            // The dots fade towards the bottom, under the comics.
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to base.copy(alpha = 0.55f)))
        }
    }
}

/** A tile of halftone dots, staggered as a print screen is. */
private fun dotsTile(density: Float): ImageBitmap {
    val pitch = (9f * density).roundToInt().coerceAtLeast(6)
    val bitmap = createBitmap(pitch * 2, pitch * 2)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(110, 236, 128, 0) }
    val r = pitch * 0.27f
    val p = pitch.toFloat()
    for ((x, y) in listOf(0f to 0f, 2 * p to 0f, 0f to 2 * p, 2 * p to 2 * p, p to p)) canvas.drawCircle(x, y, r, paint)
    return bitmap.asImageBitmap()
}

/** A tile of newsprint: specks and short fibres, faint. */
private fun grainTile(density: Float): ImageBitmap {
    val size = (96f * density).roundToInt().coerceIn(96, 512)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val random = Random(7)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(size * size / 90) {
        paint.color = android.graphics.Color.argb(random.nextInt(10, 34), 90, 62, 30)
        canvas.drawCircle(random.nextFloat() * size, random.nextFloat() * size, density * (0.3f + random.nextFloat() * 0.6f), paint)
    }
    paint.strokeWidth = density * 0.5f
    repeat(size / 6) {
        paint.color = android.graphics.Color.argb(random.nextInt(14, 30), 110, 80, 45)
        val x = random.nextFloat() * size
        val y = random.nextFloat() * size
        val length = density * (3f + random.nextFloat() * 6f)
        val angle = random.nextFloat() * 6.283f
        canvas.drawLine(x, y, x + kotlin.math.cos(angle) * length, y + kotlin.math.sin(angle) * length, paint)
    }
    return bitmap.asImageBitmap()
}

/**
 * The geometry of an issue drawn in its own shape: the front cover in its own proportions
 * standing on the bottom of the cell, and its thickness seen a little from above and the right
 * (the fore-edge and the top of the pages), as thick as the comic is for its height.
 */
internal class IssueLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** How far the back of the comic shows to the right of and above the front. */
    val depthX: Float,
    val depthY: Float,
) {
    val right get() = left + width
    val bottom get() = top + height

    companion object {
        /** Depth drawn per unit of thickness: a cabinet view, turned 45 degrees. */
        private const val OBLIQUE = 0.55f * 0.7071f

        /**
         * Fits a cover of [aspect] (width / height) of a comic [thickness] meters thick into a
         * [cellWidth] x [cellHeight] cell; at least [minDepth] of thickness shows.
         */
        fun fit(cellWidth: Float, cellHeight: Float, aspect: Float, thickness: Float, minDepth: Float): IssueLayout {
            val a = aspect.coerceIn(0.3f, 3f)
            val depth = max(minDepth, cellHeight * thickness / ShelfController.COMIC_HEIGHT * OBLIQUE)
            val width = min(cellWidth - depth, (cellHeight - depth) * a)
            val height = width / a
            return IssueLayout((cellWidth - width - depth) / 2f, cellHeight - height, width, height, depth, depth)
        }
    }
}

/** The cover of an issue in its own shape (see [IssueLayout]), with the look's finish. */
@Composable
internal fun IssueCover(
    cover: ImageBitmap?,
    pageCount: Int,
    title: String,
    style: GridStyle,
    /** Reading progress 0..1, null when not started. */
    progress: Float?,
    progressColor: Color,
    blankColor: Color,
    blankInk: Color,
    modifier: Modifier = Modifier,
) {
    val binding = ComicShape.binding(pageCount)
    val thickness = ComicShape.thickness(pageCount)
    // The edges of the boards or of the cover wrapped round the pages: the cover's own colour.
    val edge = remember(cover) { cover?.let(::edgeColor) ?: Color(0xFF5A4A3E) }
    val measurer = rememberTextMeasurer()
    val initial = title.take(1).uppercase()
    Canvas(modifier.fillMaxWidth().aspectRatio(0.66f)) {
        val aspect = cover?.let { it.width.toFloat() / it.height } ?: 0.66f
        val box = IssueLayout.fit(size.width, size.height, aspect, thickness, minDepth = 2.dp.toPx())
        val dx = box.depthX
        val dy = box.depthY
        val pages = when (style) {
            GridStyle.VINTAGE -> Color(0xFFE2D2AE)
            else -> Color(0xFFEFE8D8)
        }
        val ink = Color(0xFF111111)

        // What it stands on: a soft shadow, or in the comic look a hard black one.
        val outline = Path().apply {
            moveTo(box.left, box.top)
            lineTo(box.left + dx, box.top - dy)
            lineTo(box.right + dx, box.top - dy)
            lineTo(box.right + dx, box.bottom - dy)
            lineTo(box.right, box.bottom)
            lineTo(box.left, box.bottom)
            close()
        }
        if (style == GridStyle.COMIC) {
            val shift = 4.dp.toPx()
            translateBy(shift, shift) { drawPath(outline, ink) }
        } else {
            val dark = style == GridStyle.DARK
            drawOval(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = if (dark) 0.55f else 0.28f),
                    1f to Color.Transparent,
                    center = Offset(box.left + (box.width + dx) / 2f, box.bottom - dy / 2f),
                    radius = (box.width + dx) * 0.62f,
                ),
                topLeft = Offset(box.left - box.width * 0.1f, box.bottom - dy - 4.dp.toPx()),
                size = Size((box.width + dx) * 1.2f, dy + 10.dp.toPx()),
            )
        }

        // The fore-edge: the sheets one behind another, between the two covers.
        val side = Path().apply {
            moveTo(box.right, box.top)
            lineTo(box.right + dx, box.top - dy)
            lineTo(box.right + dx, box.bottom - dy)
            lineTo(box.right, box.bottom)
            close()
        }
        val topFace = Path().apply {
            moveTo(box.left, box.top)
            lineTo(box.left + dx, box.top - dy)
            lineTo(box.right + dx, box.top - dy)
            lineTo(box.right, box.top)
            close()
        }
        drawPath(side, lerpColor(pages, Color.Black, 0.18f))
        drawPath(topFace, lerpColor(pages, Color.White, 0.08f))
        val sheets = (dx / 2.5f).toInt().coerceIn(0, 14)
        for (i in 1..sheets) {
            val f = i / (sheets + 1f)
            drawLine(Color.Black.copy(alpha = 0.10f), Offset(box.right + dx * f, box.top - dy * f), Offset(box.right + dx * f, box.bottom - dy * f), 1f)
            drawLine(Color.Black.copy(alpha = 0.07f), Offset(box.left + dx * f, box.top - dy * f), Offset(box.right + dx * f, box.top - dy * f), 1f)
        }
        // The covers at the front and back of the block: boards on a hardcover, a thin line otherwise.
        val board = if (binding == Binding.HARDCOVER) (ComicShape.BOARD / thickness).coerceIn(0.06f, 0.2f) else 0f
        for (f in listOf(0f, 1f - board)) {
            if (board > 0f) {
                val a = f
                val b = f + board
                drawPath(
                    Path().apply {
                        moveTo(box.right + dx * a, box.top - dy * a)
                        lineTo(box.right + dx * b, box.top - dy * b)
                        lineTo(box.right + dx * b, box.bottom - dy * b)
                        lineTo(box.right + dx * a, box.bottom - dy * a)
                        close()
                    },
                    lerpColor(edge, Color.Black, 0.25f),
                )
                drawPath(
                    Path().apply {
                        moveTo(box.left + dx * a, box.top - dy * a)
                        lineTo(box.left + dx * b, box.top - dy * b)
                        lineTo(box.right + dx * b, box.top - dy * b)
                        lineTo(box.right + dx * a, box.top - dy * a)
                        close()
                    },
                    lerpColor(edge, Color.White, 0.05f),
                )
            }
        }
        if (board == 0f && dx > 3.dp.toPx()) {
            drawLine(edge, Offset(box.right + dx, box.top - dy), Offset(box.right + dx, box.bottom - dy), 1.5f)
            drawLine(edge, Offset(box.left + dx, box.top - dy), Offset(box.right + dx, box.top - dy), 1.5f)
        }

        // The front cover.
        if (cover != null) {
            drawImage(
                cover,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(cover.width, cover.height),
                dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                dstSize = IntSize(box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1)),
                colorFilter = if (style == GridStyle.VINTAGE) GridPalette.Sepia else null,
            )
        } else {
            drawRect(blankColor, Offset(box.left, box.top), Size(box.width, box.height))
            val text = measurer.measure(initial, TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = blankInk))
            drawText(text, topLeft = Offset(box.left + (box.width - text.size.width) / 2f, box.top + (box.height - text.size.height) / 2f))
        }
        clipRect(box.left, box.top, box.right, box.bottom) {
            // Shading along the spine, so the cover reads as a bound comic.
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.06f to Color.Transparent,
                    0.9f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.12f),
                    startX = box.left,
                    endX = box.right,
                ),
            )
            if (progress != null) {
                val bar = 3.dp.toPx()
                drawRect(Color.Black.copy(alpha = 0.4f), Offset(box.left, box.bottom - bar), Size(box.width, bar))
                drawRect(progressColor, Offset(box.left, box.bottom - bar), Size(box.width * progress.coerceIn(0f, 1f), bar))
            }
        }
        if (style == GridStyle.COMIC) {
            val stroke = Stroke(width = 2.dp.toPx())
            drawPath(side, ink, style = stroke)
            drawPath(topFace, ink, style = stroke)
            drawRect(ink, Offset(box.left, box.top), Size(box.width, box.height), style = stroke)
        } else if (style == GridStyle.VINTAGE) {
            drawRect(Color(0x663E2C1C), Offset(box.left, box.top), Size(box.width, box.height), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

private inline fun DrawScope.translateBy(x: Float, y: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(x, y)
    block()
    drawContext.transform.translate(-x, -y)
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** The average colour of the right edge of a cover: what its boards or its wrap look like from aside. */
private fun edgeColor(image: ImageBitmap): Color {
    val h = image.height
    if (image.width < 2 || h < 1) return Color(0xFF5A4A3E)
    val map = image.toPixelMap(startX = image.width - 2, startY = 0, width = 1, height = h)
    var r = 0f
    var g = 0f
    var b = 0f
    val step = max(1, h / 64)
    var n = 0
    var y = 0
    while (y < h) {
        val c = map[0, y]
        r += c.red
        g += c.green
        b += c.blue
        n++
        y += step
    }
    return Color(r / n, g / n, b / n)
}
