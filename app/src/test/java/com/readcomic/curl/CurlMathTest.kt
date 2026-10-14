package com.readcomic.curl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

class CurlMathTest {
    private val page = PageRect(12f, 100f, 516f, 797f)

    @Test
    fun liftedLengthIsContinuousAndMonotonic() {
        val r = 60f
        val halfTurn = (PI * r).toFloat()
        assertEquals(halfTurn, CurlMath.liftedLength(halfTurn, r), 0.01f)
        assertEquals(halfTurn, CurlMath.liftedLength(halfTurn - 0.001f, r), 0.05f)
        var previous = 0f
        var d = 0f
        while (d < 3 * halfTurn) {
            val s = CurlMath.liftedLength(d, r)
            assertTrue("not monotonic at $d", s >= previous - 1e-3f)
            previous = s
            d += 1.7f
        }
    }

    @Test
    fun noCurlWhenFingerIsRightOfTheEdge() {
        assertNull(CurlMath.compute(page.right, 400f, page.right + 20f, 400f, page))
    }

    @Test
    fun grabbedPointLandsUnderTheFinger() {
        val grabY = page.top + page.height / 2f
        val fingerX = 150f
        val curl = CurlMath.compute(page.right, grabY, fingerX, grabY, page)
        assertNotNull(curl)
        curl!!
        val (x, y, z) = CurlMath.deform(page.right, grabY, curl)
        assertEquals(fingerX, x, 0.5f)
        assertEquals(grabY, y, 0.5f)
        assertEquals(2f * curl.radius, z, 0.01f)
    }

    @Test
    fun spineNeverLiftsWhileDragging() {
        val random = Random(42)
        repeat(2000) {
            val grabY = page.top + page.height * random.nextFloat()
            val fx = page.left + page.width * random.nextFloat()
            val fy = page.top - 200f + (page.height + 400f) * random.nextFloat()
            val curl = CurlMath.compute(page.right, grabY, fx, fy, page) ?: return@repeat
            // Only meaningful while the fold has not reached the spine yet.
            val (_, _, zTop) = CurlMath.deform(page.left, page.top, curl)
            val (_, _, zBottom) = CurlMath.deform(page.left, page.bottom, curl)
            val axisReachedSpine = (page.left - curl.axisX) * curl.nx >= 0f
            if (!axisReachedSpine) {
                assertEquals("top of the spine lifted for finger ($fx, $fy)", 0f, zTop, 0.01f)
                assertEquals("bottom of the spine lifted for finger ($fx, $fy)", 0f, zBottom, 0.01f)
            }
        }
    }

    @Test
    fun hiddenPositionMovesTheWholeSheetOffScreen() {
        val grabY = page.bottom
        val hiddenX = CurlMath.hiddenX(page.right, page, 0f)
        val curl = CurlMath.compute(page.right, grabY, hiddenX, grabY, page)!!
        for (u in 0..10) {
            for (v in 0..10) {
                val (x, _, _) = CurlMath.deform(page.left + page.width * u / 10f, page.top + page.height * v / 10f, curl)
                assertTrue("point ($u, $v) still visible at x=$x", x < 0f)
            }
        }
    }

    @Test
    fun cornerGrabTiltsTheFold() {
        val curl = CurlMath.compute(page.right, page.bottom, page.right - 120f, page.bottom - 90f, page)!!
        assertTrue(curl.ny > 0.2f)
        val middle = CurlMath.compute(page.right, page.top + page.height / 2, page.right - 120f, page.top + page.height / 2 - 90f, page)!!
        assertTrue("middle of the edge must tilt less", kotlin.math.abs(middle.ny) < curl.ny)
    }
}
