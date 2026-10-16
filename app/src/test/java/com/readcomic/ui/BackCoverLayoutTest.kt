package com.readcomic.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class BackCoverLayoutTest {
    @Test
    fun issueNumberIsSplitFromTheSeries() {
        assertEquals("Saga" to "12", BackCoverLayout.splitIssue("Saga #12"))
        assertEquals("Ночной патруль" to "1", BackCoverLayout.splitIssue("Ночной патруль #1"))
        assertEquals("Batman" to "1A", BackCoverLayout.splitIssue("Batman, # 1A"))
        val plain = BackCoverLayout.splitIssue("Город теней")
        assertEquals("Город теней", plain.first)
        assertNull(plain.second)
        // Only a number: nothing is left for the series, so the title stays whole.
        assertEquals("#5" to null, BackCoverLayout.splitIssue("#5"))
    }

    @Test
    fun dotsCoverAsMuchOfTheirCellAsTheToneSays() {
        val pitch = 20f
        assertEquals(0f, BackCoverLayout.dotRadius(0f, pitch), 1e-6f)
        for (tone in listOf(0.1f, 0.3f, 0.5f)) {
            val r = BackCoverLayout.dotRadius(tone, pitch)
            assertEquals(tone, (PI * r * r / (pitch * pitch)).toFloat(), 1e-4f)
        }
        // Darker never means a smaller dot, and the darkest stop where the dots have joined.
        var last = 0f
        for (i in 0..100) {
            val r = BackCoverLayout.dotRadius(i / 100f, pitch)
            assertTrue(r >= last)
            last = r
        }
        assertTrue(last <= pitch * 0.71f + 1e-4f)
        assertEquals(last, BackCoverLayout.dotRadius(7f, pitch), 1e-6f)
    }

    @Test
    fun theScreenCoversThePictureEvenly() {
        val width = 300f
        val height = 200f
        val pitch = 10f
        val dots = BackCoverLayout.screen(width, height, pitch)
        assertEquals(0, dots.size % 2)
        // About one dot per cell, none far outside the picture.
        val count = dots.size / 2
        assertTrue("count $count", count > width * height / (pitch * pitch) * 0.95f)
        for (i in dots.indices step 2) {
            assertTrue(dots[i] >= -pitch && dots[i] <= width + pitch)
            assertTrue(dots[i + 1] >= -pitch && dots[i + 1] <= height + pitch)
        }
        // Every point of the picture has a dot close by: no gaps in the screen.
        for (x in 0..30) {
            for (y in 0..20) {
                val px = x * 10f
                val py = y * 10f
                var best = Float.MAX_VALUE
                for (i in dots.indices step 2) {
                    val dx = dots[i] - px
                    val dy = dots[i + 1] - py
                    best = minOf(best, dx * dx + dy * dy)
                }
                assertTrue("gap at $px, $py", best <= pitch * pitch * 0.5f + 1e-3f)
            }
        }
    }

    @Test
    fun tonesUseTheWholeRangeAndFollowTheInk() {
        val luminance = FloatArray(100) { 0.3f + 0.2f * it / 99f }
        val dark = BackCoverLayout.tones(luminance, darkInk = true)
        val light = BackCoverLayout.tones(luminance, darkInk = false)
        // A dull picture is stretched to the full range of the screen.
        assertEquals(1f, dark.first(), 1e-4f)
        assertEquals(0f, dark.last(), 1e-4f)
        // Dark ink goes where the picture is dark; a light ink where it is light.
        assertTrue(dark[10] > dark[90])
        assertTrue(light[10] < light[90])
        for (i in luminance.indices) assertEquals(1f, dark[i] + light[i], 1e-4f)
        // A flat picture does not blow up into noise.
        val flat = BackCoverLayout.tones(FloatArray(10) { 0.5f }, darkInk = true)
        assertTrue(flat.all { it in 0f..1f })
    }

    @Test
    fun barcodeIsAValidUpcAndTheSameForTheSameComic() {
        val digits = BackCoverLayout.upcDigits("3f2a9c")
        assertEquals(12, digits.length)
        assertTrue(digits.all { it.isDigit() })
        assertEquals('7', digits[0])
        assertEquals(digits.last() - '0', BackCoverLayout.upcCheck(digits))
        assertEquals(digits, BackCoverLayout.upcDigits("3f2a9c"))
        assertNotEquals(digits, BackCoverLayout.upcDigits("3f2a9d"))
        // A known code: 036000291452 (its check digit is 2).
        assertEquals(2, BackCoverLayout.upcCheck("036000291452"))
    }

    @Test
    fun barcodeModulesFollowUpcA() {
        val modules = BackCoverLayout.upcModules("036000291452")
        assertEquals(95, modules.size)
        val guard = booleanArrayOf(true, false, true)
        assertArrayEquals(guard, modules.copyOfRange(0, 3))
        assertArrayEquals(booleanArrayOf(false, true, false, true, false), modules.copyOfRange(45, 50))
        assertArrayEquals(guard, modules.copyOfRange(92, 95))
        // The first digit, 0, on the left: 0001101.
        assertArrayEquals(booleanArrayOf(false, false, false, true, true, false, true), modules.copyOfRange(3, 10))
        // The last digit, 2, on the right: 1101100.
        assertArrayEquals(booleanArrayOf(true, true, false, true, true, false, false), modules.copyOfRange(85, 92))
        assertTrue(BackCoverLayout.isGuard(0) && BackCoverLayout.isGuard(47) && BackCoverLayout.isGuard(94))
        assertTrue(!BackCoverLayout.isGuard(3) && !BackCoverLayout.isGuard(91))
    }
}
