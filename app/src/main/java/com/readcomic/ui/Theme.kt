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

/** Table colours behind the pages in the reader. */
val ReaderBackgrounds = listOf(
    Color(0xFF1F1D1B),
    Color(0xFF000000),
    Color(0xFF4A3B2E),
    Color(0xFFD9D4CC),
)

@Composable
fun ReadcomicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
