package com.readcomic.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RibbonTest {
    private val size = Vec3(0.17f, 0.258f, 0.004f)

    @Test
    fun aComicStandingStillKeepsItsRibbonStill() {
        val ribbon = Ribbon()
        val at = Vec3(0f, 0.1f, 0.05f)
        ribbon.step(at, Quat.IDENTITY, size, 1f / 60f)
        repeat(30) { assertFalse(ribbon.step(at, Quat.IDENTITY, size, 1f / 60f)) }
    }

    @Test
    fun turningTheComicSwingsTheRibbonThenItSettles() {
        val ribbon = Ribbon()
        val at = Vec3(0f, 0f, 0.5f)
        ribbon.step(at, Quat.IDENTITY, size, 1f / 60f)
        val restX = ribbon.bendX
        // A quick turn in the hands, then held still.
        var yaw = 0f
        var swing = 0f
        repeat(8) {
            yaw += 0.25f
            ribbon.step(at, Quat.axisAngle(Vec3(0f, 1f, 0f), yaw), size, 1f / 60f)
        }
        repeat(20) {
            ribbon.step(at, Quat.axisAngle(Vec3(0f, 1f, 0f), yaw), size, 1f / 60f)
            swing = maxOf(swing, abs(ribbon.bendX - restX))
        }
        assertTrue("it swings: $swing", swing > 0.1f)
        var t = 0f
        while (ribbon.step(at, Quat.axisAngle(Vec3(0f, 1f, 0f), yaw), size, 1f / 60f) && t < 5f) t += 1f / 60f
        assertTrue("and settles within a few seconds: $t", t < 4f)
        assertEquals(restX, ribbon.bendX, 0.01f)
    }

    @Test
    fun theMeshIsARibbonWithASwallowtail() {
        val ribbon = Ribbon()
        ribbon.step(Vec3.ZERO, Quat.IDENTITY, size, 1f / 60f)
        val mesh = ribbon.mesh(Vec3.ZERO, Quat.IDENTITY, size)
        assertEquals((Ribbon.ROWS + 1) * 3 * 9, mesh.size)
        assertTrue(mesh.all { it.isFinite() })
        // It comes out of the top of the comic, towards the fore-edge.
        assertTrue(mesh[1] > size.y * 0.45f && mesh[0] > 0f)
        // The middle of the end is cut back from its corners.
        val last = Ribbon.ROWS * 3 * 9
        fun point(i: Int) = Vec3(mesh[i], mesh[i + 1], mesh[i + 2])
        val corner = point(last)
        val notch = point(last + 9)
        val root = point(0)
        assertTrue((notch - root).length() < (corner - root).length())
        assertEquals(Ribbon.ROWS * 12, Ribbon.INDICES.size)
    }
}
