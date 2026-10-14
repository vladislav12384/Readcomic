package com.readcomic.curl

import com.readcomic.curl.PageTurnController.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue("lies a little askew, as put down by hand", rest.angle < -1f && rest.angle > -5f)
        assertTrue("lies closed in the middle of the table", rest.shiftX < 0f && rest.scale < 1f)
        assertTrue("nothing is reported while closed: the bookmark stays", changes.isEmpty())
    }

    @Test
    fun openingRifflesThroughTheReadPagesToTheBookmark() {
        val c = closedComic(ReadingMode.SPREAD, start = 9)
        c.settle()
        var ticks = 0
        val base = c.listener!!
        c.listener = object : PageTurnController.Listener by base {
            override fun onPageTurned() {
                ticks++
            }
        }
        c.open()
        var most = 0
        var t = 0f
        while (c.step(1f / 60f) && t < 4f) {
            t += 1f / 60f
            most = maxOf(most, c.scene().flying.size)
        }
        assertTrue("several bunches of pages are in the air at once", most >= 2)
        assertTrue("a tick for every bunch that lands: $ticks", ticks in 2..7)
        assertFalse(c.isClosed)
        assertNull(c.pose())
        assertEquals(listOf(true, false), closedStates)
        // Image 9 is the left page of its spread (the cover lies alone, then 1-2, 3-4…).
        assertEquals(9 to 10, changes.last())
    }

    @Test
    fun aNewComicJustOpensItsCover() {
        val c = closedComic(ReadingMode.SPREAD, start = 0)
        c.settle()
        c.open()
        c.step(1f / 60f)
        c.step(1f / 60f)
        val flying = c.scene().flying
        assertEquals(1, flying.size)
        assertEquals("the cover goes over", 0, flying.single().front.image)
        c.settle()
        assertEquals(1 to 2, changes.last())
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

    @Test
    fun itFallsFromWhereTheShelfHoldsItUp() {
        for ((mode, size) in listOf(ReadingMode.SINGLE to (1080 to 2280), ReadingMode.SPREAD to (2280 to 1080))) {
            val (width, height) = size
            val c = closedComic(mode, start = 5, width = width, height = height)
            c.step(1f / 60f)
            val pose = c.pose()!!
            assertFalse(pose.hidden)
            assertEquals(1f, pose.lift, 1e-3f)
            assertEquals("starts on a plain table", 0f, pose.ambience, 0.01f)
            // Lifted towards the eye, it looks larger by 1 / (1 - height).
            val rect = if (mode == ReadingMode.SPREAD) c.rightSlot() else c.singleRect(0)
            val seen = pose.scale / (1f - pose.lift * pose.height)
            val expected = PageTurnController.handRect(width.toFloat(), height.toFloat(), 2f, 0.65f, mode == ReadingMode.SPREAD)
            assertEquals(expected.height, rect.height * seen, 2f)
            assertEquals(expected.width, rect.width * seen, 2f)
            assertEquals(expected.left + expected.width / 2f, pose.pivotX + pose.shiftX, 1f)
            assertEquals(expected.top + expected.height / 2f, pose.pivotY + pose.shiftY, 1f)
            assertEquals(0f, pose.angle, 0.1f)
            c.settle()
            assertEquals("the table's light comes up as it falls", 1f, c.pose()!!.ambience, 1e-3f)
        }
    }

    @Test
    fun shownOnceTheCoverCanBeSeen() {
        var shown = 0
        val c = PageTurnController(density = 2f).apply {
            listener = object : PageTurnController.Listener {
                override fun onPageChanged(first: Int, last: Int) = Unit
                override fun onPageTurned() = Unit
                override fun onShown() {
                    shown++
                }
            }
            setViewSize(1080, 2280)
            setPages(FloatArray(12) { 0.65f }, startImage = 3, closed = true)
        }
        repeat(20) { c.step(1f / 60f) }
        assertEquals("waits for its cover", 0, shown)
        assertTrue(c.pose()!!.hidden)
        c.coverReady = true
        repeat(3) { c.step(1f / 60f) }
        assertEquals(1, shown)
        c.settle()
        c.open()
        c.settle()
        assertEquals(1, shown)
    }
}
