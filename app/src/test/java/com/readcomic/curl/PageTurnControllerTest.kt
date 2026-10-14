package com.readcomic.curl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PageTurnControllerTest {
    private lateinit var controller: PageTurnController
    private val changes = mutableListOf<Int>()
    private var turned = 0

    @Before
    fun setUp() {
        controller = PageTurnController(density = 2f)
        controller.setViewSize(1080, 2280)
        controller.setPages(count = 10, startIndex = 0)
        controller.listener = object : PageTurnController.Listener {
            override fun onPageChanged(index: Int) {
                changes += index
            }

            override fun onPageTurned() {
                turned++
            }
        }
    }

    private fun settle(maxSeconds: Float = 3f) {
        var t = 0f
        while (controller.step(1f / 60f) && t < maxSeconds) t += 1f / 60f
        assertTrue("animation did not finish", t < maxSeconds)
    }

    @Test
    fun tapTurnsForwardAndBack() {
        controller.turnForward()
        assertTrue(controller.isTurning)
        settle()
        assertEquals(1, controller.currentIndex)
        assertEquals(listOf(1), changes)
        assertEquals(1, turned)

        controller.turnBackward()
        settle()
        assertEquals(0, controller.currentIndex)
        assertFalse(controller.isTurning)
    }

    @Test
    fun shortDragFallsBack() {
        val rect = controller.pageRect(0)
        val y = rect.top + rect.height / 2
        assertTrue(controller.beginDrag(rect.right - 10f, y, rect.right - 40f, y))
        repeat(10) { controller.step(1f / 60f) }
        assertNotNull(controller.currentCurl())
        controller.endDrag(velocityX = 0f)
        settle()
        assertEquals(0, controller.currentIndex)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun longDragTurnsThePage() {
        val rect = controller.pageRect(0)
        val y = rect.top + rect.height / 2
        controller.beginDrag(rect.right - 10f, y, rect.right - 40f, y)
        var x = rect.right - 40f
        while (x > rect.left + rect.width * 0.3f) {
            x -= 25f
            controller.drag(x, y)
            controller.step(1f / 60f)
        }
        controller.endDrag(velocityX = -100f)
        settle()
        assertEquals(1, controller.currentIndex)
    }

    @Test
    fun flingTurnsEvenAfterAShortDrag() {
        val rect = controller.pageRect(0)
        val y = rect.top + rect.height / 2
        controller.beginDrag(rect.right - 10f, y, rect.right - 60f, y)
        controller.step(1f / 60f)
        controller.endDrag(velocityX = -4000f)
        settle()
        assertEquals(1, controller.currentIndex)
    }

    @Test
    fun fallingSheetCanBeCaughtAndTurned() {
        val rect = controller.pageRect(0)
        val y = rect.top + rect.height / 2
        controller.beginDrag(rect.right - 10f, y, rect.right - 200f, y)
        repeat(20) { controller.step(1f / 60f) }
        controller.endDrag(velocityX = 0f)
        repeat(3) { controller.step(1f / 60f) }
        val caughtAt = controller.currentCurl()
        assertNotNull("sheet should still be in the air", caughtAt)

        assertTrue(controller.beginDrag(rect.right - 100f, y, rect.left + 50f, y))
        // No snap: the sheet continues from where it was caught.
        assertEquals(caughtAt!!.axisX, controller.currentCurl()!!.axisX, 0.01f)
        repeat(30) { controller.step(1f / 60f) }
        controller.endDrag(velocityX = -100f)
        settle()
        assertEquals(1, controller.currentIndex)
    }

    @Test
    fun cannotTurnBeforeTheFirstPage() {
        val rect = controller.pageRect(0)
        assertFalse(controller.beginDrag(rect.left + 10f, 500f, rect.left + 80f, 500f))
        controller.turnBackward()
        assertFalse(controller.isTurning)
    }

    @Test
    fun lastPageOnlyLiftsAndFallsBack() {
        controller.jumpTo(9)
        val rect = controller.pageRect(9)
        assertTrue(controller.beginDrag(rect.right - 10f, 800f, rect.left, 800f))
        repeat(30) { controller.step(1f / 60f) }
        val curl = controller.currentCurl()!!
        // Rubber band: the edge cannot be pulled further than a quarter of the page.
        val (x, _, _) = CurlMath.deform(rect.right, 800f, curl)
        assertTrue(x > rect.right - rect.width * 0.3f)
        controller.endDrag(velocityX = -5000f)
        settle()
        assertEquals(9, controller.currentIndex)
    }

    @Test
    fun doubleTapZoomsAndPanIsClamped() {
        controller.doubleTap(540f, 1140f)
        settle()
        assertEquals(PageTurnController.DOUBLE_TAP_SCALE, controller.scale, 0.001f)
        assertTrue(controller.isZoomed)
        assertFalse("no page turning while zoomed", controller.beginDrag(900f, 1000f, 700f, 1000f))

        controller.panBy(100000f, 100000f)
        val rect = controller.pageRect(0)
        assertEquals(-rect.left * controller.scale, controller.offsetX, 0.5f)
        assertEquals(-rect.top * controller.scale, controller.offsetY, 0.5f)

        controller.doubleTap(540f, 1140f)
        settle()
        assertEquals(1f, controller.scale, 0.001f)
    }

    @Test
    fun jumpToReportsThePage() {
        controller.jumpTo(5)
        assertEquals(5, controller.currentIndex)
        assertEquals(listOf(5), changes)
        assertEquals(listOf(5, 6, 4, 7), controller.wantedPages())
    }
}
