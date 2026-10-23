package com.readcomic.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.readcomic.data.ComicEntry
import kotlin.math.max
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
    /** Covers in the shapes of the issues: their own proportions, standing on one line. */
    val byShape: Boolean = false,
    val onStyleChange: (GridStyle) -> Unit = {},
    val onByShapeChange: (Boolean) -> Unit = {},
)

/** Colours of the grid in each look. */
internal object GridPalette {
    // Cool graphite with white ink; the amber of the app stays only as its accent.
    private val Graphite = darkColorScheme(
        primary = Color(0xFFFFB55A),
        onPrimary = Color(0xFF3A2200),
        primaryContainer = Color(0xFF5A3A12),
        onPrimaryContainer = Color(0xFFFFDDB6),
        secondary = Color(0xFFB9C0CC),
        background = Color(0xFF0D0F13),
        onBackground = Color(0xFFF2F4F7),
        surface = Color(0xFF15181D),
        onSurface = Color(0xFFF2F4F7),
        surfaceVariant = Color(0xFF262A31),
        onSurfaceVariant = Color(0xFF9EA5B0),
        surfaceContainer = Color(0xFF1C1F25),
        surfaceContainerHigh = Color(0xFF23272E),
        outline = Color(0xFF4A505A),
        error = Color(0xFFFFB4AB),
    )

    private val Light = lightColorScheme(
        primary = Color(0xFFA0632A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF3DDBF),
        onPrimaryContainer = Color(0xFF3A2200),
        secondary = Color(0xFF7A6150),
        background = Color(0xFFF6F1E8),
        onBackground = Color(0xFF2A231D),
        surface = Color(0xFFF6F1E8),
        onSurface = Color(0xFF2A231D),
        surfaceVariant = Color(0xFFE9E1D4),
        onSurfaceVariant = Color(0xFF75685C),
        surfaceContainer = Color(0xFFFFFCF7),
        surfaceContainerHigh = Color(0xFFFFFCF7),
    )

    private val Vintage = lightColorScheme(
        primary = Color(0xFF7A2E1A),
        onPrimary = Color(0xFFF5ECD8),
        primaryContainer = Color(0xFF5E4029),
        onPrimaryContainer = Color(0xFFF5ECD8),
        secondary = Color(0xFF6E5640),
        background = Color(0xFFEBDFC4),
        onBackground = Color(0xFF3B2A1B),
        surface = Color(0xFFEBDFC4),
        onSurface = Color(0xFF3B2A1B),
        surfaceVariant = Color(0xFFDCCBA8),
        onSurfaceVariant = Color(0xFF735A43),
        surfaceContainer = Color(0xFFF4EBD8),
        surfaceContainerHigh = Color(0xFFF4EBD8),
        outline = Color(0xFF8A7154),
    )

    private val Comic = lightColorScheme(
        primary = Color(0xFFD8352A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF161616),
        onPrimaryContainer = Color(0xFFFFD84D),
        secondary = Color(0xFF1F4FB8),
        background = Color(0xFFFFDB66),
        onBackground = Color(0xFF141414),
        surface = Color(0xFFFFDB66),
        onSurface = Color(0xFF141414),
        surfaceVariant = Color(0xFFFFF0B8),
        onSurfaceVariant = Color(0xFF4A3F22),
        surfaceContainer = Color(0xFFFFFAE6),
        surfaceContainerHigh = Color(0xFFFFFAE6),
        outline = Color(0xFF141414),
    )

    /** The colour scheme of [style]. */
    fun colors(style: GridStyle): ColorScheme = when (style) {
        GridStyle.DARK -> Graphite
        GridStyle.LIGHT -> Light
        GridStyle.VINTAGE -> Vintage
        GridStyle.COMIC -> Comic
    }

    /** The background, top to bottom, and the soft light at its top. */
    fun ground(style: GridStyle): Triple<Color, Color, Color> = when (style) {
        // Cool deep graphite, a faint light from above.
        GridStyle.DARK -> Triple(Color(0xFF1E222A), Color(0xFF0D0F13), Color(0x0FFFFFFF))
        GridStyle.LIGHT -> Triple(Color(0xFFFCF9F4), Color(0xFFEEE7DB), Color(0x99FFFFFF))
        GridStyle.VINTAGE -> Triple(Color(0xFFF1E7D1), Color(0xFFDFCFAD), Color(0x66FFF8E6))
        GridStyle.COMIC -> Triple(Color(0xFFFFE58C), Color(0xFFFFCF45), Color(0x66FFFFFF))
    }

