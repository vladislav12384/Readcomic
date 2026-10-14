package com.readcomic.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinePaletteTest {
    private val navy = SpinePalette.rgb(20, 30, 90)
    private val yellow = SpinePalette.rgb(250, 210, 30)
    private val red = SpinePalette.rgb(200, 30, 40)
    private val green = SpinePalette.rgb(30, 140, 60)

    private fun cover(width: Int, height: Int, paint: (x: Int, y: Int) -> Int) =
        IntArray(width * height) { paint(it % width, it / width) }

    @Test
    fun aPlainBackgroundGoesRoundTheSpineAndTheLogoColourWritesOnIt() {
        val w = 90
        val h = 140
        val pixels = cover(w, h) { x, y ->
            when {
                y < 25 && x in 15..75 -> yellow // the logo
                (x - 45) * (x - 45) + (y - 80) * (y - 80) < 600 -> red // the art
                else -> navy
            }
        }
        val colors = SpinePalette.pick(pixels, w, 0, 8)
        assertEquals(navy, colors.background)
        assertEquals(yellow, colors.ink)
    }

    @Test
    fun aBusyEdgeTakesTheCoversMainColourInstead() {
        val w = 90
        val h = 140
        val pixels = cover(w, h) { x, y ->
            if (x < 8) {
                // The picture runs to the edge: stripes of all sorts.
                listOf(red, yellow, navy, SpinePalette.rgb(240, 240, 240))[(y / 5) % 4]
            } else {
                green
            }
        }
        val colors = SpinePalette.pick(pixels, w, 0, 8)
        assertEquals(green, colors.background)
        assertTrue(SpinePalette.contrast(colors.ink, colors.background) >= 3f)
    }

    @Test
    fun withoutAContrastingColourTheTitleIsLightOrDark() {
        val grey = SpinePalette.rgb(120, 120, 120)
        val pixels = IntArray(50 * 80) { grey }
        val colors = SpinePalette.pick(pixels, 50, 0, 5)
        assertEquals(grey, colors.background)
        assertTrue(SpinePalette.contrast(colors.ink, grey) >= 3f)
    }

    @Test
    fun clothIsDarker() {
        assertTrue(SpinePalette.luminance(SpinePalette.cloth(red)) < SpinePalette.luminance(red))
    }
}
