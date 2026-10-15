package com.readcomic.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnboxingTest {
    private val width = 1080
    private val height = 2280

    private class Recorder : Unboxing.Listener {
        val unwrapped = ArrayList<String>()
        val states = ArrayList<UnboxState>()
        val cues = ArrayList<UnboxCue>()
        override fun onUnwrapped(id: String) {
            unwrapped += id
        }

        override fun onStateChanged(state: UnboxState) {
            states += state
        }

        override fun onCue(cue: UnboxCue, strength: Float) {
            cues += cue
        }
    }

    private fun shelved() = List(6) { ShelfItem("s$it", 0.66f, 32, false, label = "Shelf $it") }

    private fun boxed() = List(2) { ShelfItem("n$it", 0.66f, 40, false, label = "New $it") }

    private fun controller(recorder: Recorder): ShelfController = ShelfController(density = 2.75f).apply {
        setViewSize(width, height)
        // Walking into the room: the lights come on, the box drops in.
        switchLightsOn()
        setItems(shelved())
        unboxing.listener = recorder
        setBox(boxed())
    }

    private fun ShelfController.run(seconds: Float) {
        var t = 0f
        while (t < seconds) {
            step(1f / 60f)
            t += 1f / 60f
        }
    }

    /** Where the point [local] of a model ends up on screen, in pixels. */
    private fun screen(frame: ShelfFrame, model: FloatArray, local: Vec3): Pair<Float, Float> {
        val p = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(model, local))
        return (p.x + 1f) / 2f * width to (1f - p.y) / 2f * height
    }

    @Test
    fun theBoxDropsInAndPeeksAtTheBottom() {
        val recorder = Recorder()
        val c = controller(recorder)
        c.run(2f)
        assertEquals(UnboxPhase.PEEK, recorder.states.last().phase)
        assertEquals(2, recorder.states.last().count)
        assertTrue(UnboxCue.BOX_LANDS in recorder.cues)
        val frame = c.frame()
        val box = frame.unbox!!
        // Its front wall is at the bottom of the screen, partly off it.
        val (_, y) = screen(frame, box.parts[1].model, Vec3.ZERO)
        assertTrue("near the bottom: $y", y > height * 0.8f)
        assertFalse(c.boxTakesTouches)
        // The shelves are not darkened while it only peeks.
        assertEquals(0f, frame.dim, 0.01f)
    }

    @Test
    fun comingBackFromReadingTheBoxIsSimplyThere() {
        val recorder = Recorder()
        val c = ShelfController(density = 2.75f).apply {
            setViewSize(width, height)
            setItems(shelved())
            unboxing.listener = recorder
            setBox(boxed())
        }
        c.step(1f / 60f)
        assertEquals(UnboxPhase.PEEK, recorder.states.last().phase)
        assertFalse(UnboxCue.BOX_LANDS in recorder.cues)
        val frame = c.frame()
        val (_, y) = screen(frame, frame.unbox!!.parts[1].model, Vec3.ZERO)
        assertTrue("already at the bottom: $y", y > height * 0.8f)
        // New comics later still come with a bump.
        c.setBox(boxed() + ShelfItem("n9", 0.66f, 30, false))
        assertTrue(UnboxCue.BOX_SHAKES in recorder.cues)
    }

    @Test
    fun aComicGoesFromTheBoxThroughTheReadersHandsOntoItsShelf() {
        val recorder = Recorder()
        val c = controller(recorder)
        c.run(2f)

        // A tap on the box brings it up.
        val peek = c.frame()
        val (bx, by) = screen(peek, peek.unbox!!.parts[1].model, Vec3.ZERO)
        c.tap(bx, (by).coerceAtMost(height - 10f))
        c.run(1f)
        assertEquals(UnboxPhase.SEALED, recorder.states.last().phase)
        assertTrue(c.boxTakesTouches)
        assertTrue(c.frame().dim > 0.4f)

        // A finger along the tape cuts it; the flaps spring open and the top comic comes out.
        val sealed = c.frame()
        val tape = sealed.unbox!!.parts.first { it.kind == CardboardBox.KIND_TAPE }.model
        val (fx, fy) = screen(sealed, tape, Vec3(0.5f, 0f, 0.45f))
        val (tx, ty) = screen(sealed, tape, Vec3(0.5f, 0f, -0.45f))
        c.boxDown(fx, fy)
        for (k in 1..12) {
            c.boxMove(fx + (tx - fx) * k / 12f, fy + (ty - fy) * k / 12f)
            c.step(1f / 60f)
        }
        c.boxUp()
        c.run(2.5f)
        assertTrue(UnboxCue.TAPE_DONE in recorder.cues)
        assertTrue(UnboxCue.FLAP in recorder.cues)
        assertEquals(UnboxPhase.TEARING, recorder.states.last().phase)
        assertEquals("n0", c.unboxing.inHandId)

        // Tearing the paper off, strip by strip, across the cover.
        var tearing = c.frame()
        var guard = 0
        while (c.unboxing.inHandId == "n0" && recorder.unwrapped.isEmpty() && guard < 40) {
            tearing = c.frame()
            val comic = tearing.unbox!!.comics.last { it.comic.held }.comic.model
            val row = -0.45f + (guard % 20) * 0.047f
            val (ax, ay) = screen(tearing, comic, Vec3(-0.55f, row, 0.5f))
            val (zx, zy) = screen(tearing, comic, Vec3(0.55f, row, 0.5f))
            c.boxDown(ax, ay)
            for (k in 1..10) {
                c.boxMove(ax + (zx - ax) * k / 10f, ay + (zy - ay) * k / 10f)
                c.step(1f / 60f)
            }
            c.boxUp()
            c.step(1f / 60f)
            guard++
            if (recorder.states.last().phase != UnboxPhase.TEARING) break
        }
        assertTrue(UnboxCue.TEAR_TICK in recorder.cues)
        assertTrue(UnboxCue.RELEASE in recorder.cues)
        c.run(2.5f)
        assertTrue(UnboxCue.SHINE in recorder.cues)
        assertEquals(listOf("n0"), recorder.unwrapped)

        // The collection catches up: the comic is on the shelves now, out of the box.
        c.setItems(shelved() + boxed().first())
        c.setBox(boxed().drop(1))
        assertNull(c.unboxing.inHandId)
        assertEquals("n0", c.heldId)
        assertNull(c.inHandId)
        c.run(1.5f)
        assertNull("back on the shelf", c.heldId)

        // The next one comes out of the box by itself, once that one is in its place.
        c.run(2.5f)
        assertEquals("n1", c.unboxing.inHandId)
        assertEquals(UnboxPhase.TEARING, recorder.states.last().phase)
    }

    @Test
    fun putAwayHalfwayKeepsTheTear() {
        val recorder = Recorder()
        val c = controller(recorder)
        c.run(2f)
        c.openBox()
        c.run(1f)
        c.boxTap(width / 2f, height * 0.6f)
        c.run(2.5f)
        assertEquals(UnboxPhase.TEARING, recorder.states.last().phase)
        val frame = c.frame()
        val comic = frame.unbox!!.comics.last { it.comic.held }.comic.model
        val (ax, ay) = screen(frame, comic, Vec3(-0.4f, 0f, 0.5f))
        val (zx, zy) = screen(frame, comic, Vec3(0.4f, 0f, 0.5f))
        c.boxDown(ax, ay)
        for (k in 1..8) {
            c.boxMove(ax + (zx - ax) * k / 8f, ay + (zy - ay) * k / 8f)
            c.step(1f / 60f)
        }
        c.boxUp()
        val progress = recorder.states.last().progress
        c.putAwayBox()
        c.run(2f)
        assertEquals(UnboxPhase.PEEK, recorder.states.last().phase)
        assertFalse(c.boxTakesTouches)
        assertNull(c.unboxing.inHandId)
        // Out again: the same paper, as torn as it was.
        c.openBox()
        c.run(2.5f)
        assertEquals("n0", c.unboxing.inHandId)
        assertTrue(recorder.states.last().progress >= progress - 0.01f)
    }

    @Test
    fun unwrappingEverythingSendsTheBoxAwayAndTheComicsGleamOnTheShelves() {
        val recorder = Recorder()
        val c = controller(recorder)
        c.run(2f)
        c.setItems(shelved() + boxed())
        c.setBox(emptyList())
        c.run(0.3f)
        val gleaming = c.frame().comics.filter { it.shine >= 0f }.map { it.id }.toSet()
        assertEquals(setOf("n0", "n1"), gleaming)
        c.run(2f)
        assertNull(c.frame().unbox)
        assertEquals(UnboxPhase.NONE, recorder.states.last().phase)
    }

    @Test
    fun neighboursMakeRoomForANewcomer() {
        val c = ShelfController(density = 2.75f).apply {
            setViewSize(width, height)
            setItems(shelved())
        }
        c.run(0.2f)
        val before = c.frame().comics.associate { it.id to Mat4.transformPoint(it.model, Vec3.ZERO) }
        // A comic standing first pushes the others one place along.
        c.setItems(listOf(ShelfItem("a", 0.66f, 30, false)) + shelved())
        c.step(1f / 60f)
        val moving = c.frame().comics.associate { it.id to Mat4.transformPoint(it.model, Vec3.ZERO) }
        val s0 = moving.getValue("s0")
        // On its way: between where it was and its new place, not jumped there.
        assertTrue((s0 - before.getValue("s0")).length() < 0.05f)
        c.run(1f)
        val after = c.frame().comics.associate { it.id to Mat4.transformPoint(it.model, Vec3.ZERO) }
        assertEquals(before.getValue("s1").x, after.getValue("s0").x, 0.002f)
        assertNotNull(after["a"])
    }
}
