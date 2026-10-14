package com.readcomic.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShelfControllerTest {
    private val items = List(9) { ShelfItem("c$it", 0.65f, 24 + it * 20, inProgress = it % 3 == 0) }

    private fun controller(width: Int = 1080, height: Int = 2280) = ShelfController(density = 2.75f).apply {
        setViewSize(width, height)
        setItems(items)
    }

    private fun ShelfController.settle() {
        var t = 0f
        while (step(1f / 60f) && t < 5f) t += 1f / 60f
        assertTrue("animation did not finish", t < 5f)
    }

    /** Screen position (pixels) of the centre of a comic. */
    private fun ShelfController.screenOf(id: String, width: Int = 1080, height: Int = 2280): Pair<Float, Float> {
        val frame = frame()
        val comic = frame.comics.single { it.id == id }
        val center = Mat4.transformPoint(comic.model, Vec3.ZERO)
        val ndc = Mat4.transformPoint(frame.viewProj, center)
        return (ndc.x + 1f) / 2f * width to (1f - ndc.y) / 2f * height
    }

    @Test
    fun portraitHasTwoColumnsLandscapeMore() {
        assertEquals(2, controller().columns)
        assertTrue(controller(2280, 1080).columns >= 4)
    }

    @Test
    fun thicknessFollowsThePageCount() {
        val frame = controller().frame()
        val thin = frame.comics.single { it.id == "c0" }.size.z
        val thick = frame.comics.single { it.id == "c1" }.size.z
        assertTrue(thick > thin)
        assertEquals(24, frame.comics.single { it.id == "c0" }.pages)
        assertEquals(Binding.STAPLED, frame.comics.single { it.id == "c0" }.binding)
    }

    @Test
    fun tappingAComicTakesItAndTappingBesidePutsItBack() {
        val c = controller()
        val (x, y) = c.screenOf("c1")
        assertEquals("c1", c.tap(x, y))
        assertEquals("c1", c.heldId)
        c.settle()
        // In hand: big and in front of the shelves.
        val held = c.frame().comics.last()
        assertEquals("c1", held.id)
        assertTrue(held.held)
        val (hx, hy) = c.screenOf("c1")
        assertEquals("held comic is tappable", "c1", c.pick(hx, hy))
        assertTrue(c.frame().dim > 0.4f)

        // A tap far from it puts it back.
        c.tap(40f, 40f)
        c.settle()
        assertNull(c.heldId)
        assertEquals(0f, c.frame().dim, 0f)
    }

    @Test
    fun aComicOnItsWayBackCanBeCaughtAgain() {
        val c = controller()
        c.take("c2")
        c.settle()
        c.putBack()
        assertNull(c.inHandId)
        repeat(4) { c.step(1f / 60f) }
        val (x, y) = c.screenOf("c2")
        assertEquals("c2", c.tap(x, y))
        assertEquals("c2", c.inHandId)
        c.settle()
        assertTrue(c.frame().comics.last().held)
    }

    @Test
    fun turningInHandSettlesFacingFrontOrBack() {
        val c = controller()
        c.take("c0")
        c.settle()
        c.drag(1080f * 0.6f, 0f)
        c.release(0f, 0f)
        c.settle()
        val held = c.frame().comics.last()
        // The cover normal (local +z) ends up pointing straight at the camera or straight away.
        val normal = Mat4.transformPoint(held.model, Vec3(0f, 0f, 1f)) - Mat4.transformPoint(held.model, Vec3.ZERO)
        val n = normal.normalized()
        assertTrue("faces front or back, z=${n.z}", abs(n.z) > 0.95f)
    }

    @Test
    fun scrollingStopsAtTheEnds() {
        val c = controller()
        val (_, before) = c.screenOf("c0")
        c.drag(0f, 100000f)
        c.release(0f, 0f)
        c.settle()
        val (_, top) = c.screenOf("c0")
        assertEquals("cannot scroll above the first shelf", before, top, 1f)

        c.drag(0f, -100000f)
        c.release(0f, 0f)
        c.settle()
        val (_, after) = c.screenOf("c8")
        assertTrue("last shelf is on screen", after in 0f..2280f)
        assertNotEquals(before, c.screenOf("c0").second, 1f)
    }

    @Test
    fun ribbonOnlyForComicsBeingRead() {
        val frame = controller().frame()
        assertTrue(frame.comics.single { it.id == "c0" }.ribbon != null)
        assertNull(frame.comics.single { it.id == "c1" }.ribbon)
    }
}
