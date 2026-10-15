package com.readcomic.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapTearTest {
    private fun tear() = WrapTear(0.19f, 0.29f, WrapperShape.margin(0.004f), seed = 7)

    @Test
    fun aFingerPiercesThePaperWhereItComesDown() {
        val t = tear()
        assertFalse(t.isTorn(0f, 0f))
        assertTrue(t.touch(0f, 0f))
        assertTrue(t.isTorn(0f, 0f))
        // Only a small hole.
        assertFalse(t.isTorn(0.02f, 0f))
        assertTrue(t.progress in 0f..0.01f)
    }

    @Test
    fun draggingRipsARaggedStripAlongThePath() {
        val t = tear()
        t.touch(-0.08f, 0.05f)
        var y = 0.05f
        var x = -0.08f
        repeat(13) {
            x += 0.01f
            t.drag(x, y, 0.4f)
        }
        // Torn along the path, not far from it.
        for (k in 1..12) assertTrue("torn at ${-0.08f + k * 0.01f}", t.isTorn(-0.08f + k * 0.01f, y))
        assertFalse(t.isTorn(0f, y + 0.04f))
        assertFalse(t.isTorn(0f, y - 0.04f))
        // The edges wander: the strip is not equally wide everywhere.
        val widths = (2..11).map { k ->
            val px = -0.08f + k * 0.01f
            var up = 0f
            while (t.isTorn(px, y + up) && up < 0.05f) up += 0.0005f
            up
        }
        assertTrue("ragged: $widths", widths.max() - widths.min() > 0.002f)
        assertTrue(t.progress > 0.03f)
        // The strip being torn is attached at the finger, with a point every few millimetres.
        val strip = t.scraps.single()
        assertTrue(strip.attached)
        assertTrue("points: ${strip.size}", strip.size > 20)
        t.lift()
        assertFalse(strip.attached)
        assertTrue(t.version > 0)
    }

    @Test
    fun runningOverAHoleFreesTheStrip() {
        val t = tear()
        t.touch(-0.05f, 0f)
        t.drag(0.05f, 0f, 0.3f)
        t.lift()
        // Across the first tear: a new strip starts on the other side.
        t.touch(0f, -0.06f)
        t.drag(0f, 0.06f, 0.3f)
        assertTrue(t.scraps.size >= 2)
        assertTrue(t.scraps.count { it.attached } <= 1)
    }

    @Test
    fun freedStripsFallAndGo() {
        val t = tear()
        t.touch(-0.05f, 0f)
        t.drag(0.05f, 0f, 0.3f)
        t.lift()
        val at = Vec3(0f, 0f, 0.6f)
        assertTrue(t.stepScraps(at, Quat.IDENTITY, 0.003f, 1f / 60f, 0f))
        val start = t.scraps.single().position
        repeat(30) { t.stepScraps(at, Quat.IDENTITY, 0.003f, 1f / 60f, it / 60f) }
        val later = t.scraps.single().position
        assertTrue("falls: ${start.y} -> ${later.y}", later.y < start.y)
        val mesh = t.pieceMesh(at, Quat.IDENTITY, 0.003f, 1f)
        assertNotNull(mesh)
        assertTrue(mesh!!.first.all { it.isFinite() })
        var time = 0.5f
        while (t.stepScraps(at, Quat.IDENTITY, 0.003f, 1f / 30f, time) && time < 10f) time += 1f / 30f
        assertTrue("gone after a while: $time", time < WrapTear.SCRAP_LIFE + 1f)
        assertNull(t.pieceMesh(at, Quat.IDENTITY, 0.003f, time))
    }

    @Test
    fun theEdgesOfATearCurlUp() {
        val t = tear()
        assertEquals(0f, t.lift(0f, 0f), 0f)
        t.touch(-0.05f, 0f)
        t.drag(0.05f, 0f, 0.3f)
        val near = t.lift(0f, 0.016f)
        val far = t.lift(0f, 0.06f)
        assertTrue("near the tear: $near, far: $far", near > far * 3f && near > 0.0005f)
    }

    @Test
    fun everyStripIsDrawnFromTheCellsItTook() {
        val t = tear()
        t.touch(-0.06f, 0f)
        t.drag(0.06f, 0f, 0.3f)
        val strip = t.scraps.single()
        val taken = t.owner.indices.filter { t.owner[it].toInt() and 0xFF == strip.id }
        assertTrue(taken.size > 100)
        // Exactly the torn cells are the strip's, no more.
        assertTrue(taken.all { t.mask[it] != 0.toByte() })
        assertEquals(t.mask.count { it != 0.toByte() }, taken.size)
        val (vertices, _) = t.pieceMesh(Vec3.ZERO, Quat.IDENTITY, 0.003f, 0f)!!
        // Its shape knows where on the sheet every point of it was, and whose it is.
        val codes = (0 until vertices.size / 9).map { vertices[it * 9 + 8] }
        assertTrue(codes.all { it.toInt() == strip.id })
        // Where it still lies at the finger it lies on the cover.
        assertTrue(codes.max() - strip.id > 0.9f)
        t.lift()
        var time = 0f
        while (t.stepScraps(Vec3.ZERO, Quat.IDENTITY, 0.003f, 1f / 30f, time) && time < 10f) time += 1f / 30f
        // Gone: its cells are no one's to draw any more.
        assertTrue(taken.all { t.owner[it].toInt() and 0xFF == WrapTear.GONE })
    }

    @Test
    fun whatIsLeftComesOffInItsOwnPieces() {
        val t = tear()
        for (k in 0..5) {
            val y = 0.12f - k * 0.05f
            t.touch(-0.09f, y)
            t.drag(0.09f, y + 0.01f, 0.4f)
            t.lift()
        }
        val before = t.mask.copyOf()
        t.release(Vec3(0f, 0f, 0.6f), Quat.IDENTITY, 0f) { x, y -> Vec3(x, y, 0.003f) }
        assertTrue(t.released)
        // The front is all accounted for: torn, or a piece of what was left (each its own).
        val front = t.mask.indices.filter { i ->
            val x = -0.095f - t.margin + (i % t.columns + 0.5f) * (0.19f + 2 * t.margin) / t.columns
            val y = -0.145f - t.margin + (i / t.columns + 0.5f) * (0.29f + 2 * t.margin) / t.columns * t.columns / t.rows
            kotlin.math.abs(x) < 0.09f && kotlin.math.abs(y) < 0.14f
        }
        val pieces = front.filter { t.mask[it] == 0.toByte() }.map { t.owner[it].toInt() and 0xFF }.toSet()
        assertTrue("several pieces: $pieces", pieces.size >= 3)
        assertTrue(pieces.none { it == 0 || it == WrapTear.GONE })
        // What was paper stays paper: only thin tears were added to break it up.
        val added = t.mask.indices.count { before[it] == 0.toByte() && t.mask[it] != 0.toByte() }
        assertTrue("few cells torn to break it: $added", added < front.size / 5)
        // They come off, and are gone after a while.
        val (vertices, _) = t.pieceMesh(Vec3.ZERO, Quat.IDENTITY, 0.003f, 0.5f)!!
        assertTrue((0 until vertices.size / 9).any { vertices[it * 9 + 8] >= 512f })
        var time = 0f
        while (t.stepScraps(Vec3.ZERO, Quat.IDENTITY, 0.003f, 1f / 30f, time) && time < 10f) time += 1f / 30f
        assertTrue("all gone: $time", time < 4f)
        // The finger tears nothing more.
        assertFalse(t.touch(0f, 0f))
    }

    @Test
    fun theWrappingIsTwoClosedHalves() {
        val halves = WrapperShape.build(Vec3(0.19f, 0.29f, 0.004f), null, 3)
        for (vertices in listOf(halves.left, halves.right)) {
            assertTrue(vertices.all { it.isFinite() })
            val count = vertices.size / 9
            // In front of the cover, round its edges and behind it.
            val zs = (0 until count).map { vertices[it * 9 + 2] }
            assertTrue(zs.max() > 0.003f && zs.min() < -0.003f)
        }
        assertTrue(halves.leftIndices.all { it.toInt() < halves.left.size / 9 })
        assertTrue(halves.rightIndices.all { it.toInt() < halves.right.size / 9 })
        // The left half stays left of the middle, the right one right of it.
        assertTrue((0 until halves.left.size / 9).all { halves.left[it * 9] <= 1e-5f })
        assertTrue((0 until halves.right.size / 9).all { halves.right[it * 9] >= -1e-5f })
    }
}