    /** The bar along the top in the comic look: red, as on the covers of old comics. */
    val ComicBar = Color(0xFFD8352A)

    /** The header and the add button in each look. */
    class Chrome(
        val ink: Color,
        val muted: Color,
        /** The round buttons: their fill, and their rim (transparent for none). */
        val button: Color,
        val rim: Color,
        val fab: Color,
        val fabInk: Color,
        /** Behind the header itself (the red bar of the comic look), transparent elsewhere. */
        val bar: Color,
    )

    fun chrome(style: GridStyle): Chrome = when (style) {
        GridStyle.DARK -> Chrome(Color(0xFFF2F4F7), Color(0x80F2F4F7), Color(0x17FFFFFF), Color(0x0FFFFFFF), Color(0xFF2B3039), Color(0xFFF2F4F7), Color.Transparent)
        GridStyle.LIGHT -> Chrome(Color(0xFF2A231D), Color(0x992A231D), Color(0x0D000000), Color.Transparent, Color(0xFF2A231D), Color(0xFFFCF9F4), Color.Transparent)
        GridStyle.VINTAGE -> Chrome(Color(0xFF3B2A1B), Color(0xA63B2A1B), Color.Transparent, Color(0x998A7154), Color(0xFF5E4029), Color(0xFFF5ECD8), Color.Transparent)
        GridStyle.COMIC -> Chrome(Color.White, Color(0xD9FFFFFF), Color(0x26000000), Color.Transparent, Color(0xFF161616), Color(0xFFFFD84D), ComicBar)
    }

    /** Covers on yellowed paper look printed long ago, a little. */
    val Sepia: ColorFilter by lazy {
        val keep = 0.78f
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

    private val Ink = Color(0xFF141414)
    private val Mat = Color(0xFFF8F1E0)
    private val PrintLine = Color(0x996E5640)

    /**
     * A cover as a tile of the grid in [style], as big as [modifier] makes it (the picture cut to
     * fill it): square-cornered as a printed comic, lifted off the page by a soft shadow, with a
     * fine light along its edge; on a paper mat in the vintage look, inked and with a hard
     * shadow in the comic one.
     */
    @Composable
    fun Cover(entry: ComicEntry, cover: ImageBitmap?, style: GridStyle, modifier: Modifier) {
        val shape = RectangleShape
        val lifted = when (style) {
            GridStyle.DARK -> modifier.shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            GridStyle.LIGHT -> modifier.shadow(10.dp, shape, ambientColor = Color(0xFF6B5440), spotColor = Color(0xFF6B5440))
            // A print pasted on a mat, the mat on the page.
            GridStyle.VINTAGE -> modifier
                .shadow(5.dp, shape, ambientColor = Color(0xFF5A4028), spotColor = Color(0xFF5A4028))
                .background(Mat, shape)
                .padding(5.dp)
                .border(0.75.dp, PrintLine)
                .padding(1.5.dp)
            // Inked: a black outline and a hard black shadow, as a panel of a comic.
            GridStyle.COMIC -> modifier
                .drawBehind { drawRect(Ink, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = size) }
                .border(2.dp, Ink, shape)
                .padding(2.dp)
        }
        Box(lifted.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (cover != null) {
                Image(
                    cover,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (style == GridStyle.VINTAGE) Sepia else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    entry.title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // The fold of the spine, softly, so the cover reads as a bound comic.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.22f),
                            0.05f to Color.Transparent,
                            0.92f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.08f),
                        ),
                    ),
            )
            if (style == GridStyle.DARK || style == GridStyle.LIGHT) {
                // The edge of the pages on the right, catching a little light: a book, not a picture.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(1.5.dp)
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.08f)))),
                )
                // A fine light along the top edge, as glossy card catches it.
                val rim = if (style == GridStyle.DARK) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.5f)
                Box(Modifier.fillMaxSize().border(1.dp, Brush.verticalGradient(listOf(rim, Color.Transparent), endY = 220f), shape))
            }
        }
    }

    /** The cover in its own proportions, standing on the bottom of a tile of the grid. */
    @Composable
    fun ShapedCover(entry: ComicEntry, cover: ImageBitmap?, style: GridStyle) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(TILE_ASPECT), contentAlignment = Alignment.BottomCenter) {
            val aspect = cover?.let { it.width.toFloat() / it.height }?.coerceIn(0.3f, 3f) ?: TILE_ASPECT
            val width = if (aspect < TILE_ASPECT) maxHeight * aspect else maxWidth
            val height = if (aspect < TILE_ASPECT) maxHeight else maxWidth / aspect
            Cover(entry, cover, style, Modifier.size(width, height))
        }
    }

    /** How far the comic has been read: a slim line under its cover. */
    @Composable
    fun Progress(progress: Float, style: GridStyle) {
        val track = when (style) {
            GridStyle.DARK -> Color.White.copy(alpha = 0.10f)
            GridStyle.COMIC -> Color(0x33141414)
            else -> Color(0x1F3B2A1B)
        }
        Box(
            Modifier
                .padding(top = 7.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(track),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
        }
    }

    const val TILE_ASPECT = 0.66f
}

