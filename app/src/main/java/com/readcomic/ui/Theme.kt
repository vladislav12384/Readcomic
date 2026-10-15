package com.readcomic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFFFB55A),
    onPrimary = Color(0xFF3A2200),
    primaryContainer = Color(0xFF5A3A12),
    onPrimaryContainer = Color(0xFFFFDDB6),
    secondary = Color(0xFFE2C1A0),
    background = Color(0xFF141210),
    onBackground = Color(0xFFEDE3DA),
    surface = Color(0xFF141210),
    onSurface = Color(0xFFEDE3DA),
    surfaceVariant = Color(0xFF2B2622),
    onSurfaceVariant = Color(0xFFCBBFB4),
    surfaceContainer = Color(0xFF201C19),
    surfaceContainerHigh = Color(0xFF2A2522),
    error = Color(0xFFFFB4AB),
)

@Composable
fun ReadcomicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

/** A paint for the wall behind the shelves; [red], [green], [blue] are linear, as the 3D scene uses them. */
class ShelfTheme(val name: String, val red: Float, val green: Float, val blue: Float) {
    /** The paint as it looks on screen. */
    val swatch: Color
        get() = Color(toSrgb(red), toSrgb(green), toSrgb(blue))

    private fun toSrgb(c: Float) = Math.pow(c.toDouble(), 1.0 / 2.2).toFloat().coerceIn(0f, 1f)
}

val ShelfThemes = listOf(
    ShelfTheme("Шалфей", 0.17f, 0.22f, 0.17f),
    ShelfTheme("Лён", 0.34f, 0.27f, 0.20f),
    ShelfTheme("Пыльно-синий", 0.14f, 0.19f, 0.25f),
    ShelfTheme("Терракота", 0.36f, 0.17f, 0.11f),
    ShelfTheme("Графит", 0.055f, 0.055f, 0.06f),
)
