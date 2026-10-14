package com.readcomic.curl

import com.readcomic.curl.PageTurnController.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

class SmoothGesturesTest {
    private fun zoomed(): PageTurnController = PageTurnController(density = 2f).apply {
        setViewSize(1080, 2280)
        setReadingMode(ReadingMode.SINGLE)
        setPages(FloatArray(10) { 0.65f }, startImage = 0)
        doubleTap(540f, 1140f)
        var t = 0f
        while (step(1f / 60f) && t < 2f) t += 1f / 60f
    }

    @Test
    fun trackInterpolatesAndHoldsTheLastPosition() {
        val track = TouchTrack()
        val out = FloatArray(3)
        track.add(1_000L, 0f, 0f)
        track.add(2_000L, 10f, 20f)
        track.add(4_000L, 30f, 20f)
        assertTrue(track.sample(1_500L, out))
        assertEquals(5f, out[0], 1e-4f)
        assertEquals(10f, out[1], 1e-4f)
        track.sample(3_000L, out)
        assertEquals(20f, out[0], 1e-4f)
        track.sample(9_000L, out)
        assertEquals("a finger that stopped is not guessed past", 30f, out[0], 1e-4f)
        track.sample(0L, out)
        assertEquals(0f, out[0], 1e-4f)
    }

    /**
     * A finger moving at a steady speed, touch samples arriving at 120 Hz with a jittery clock,
     * frames at 60 Hz with their own phase: the page must move by equal steps every frame.
     */
    @Test
    fun panStepsAreEvenWhateverTheEventTiming() {
        val c = zoomed()
        val track = TouchTrack()
        val random = Random(7)
        val speed = 1200f / 1e9f // pixels per nanosecond (1200 px/s)
        val startY = 1800f
        val out = FloatArray(3)
        // Samples every ~8.3 ms with up to 2 ms of jitter, starting at time 0.
        var sampleTime = 0L
        val samples = ArrayList<Long>()
        while (sampleTime < 600_000_000L) {
            samples += sampleTime
            sampleTime += 8_333_333L + random.nextLong(-2_000_000L, 2_000_000L)
        }
        c.beginPan(540f, startY)
        val offsets = ArrayList<Float>()
        var next = 0
        var frame = 50_000_000L + 3_100_000L
        while (frame < 550_000_000L) {
            // Everything measured up to 5 ms before the refresh has been delivered, and the input
            // system adds the finger's position at exactly that moment (its own resampling).
            while (next < samples.size && samples[next] <= frame - 5_000_000L) {
                val t = samples[next]
                track.add(t, 540f, startY - speed * t)
                next++
            }
            track.add(frame - 5_000_000L, 540f, startY - speed * (frame - 5_000_000L))
            track.sample(frame - 6_000_000L, out)
            c.panTo(out[0], out[1] + (startY - speed * 0f) - startY)
            offsets += c.offsetY
            frame += 16_666_667L
        }
        val steps = offsets.zipWithNext { a, b -> b - a }.drop(2).filter { it != 0f }
        val mean = steps.average().toFloat()
        val deviation = sqrt(steps.map { (it - mean) * (it - mean) }.average()).toFloat()
        assertEquals("moves at the finger's speed", -1200f / 60f, mean, 1f)
        assertTrue("steps vary by $deviation px", deviation < 0.5f)
    }

    @Test
    fun pinchKeepsThePointUnderTheFingers() {
        val c = zoomed()
        c.beginPinch(400f, 900f, 300f)
        val contentX = (400f - c.offsetX) / c.scale
        val contentY = (900f - c.offsetY) / c.scale
        // Spread the fingers in many small steps and move them a little: no drift may add up.
        for (i in 1..200) {
            val fx = 400f + i * 0.4f
            val fy = 900f - i * 0.3f
            c.pinchTo(fx, fy, 300f + i * 3f)
            assertEquals(fx, contentX * c.scale + c.offsetX, 0.01f)
            assertEquals(fy, contentY * c.scale + c.offsetY, 0.01f)
        }
        // 2.5 times the zoom of the start would be 7.5: past the maximum the pinch gives way.
        assertTrue(c.scale > PageTurnController.MAX_SCALE && c.scale < PageTurnController.MAX_SCALE * 1.3f)
    }

    @Test
    fun zoomGivesWayPastItsLimitsAndComesBack() {
        val c = zoomed()
        c.beginPinch(540f, 1140f, 400f)
        c.pinchTo(540f, 1140f, 4000f)
        assertTrue("over-zoom is resisted", c.scale > PageTurnController.MAX_SCALE && c.scale < PageTurnController.MAX_SCALE * 1.3f)
        c.endPinch()
        var t = 0f
        while (c.step(1f / 60f) && t < 2f) t += 1f / 60f
        assertEquals(PageTurnController.MAX_SCALE, c.scale, 0.001f)

        c.beginPinch(540f, 1140f, 1000f)
        c.pinchTo(540f, 1140f, 100f)
        assertTrue("zooming out past the page is resisted", c.scale < 1f && c.scale > 0.75f)
        c.endPinch()
        t = 0f
        while (c.step(1f / 60f) && t < 2f) t += 1f / 60f
        assertEquals(1f, c.scale, 0.001f)
        assertEquals(0f, c.offsetX, 0.001f)
    }

    @Test
    fun panningPastAnEdgeStretchesThenSpringsBack() {
        val c = zoomed()
        c.beginPan(500f, 500f)
        c.panTo(500f, 400f)
        c.panTo(500f, 500f)
        c.panTo(5500f, 500f)
        val edge = -c.singleRect(0).left * c.scale
        val stretched = c.offsetX - edge
        assertTrue("stretched by $stretched", stretched > 0f && stretched < 1080f * 0.22f)
        // Pulling further stretches less and less.
        c.panTo(9500f, 500f)
        assertTrue(c.offsetX - edge > stretched)
        assertTrue(c.offsetX - edge - stretched < stretched)
        // Taking hold again does not make the page jump.
        val before = c.offsetX
        c.beginPan(9500f, 500f)
        c.panTo(9500f, 500f)
        assertEquals(before, c.offsetX, 0.01f)

        c.endPan(0f, 0f)
        var t = 0f
        while (c.step(1f / 60f) && t < 2f) t += 1f / 60f
        assertTrue("settled in ${t}s", t < 1.2f)
        assertEquals(edge, c.offsetX, 0.5f)
    }

    @Test
    fun aFastThrowBouncesSoftlyOffTheEdge() {
        val c = zoomed()
        val edge = -c.singleRect(0).left * c.scale
        c.beginPan(500f, 500f)
        c.endPan(9000f, 0f)
        var t = 0f
        var furthest = 0f
        while (c.step(1f / 60f) && t < 3f) {
            t += 1f / 60f
            furthest = max(furthest, c.offsetX - edge)
        }
        assertTrue("overshoots a little: $furthest", furthest > 1f && furthest < 1080f * 0.15f)
        assertEquals(edge, c.offsetX, 0.5f)
        assertTrue(abs(c.offsetY) >= 0f)
    }
}