/** The theme of the grid in [style]. */
@Composable
internal fun GridTheme(style: GridStyle, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GridPalette.colors(style), typography = MaterialTheme.typography, content = content)
}

/**
 * Behind the grid: a soft gradient from top to bottom with a little light at the top in every
 * look; fine fibres of old paper and darker edges in the vintage one, a light halftone screen
 * fading down the page in the comic one.
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
    }
    val pattern = remember(tile) { tile?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    val (top, bottom, light) = GridPalette.ground(style)
    return drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        drawRect(
            Brush.radialGradient(
                0f to light,
                1f to Color.Transparent,
                center = Offset(size.width * 0.5f, -size.height * 0.05f),
                radius = max(size.width, size.height) * 0.7f,
            ),
        )
        if (pattern != null) {
            if (style == GridStyle.COMIC) {
                // The dots are strongest at the top and fade out down the page.
                drawRect(pattern, alpha = 0.55f)
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.75f to bottom.copy(alpha = 0.85f), 1f to bottom))
            } else {
                drawRect(pattern)
            }
        }
        if (style == GridStyle.VINTAGE) {
            drawRect(
                Brush.radialGradient(
                    0.6f to Color.Transparent,
                    1f to Color(0x33553A1E),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = max(size.width, size.height) * 0.75f,
                ),
            )
        }
    }
}

/** A tile of halftone dots, staggered as a print screen is. */
private fun dotsTile(density: Float): ImageBitmap {
    val pitch = (7f * density).roundToInt().coerceAtLeast(5)
    val bitmap = createBitmap(pitch * 2, pitch * 2)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(120, 240, 142, 20) }
    val r = pitch * 0.22f
    val p = pitch.toFloat()
    for ((x, y) in listOf(0f to 0f, 2 * p to 0f, 0f to 2 * p, 2 * p to 2 * p, p to p)) canvas.drawCircle(x, y, r, paint)
    return bitmap.asImageBitmap()
}

/** A tile of old paper: faint specks and short fibres, barely there. */
private fun grainTile(density: Float): ImageBitmap {
    val size = (128f * density).roundToInt().coerceIn(128, 512)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val random = Random(7)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(size * size / 260) {
        paint.color = android.graphics.Color.argb(random.nextInt(6, 18), 90, 62, 30)
        canvas.drawCircle(random.nextFloat() * size, random.nextFloat() * size, density * (0.25f + random.nextFloat() * 0.4f), paint)
    }
    paint.strokeWidth = density * 0.4f
    repeat(size / 10) {
        paint.color = android.graphics.Color.argb(random.nextInt(8, 18), 110, 80, 45)
        val x = random.nextFloat() * size
        val y = random.nextFloat() * size
        val length = density * (3f + random.nextFloat() * 5f)
        val angle = random.nextFloat() * 6.283f
        canvas.drawLine(x, y, x + kotlin.math.cos(angle) * length, y + kotlin.math.sin(angle) * length, paint)
    }
    return bitmap.asImageBitmap()
}
