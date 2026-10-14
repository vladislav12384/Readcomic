package com.readcomic.curl

import com.readcomic.curl.PageTurnController.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTest {
    private val changes = mutableListOf<Pair<Int, Int>>()
    private var landed = 0
    private val closedStates = mutableListOf<Boolean>()

    private fun closedComic(mode: ReadingMode, start: Int, width: Int = 1920, height: Int = 1080) =
        PageTurnController(density = 2f).apply {
            listener = object : PageTurnController.Listener {
                override fun onPageChanged(first: Int, last: Int) {
                    changes += first to last
                }

                override fun onPageTurned() = Unit

                override fun onLanded() {
                    landed++
                }

                override fun onClosedChanged(closed: Boolean) {
                    closedStates += closed
                }
            }
            setViewSize(width, height)
            setReadingMode(mode)
            setPages(FloatArray(12) { 0.65f }, startImage = start, closed = true)
            coverReady = true
        }

    private fun PageTurnController.settle(maxSeconds: Float = 4f) {
        var t = 0f
        while (step(1f / 60f) && t < maxSeconds) t += 1f / 60f
        assertTrue("animation did not finish", t < maxSeconds)
    }

    @Test
    fun aClosedComicLandsAndKeepsItsBookmark() {
        val c = closedComic(ReadingMode.SPREAD, start = 7)
        assertTrue(c.isClosed)
        assertEquals(0, c.position)
        val start = c.pose()!!
        assertEquals(1f, start.lift, 1e-4f)
        c.settle()
        assertEquals("lands once", 1, landed)
        val rest = c.pose()!!
        assertEquals(0f, rest.lift, 1e-3f)
        assertEquals(0f, rest.angle, 0.05f)
        assertTrue("lies closed in the middle of the table", rest.shiftX < 0f && rest.scale < 1f)
        assertTrue("nothing is reported while closed: the bookmark stays", changes.isEmpty())
    }

    @Test
    fun openingTurnsTheCoverAndTheReadPagesToTheBookmark() {
        val c = closedComic(ReadingMode.SPREAD, start = 7)
        c.settle()
        c.open()
        assertNotNull(c.scene().turning)
        // Image 7 is the left page of its spread (the cover lies alone, then 1-2, 3-4…).
        assertEquals("the turning leaf shows the left page of the bookmark's spread on its back", 7, c.scene().turning!!.back!!.image)
        c.settle()
        assertFalse(c.isClosed)
        assertNull(c.pose())
        assertEquals(listOf(true, false), closedStates)
        assertEquals(7 to 8, changes.last())
    }

    @Test
    fun theCoverCanBePulledOpenButNotBack() {
        val c = closedComic(ReadingMode.SINGLE, start = 4, width = 1080, height = 2280)
        c.settle()
        assertFalse("nothing to turn back", c.beginDrag(500f, 1000f, 700f, 1000f))
        val r = c.singleRect(0)
        val y = r.top + r.height * 0.6f
        assertTrue(c.beginDrag(r.right - 10f, y, r.right - 60f, y))
        var x = r.right - 60f
        repeat(20) {
            x -= r.width * 0.06f
            c.drag(x, y)
            c.step(1f / 60f)
        }
        c.endDrag(-2000f)
        c.settle()
        assertFalse(c.isClosed)
        assertEquals(4, c.position)
    }

    @Test
    fun anImpatientTouchOpensAtOnce() {
        val c = closedComic(ReadingMode.SINGLE, start = 0, width = 1080, height = 2280)
        repeat(5) { c.step(1f / 60f) }
        assertTrue(c.pose()!!.lift > 0.5f)
        c.open()
        c.settle()
        assertFalse(c.isClosed)
        assertEquals("a new comic opens past its cover", 1, c.position)
    }

    @Test
    fun noZoomWhileClosed() {
        val c = closedComic(ReadingMode.SINGLE, start = 0, width = 1080, height = 2280)
        c.settle()
        c.doubleTap(540f, 1140f)
        c.settle()
        assertEquals(1f, c.scale, 1e-4f)
    }
}
