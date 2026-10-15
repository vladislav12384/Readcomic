package com.readcomic.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class SaverFlightTest {
    private val flight = SaverFlight(Random(7), 16)
    private val tiles = List(16) { flight.tile(it, if (it % 5 == 0) 1.4f else 0.66f) }

    @Test
    fun theCameraFliesSmoothlyFromPageToPage() {
        val dt = 1f / 60f
        var previous = flight.camera(0f, tiles, 1080f, 2280f)
        val visited = HashSet<Int>()
        var time = 0f
        while (time < 60f) {
            time += dt
            val camera = flight.camera(time, tiles, 1080f, 2280f)
            // On screen, nothing jumps from one frame to the next.
            val step = hypot(camera.x - previous.x, camera.y - previous.y) * camera.scale
            assertTrue("moved $step px in a frame at $time", step < 60f)
            assertTrue("zoom jumps at $time", abs(camera.scale / previous.scale - 1f) < 0.05f)
            assertTrue(abs(camera.rotation - previous.rotation) < 1f)
            visited += flight.currentTile
            previous = camera
        }
        assertTrue("visits many pages: $visited", visited.size >= 6)
    }

    @Test
    fun itLingersOverADetailFillingTheScreen() {
        var time = 0f
        while (flight.lingering(time) <= 0f) {
            time += 1f / 60f
            flight.camera(time, tiles, 1080f, 2280f)
        }
        val camera = flight.camera(time, tiles, 1080f, 2280f)
        val page = tiles[flight.currentTile]
        // Closer than the whole page: a detail of it fills the screen.
        assertTrue(1080f / camera.scale < page.width)
        assertTrue(hypot(camera.x - page.x, camera.y - page.y) < page.height / 2f)
    }
}
