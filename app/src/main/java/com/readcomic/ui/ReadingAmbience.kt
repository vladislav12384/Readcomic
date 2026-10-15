package com.readcomic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/**
 * Tables to read on. [kind] is the surface drawn by table.frag, [color] its overall colour (shown
 * before the first frame), [vignette] how dark its corners get.
 */
enum class ReadingTable(val label: String, val kind: Int, val color: Color, val vignette: Float) {
    GRAPHITE("Графит", 0, Color(0xFF1F1D1B), 0.55f),
    WALNUT("Орех", 2, Color(0xFF3B2516), 0.6f),
    OAK("Дуб", 4, Color(0xFF8F653C), 0.5f),
    LEATHER("Кожа", 5, Color(0xFF4A1511), 0.5f),
    FELT("Сукно", 6, Color(0xFF16402A), 0.6f),
    MARBLE("Мрамор", 7, Color(0xFF111113), 0.4f),
    LINEN("Лён", 3, Color(0xFFCAC1B0), 0.42f),
    BLACK("Чёрный", 1, Color(0xFF000000), 0f),
    ;

    companion object {
        /** The plain colours of earlier versions, by their index: dark, black, brown, light. */
        fun fromOldBackground(index: Int) = when (index) {
            1 -> BLACK
            2 -> WALNUT
            3 -> LINEN
            else -> GRAPHITE
        }
    }
}

enum class NightMode(val label: String) {
    OFF("Выкл"),
    ON("Вкл"),
    EVENING("Вечером"),
}

/** Reading at night: warm, dim light from a desk lamp. [warmth] and [brightness] are 0..1. */
data class NightSettings(
    val mode: NightMode = NightMode.OFF,
    val warmth: Float = 0.6f,
    val brightness: Float = 0.75f,
) {
    /** Whether it is on now: "evening" means from nine in the evening to seven in the morning. */
    fun activeAt(time: LocalTime): Boolean = when (mode) {
        NightMode.OFF -> false
        NightMode.ON -> true
        NightMode.EVENING -> time.hour >= EVENING_HOUR || time.hour < MORNING_HOUR
    }

    companion object {
        const val EVENING_HOUR = 21
        const val MORNING_HOUR = 7
    }
}

/** A small square of [table], to choose it. */
@Composable
fun TableSwatch(table: ReadingTable, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Canvas(
        Modifier
            .size(46.dp)
            .clip(shape)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
                shape = shape,
            )
            .clickable(onClickLabel = table.label, onClick = onClick),
    ) {
        drawTable(table)
    }
}

private fun DrawScope.drawTable(table: ReadingTable) {
    drawRect(table.color)
    val w = size.width
    val h = size.height
    when (table) {
        ReadingTable.WALNUT, ReadingTable.OAK -> {
            // A few lines of grain, wavering along the board.
            val light = if (table == ReadingTable.OAK) Color(0xFFC08C58) else Color(0xFF6B4527)
            for (i in 0 until 7) {
                val y = h * (0.08f + i * 0.14f)
                val path = Path().apply {
                    moveTo(0f, y)
                    cubicTo(w * 0.3f, y - h * 0.06f * (i % 3 - 1), w * 0.6f, y + h * 0.05f, w, y + h * 0.02f * (i % 2))
                }
                drawPath(path, light.copy(alpha = 0.35f + 0.1f * (i % 3)), style = Stroke(width = h * 0.035f))
            }
            if (table == ReadingTable.OAK) {
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1.5f)
            }
        }
        ReadingTable.LEATHER -> {
            drawRect(Brush.radialGradient(listOf(Color(0xFF6A2018), Color(0xFF3A0F0C)), center = Offset(w * 0.4f, h * 0.35f), radius = w))
            val inset = w * 0.16f
            drawRoundRect(
                Color(0xFFC9A877),
                topLeft = Offset(inset, inset),
                size = Size(w - 2 * inset, h - 2 * inset),
                cornerRadius = CornerRadius(w * 0.06f),
                style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.07f, w * 0.05f))),
            )
        }
        ReadingTable.FELT -> {
            drawRect(Brush.radialGradient(listOf(Color(0xFF22593A), Color(0xFF0E2E1C)), center = Offset(w * 0.45f, h * 0.4f), radius = w * 0.9f))
        }
        ReadingTable.MARBLE -> {
            val vein = Path().apply {
                moveTo(-w * 0.1f, h * 0.15f)
                cubicTo(w * 0.3f, h * 0.35f, w * 0.45f, h * 0.2f, w * 1.1f, h * 0.85f)
            }
            drawPath(vein, Color.White.copy(alpha = 0.75f), style = Stroke(width = 1.6f))
            val branch = Path().apply {
                moveTo(w * 0.35f, h * 0.3f)
                cubicTo(w * 0.4f, h * 0.6f, w * 0.2f, h * 0.75f, w * 0.25f, h * 1.1f)
            }
            drawPath(branch, Color.White.copy(alpha = 0.35f), style = Stroke(width = 1f))
        }
        ReadingTable.LINEN -> {
            val step = w / 14f
            for (i in 0..14) {
                drawLine(Color(0xFF9A8F7C).copy(alpha = 0.35f), Offset(i * step, 0f), Offset(i * step, h), strokeWidth = 1f)
                drawLine(Color(0xFFFFFFFF).copy(alpha = 0.18f), Offset(0f, i * step), Offset(w, i * step), strokeWidth = 1f)
            }
        }
        ReadingTable.GRAPHITE -> {
            drawRect(Brush.radialGradient(listOf(Color(0xFF34302C), Color(0xFF161412)), center = Offset(w * 0.4f, h * 0.35f), radius = w))
        }
        ReadingTable.BLACK -> Unit
    }
}
