package com.readcomic.curl

import com.readcomic.curl.PageTurnController.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        assertTrue("lies a little askew: ${rest.angle}", abs(rest.angle) in 1.3f..3.9f)
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

    @Test
    fun itFadesInStillThenLetsGoWithoutAJerk() {
        val c = closedComic(ReadingMode.SINGLE, start = 3, width = 1080, height = 2280)
        assertTrue(c.pose()!!.hidden)
        val alphas = ArrayList<Float>()
        var pose = c.pose()!!
        // Held up while it fades in: nothing moves yet.
        while (true) {
            c.step(1f / 60f)
            pose = c.pose()!!
            if (pose.lift < 1f) break
            alphas += pose.alpha
            assertEquals(1f, pose.lift, 0f)
        }
        assertTrue("fades in over several frames: ${alphas.size}", alphas.size >= 10)
        assertEquals(alphas.sorted(), alphas)
        assertTrue(alphas.first() < 0.1f && alphas.last() > 0.95f)
        // The first frames of the fall move only a little, then faster and faster.
        val angles = ArrayList<Float>()
        val tilts = ArrayList<Float>()
        val lifts = ArrayList<Float>()
        repeat(6) {
            angles += c.pose()!!.angle
            tilts += c.pose()!!.tilt
            lifts += c.pose()!!.lift
            c.step(1f / 60f)
        }
        val firstTurn = abs(angles[1] - angles[0])
        val laterTurn = abs(angles[5] - angles[4])
        assertTrue("turns from rest: $firstTurn then $laterTurn", firstTurn < laterTurn * 0.5f)
        assertTrue("tilts from rest", abs(tilts[1] - tilts[0]) < abs(tilts[5] - tilts[4]) * 0.5f)
        assertTrue("falls from rest", (lifts[0] - lifts[1]) < (lifts[4] - lifts[5]) * 0.5f)
        assertEquals(1f, c.pose()!!.alpha, 0f)
    }

    @Test
    fun itFallsFromRightInFrontOfTheEyes() {
        for ((mode, size) in listOf(ReadingMode.SPREAD to (1080 to 2280), ReadingMode.SPREAD to (2280 to 1080), ReadingMode.SINGLE to (1080 to 2280))) {
            val (width, height) = size
            val c = closedComic(mode, start = 4, width = width, height = height)
            val rect = if (mode == ReadingMode.SPREAD) c.rightSlot() else c.singleRect(0)
            c.step(1f / 60f)
            val first = c.pose()!!
            // Seen from the eye: larger by 1 / (1 - lift * height).
            val seen = first.scale / (1f - first.lift * first.height)
            // It overflows the screen; on a wide screen (where that would mean a giant cover) it
            // fills the height and most of the width.
            assertTrue("overflows the height at first ($mode ${width}x$height)", rect.height * seen > height)
            val wanted = if (width > height) width * 0.6f else width.toFloat()
            assertTrue("covers the width at first ($mode ${width}x$height)", rect.width * seen > wanted)
            c.settle()
            val rest = c.pose()!!
            assertEquals(0f, rest.lift, 1e-3f)
            // Lying closed it uses the screen well, whatever the layout.
            val fill = maxOf(rect.width * rest.scale / width, rect.height * rest.scale / height)
            assertTrue("lies large on the table: $fill", fill in 0.8f..0.95f)
        }
    }

    @Test
    fun eachComicLandsALittleDifferently() {
        val angles = (1..6).map { seed ->
            closedComic(ReadingMode.SINGLE, start = 2, width = 1080, height = 2280).apply {
                random = kotlin.random.Random(seed)
                setPages(FloatArray(12) { 0.65f }, startImage = 2, closed = true)
                coverReady = true
                settle()
            }.pose()!!.angle
        }
        assertTrue("not always the same: $angles", angles.distinct().size > 3)
        assertTrue(angles.any { it < 0f } && angles.any { it > 0f })
    }

    @Test
    fun inPortraitTheClosedComicMovesBackToTheSpreadAsItOpens() {
        val c = closedComic(ReadingMode.SPREAD, start = 6, width = 1080, height = 2280)
        c.settle()
        assertTrue("closed, larger than a page of the spread", c.pose()!!.scale > 1.5f)
        c.open()
        c.settle()
        assertNull(c.pose())
        assertFalse(c.isClosed)
    }
}
