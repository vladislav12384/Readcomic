package com.readcomic.curl

import com.readcomic.curl.PageTurnController.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnControllerTest {
    private val changes = mutableListOf<Pair<Int, Int>>()
    private var turned = 0

    private fun controller(
        mode: ReadingMode,
        aspects: FloatArray = FloatArray(10) { 0.65f },
        width: Int = 1080,
        height: Int = 2280,
    ) = PageTurnController(density = 2f).apply {
        setViewSize(width, height)
        setReadingMode(mode)
        setPages(aspects, startImage = 0)
        listener = object : PageTurnController.Listener {
            override fun onPageChanged(first: Int, last: Int) {
                changes += first to last
            }

            override fun onPageTurned() {
                turned++
            }
        }
    }

    private fun PageTurnController.settle(maxSeconds: Float = 3f) {
        var t = 0f
        while (step(1f / 60f) && t < maxSeconds) t += 1f / 60f
        assertTrue("animation did not finish", t < maxSeconds)
    }

    // region Single page

    @Test
    fun tapTurnsForwardAndBack() {
        val c = controller(ReadingMode.SINGLE)
        c.turnForward()
        assertTrue(c.isTurning)
        c.settle()
        assertEquals(1, c.position)
        assertEquals(listOf(1 to 1), changes)
        assertEquals(1, turned)

        c.turnBackward()
        c.settle()
        assertEquals(0, c.position)
        assertFalse(c.isTurning)
    }

    @Test
    fun shortDragFallsBack() {
        val c = controller(ReadingMode.SINGLE)
        val rect = c.singleRect(0)
        val y = rect.top + rect.height / 2
        assertTrue(c.beginDrag(rect.right - 10f, y, rect.right - 40f, y))
        repeat(10) { c.step(1f / 60f) }
        assertNotNull(c.currentCurl())
        c.endDrag(velocityX = 0f)
        c.settle()
        assertEquals(0, c.position)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun longDragTurnsThePage() {
        val c = controller(ReadingMode.SINGLE)
        val rect = c.singleRect(0)
        val y = rect.top + rect.height / 2
        c.beginDrag(rect.right - 10f, y, rect.right - 40f, y)
        var x = rect.right - 40f
        while (x > rect.left + rect.width * 0.3f) {
            x -= 25f
            c.drag(x, y)
            c.step(1f / 60f)
        }
        c.endDrag(velocityX = -100f)
        c.settle()
        assertEquals(1, c.position)
    }

    @Test
    fun flingTurnsEvenAfterAShortDrag() {
        val c = controller(ReadingMode.SINGLE)
        val rect = c.singleRect(0)
        val y = rect.top + rect.height / 2
        c.beginDrag(rect.right - 10f, y, rect.right - 60f, y)
        c.step(1f / 60f)
        c.endDrag(velocityX = -4000f)
        c.settle()
        assertEquals(1, c.position)
    }

    @Test
    fun fallingSheetCanBeCaughtAndTurned() {
        val c = controller(ReadingMode.SINGLE)
        val rect = c.singleRect(0)
        val y = rect.top + rect.height / 2
        c.beginDrag(rect.right - 10f, y, rect.right - 200f, y)
        repeat(20) { c.step(1f / 60f) }
        c.endDrag(velocityX = 0f)
        repeat(3) { c.step(1f / 60f) }
        val caughtAt = c.currentCurl()
        assertNotNull("sheet should still be in the air", caughtAt)

        assertTrue(c.beginDrag(rect.right - 100f, y, rect.left + 50f, y))
        // No snap: the sheet continues from where it was caught.
        assertEquals(caughtAt!!.axisX, c.currentCurl()!!.axisX, 0.01f)
        repeat(30) { c.step(1f / 60f) }
        c.endDrag(velocityX = -100f)
        c.settle()
        assertEquals(1, c.position)
    }

    @Test
    fun cannotTurnBeforeTheFirstPage() {
        val c = controller(ReadingMode.SINGLE)
        val rect = c.singleRect(0)
        assertFalse(c.beginDrag(rect.left + 10f, 500f, rect.left + 80f, 500f))
        c.turnBackward()
        assertFalse(c.isTurning)
    }

    @Test
    fun lastPageOnlyLiftsAndFallsBack() {
        val c = controller(ReadingMode.SINGLE)
        c.jumpTo(9)
        val rect = c.singleRect(9)
        assertTrue(c.beginDrag(rect.right - 10f, 800f, rect.left, 800f))
        repeat(30) { c.step(1f / 60f) }
        val curl = c.currentCurl()!!
        // Rubber band: the edge cannot be pulled further than a quarter of the page.
        val (x, _, _) = CurlMath.deform(rect.right, 800f, curl)
        assertTrue(x > rect.right - rect.width * 0.3f)
        c.endDrag(velocityX = -5000f)
        c.settle()
        assertEquals(9, c.position)
    }

    @Test
    fun singlePageBackIsBlankPaper() {
        val c = controller(ReadingMode.SINGLE)
        c.turnForward()
        repeat(5) { c.step(1f / 60f) }
        val sheet = c.scene().turning!!
        assertEquals(0, sheet.front.image)
        assertNull(sheet.back)
        assertEquals(listOf(1), c.scene().flats.map { it.front.image })
    }

    @Test
    fun doubleTapZoomsAndPanIsClamped() {
        val c = controller(ReadingMode.SINGLE)
        c.doubleTap(540f, 1140f)
        c.settle()
        assertEquals(PageTurnController.DOUBLE_TAP_SCALE, c.scale, 0.001f)
        assertTrue(c.isZoomed)
        assertFalse("no page turning while zoomed", c.beginDrag(900f, 1000f, 700f, 1000f))

        c.panBy(100000f, 100000f)
        val rect = c.singleRect(0)
        assertEquals(-rect.left * c.scale, c.offsetX, 0.5f)
        assertEquals(-rect.top * c.scale, c.offsetY, 0.5f)

        c.doubleTap(540f, 1140f)
        c.settle()
        assertEquals(1f, c.scale, 0.001f)
    }

    @Test
    fun tapTurnsWhileZoomedAndShowsTheTopOfTheNextPage() {
        val c = controller(ReadingMode.SINGLE)
        c.doubleTap(900f, 1800f)
        c.settle()
        val scale = c.scale
        assertTrue(c.isZoomed)

        c.turnForward()
        assertTrue("turning does not need zooming out first", c.isTurning)
        c.settle()
        assertEquals(1, c.position)
        assertEquals("zoom is kept", scale, c.scale, 0.001f)
        val rect = c.singleRect(1)
        assertEquals(-rect.left * scale, c.offsetX, 1f)
        assertEquals(-rect.top * scale, c.offsetY, 1f)

        c.turnBackward()
        c.settle()
        assertEquals(0, c.position)
        val previous = c.singleRect(0)
        assertEquals("back shows the end of the page", 1080f - previous.right * scale, c.offsetX, 1f)
        assertEquals(2280f - previous.bottom * scale, c.offsetY, 1f)
    }

    @Test
    fun portraitSplitsTwoPageImagesIntoHalves() {
        val aspects = floatArrayOf(0.65f, 0.65f, 1.3f, 0.65f)
        val c = controller(ReadingMode.SINGLE, aspects)
        c.jumpTo(3)
        assertEquals("the spread takes two positions", 4, c.position)
        c.turnBackward()
        c.settle()
        assertEquals(2 to 2, c.visibleImages())
        val right = c.scene().flats.single().front
        assertEquals(0.5f, right.sub[0], 0f)

        // In landscape the whole spread fits on screen, so it is one page again.
        c.setViewSize(2280, 1080)
        assertEquals(2, c.position)
        assertEquals(1f, c.scene().flats.single().front.sub[2], 0f)
    }

    @Test
    fun preparesPagesInTheReadingDirection() {
        val c = controller(ReadingMode.SINGLE, FloatArray(30) { 0.65f })
        c.jumpTo(10)
        assertEquals(listOf(10, 11, 12, 13, 9), c.wantedImages())
        c.turnBackward()
        c.settle()
        assertEquals(listOf(9, 8, 7, 6, 10), c.wantedImages())
        val previews = c.previewImages()
        assertEquals(9, previews.first())
        assertTrue(previews.containsAll((0..19).toList()))
    }

    // endregion

    // region Book spread

    @Test
    fun coverIsAloneOnTheRight() {
        val c = controller(ReadingMode.SPREAD)
        val scene = c.scene()
        assertEquals(1, scene.flats.size)
        assertFalse(scene.flats[0].mirror)
        assertEquals(0, scene.flats[0].front.image)
        assertEquals(0 to 0, c.visibleImages())
    }

    @Test
    fun onlyTheOutsideOfTheCoversIsGlossy() {
        val c = controller(ReadingMode.SPREAD)
        assertTrue("front cover", c.scene().flats.single().front.glossy)
        c.turnForward()
        repeat(5) { c.step(1f / 60f) }
        val leaf = c.scene().turning!!
        assertTrue(leaf.front.glossy)
        assertFalse("inside of the cover", leaf.back!!.glossy)
        c.settle()
        assertTrue(c.scene().flats.none { it.front.glossy })

        c.jumpTo(9)
        val back = c.scene().flats.single { it.front.image == 9 }
        assertTrue("back cover", back.front.glossy)
    }

    @Test
    fun turnedLeafShowsTheNextPageOnItsBack() {
        val c = controller(ReadingMode.SPREAD)
        c.turnForward()
        c.settle()
        assertEquals(1, c.position)
        assertEquals(1 to 2, c.visibleImages())
        assertEquals(listOf(1 to 2), changes)

        c.turnForward()
        repeat(10) { c.step(1f / 60f) }
        val scene = c.scene()
        val leaf = scene.turning!!
        assertEquals("front of the leaf is the right page", 2, leaf.front.image)
        assertEquals("its back is the next left page", 3, leaf.back!!.image)
        assertFalse(leaf.mirror)
        // Left page stays, the next right page is revealed underneath.
        assertEquals(listOf(1 to true, 4 to false), scene.flats.map { it.front.image to it.mirror })
        c.settle()
        assertEquals(3 to 4, c.visibleImages())
    }

    @Test
    fun turningBackIsMirrored() {
        val c = controller(ReadingMode.SPREAD)
        c.jumpTo(3)
        assertEquals(2, c.position)
        c.turnBackward()
        repeat(10) { c.step(1f / 60f) }
        val leaf = c.scene().turning!!
        assertTrue(leaf.mirror)
        assertEquals(3, leaf.front.image)
        assertEquals(2, leaf.back!!.image)
        assertEquals(listOf(1 to true, 4 to false), c.scene().flats.map { it.front.image to it.mirror })
        c.settle()
        assertEquals(1 to 2, c.visibleImages())
    }

    @Test
    fun leafLandsFlatOnTheOtherSide() {
        val c = controller(ReadingMode.SPREAD, width = 2280, height = 1080)
        val slot = c.rightSlot()
        val y = slot.top + slot.height / 2
        c.beginDrag(slot.right - 5f, y, slot.right - 50f, y)
        // Drag the edge all the way to where it lies on the left page.
        val landed = slot.left - slot.width
        c.drag(landed, y)
        repeat(120) { c.step(1f / 60f) }
        val curl = c.currentCurl()!!
        val (x, _, z) = CurlMath.deform(slot.right, y, curl)
        assertEquals(landed, x, 3f)
        assertTrue("lies flat, z=$z", z < 3f)
        c.endDrag(velocityX = 0f)
        c.settle()
        assertEquals(1, c.position)
    }

    @Test
    fun dragMustCrossTheSpine() {
        val c = controller(ReadingMode.SPREAD, width = 2280, height = 1080)
        val slot = c.rightSlot()
        val y = slot.top + slot.height / 2
        c.beginDrag(slot.right - 5f, y, slot.right - 50f, y)
        c.drag(slot.left + slot.width * 0.3f, y)
        repeat(30) { c.step(1f / 60f) }
        c.endDrag(velocityX = 0f)
        c.settle()
        assertEquals(0, c.position)

        // Dragging the left page to the right goes back.
        c.jumpTo(3)
        val left = slot.left - slot.width
        c.beginDrag(left + 5f, y, left + 60f, y)
        c.drag(slot.left + slot.width * 0.5f, y)
        repeat(30) { c.step(1f / 60f) }
        c.endDrag(velocityX = 0f)
        c.settle()
        assertEquals(1, c.position)
    }

    @Test
    fun wideImagesBecomeTwoFacingPages() {
        val aspects = floatArrayOf(0.65f, 0.65f, 0.65f, 0.65f, 0.65f, 1.3f, 0.65f, 0.65f)
        val c = controller(ReadingMode.SPREAD, aspects)
        c.jumpTo(5)
        assertEquals(3, c.position)
        assertEquals(5 to 5, c.visibleImages())
        val halves = c.scene().flats.associate { it.mirror to it.front }
        assertEquals(5, halves[true]!!.image)
        assertEquals(0f, halves[true]!!.sub[0], 0f)
        assertEquals(5, halves[false]!!.image)
        assertEquals(0.5f, halves[false]!!.sub[0], 0f)
    }

    @Test
    fun blankPageKeepsSpreadsAligned() {
        val c = controller(ReadingMode.SPREAD, floatArrayOf(0.65f, 0.65f, 1.3f))
        c.jumpTo(2)
        assertEquals(2, c.position)
        // Spread 1 is image 1 next to an inserted blank page.
        c.turnBackward()
        c.settle()
        assertEquals(1 to 1, c.visibleImages())
        assertTrue(c.scene().flats.single { !it.mirror }.front.isBlank)
    }

    @Test
    fun lastRightPageOnlyLifts() {
        val c = controller(ReadingMode.SPREAD, FloatArray(9) { 0.65f })
        c.jumpTo(8)
        assertEquals(7 to 8, c.visibleImages())
        c.turnForward()
        assertFalse(c.isTurning)
        val slot = c.rightSlot()
        assertTrue(c.beginDrag(slot.right - 5f, 800f, slot.left - 300f, 800f))
        repeat(30) { c.step(1f / 60f) }
        c.endDrag(velocityX = -5000f)
        c.settle()
        assertEquals(7 to 8, c.visibleImages())
    }

    @Test
    fun switchingLayoutKeepsThePage() {
        val c = controller(ReadingMode.SPREAD)
        c.jumpTo(4)
        assertEquals(3 to 4, c.visibleImages())
        c.setReadingMode(ReadingMode.SINGLE)
        assertFalse(c.isSpread)
        assertEquals(3, c.position)
        c.setReadingMode(ReadingMode.AUTO)
        assertFalse("portrait", c.isSpread)
        c.setViewSize(2280, 1080)
        assertTrue("landscape", c.isSpread)
        assertEquals(3 to 4, c.visibleImages())
    }

    @Test
    fun stacksGrowOnTheLeftAsYouRead() {
        val c = controller(ReadingMode.SPREAD, FloatArray(40) { 0.65f })
        c.jumpTo(1)
        val early = c.scene().stacks.associate { (it.dx > 0) to it }
        c.jumpTo(33)
        val late = c.scene().stacks.associate { (it.dx > 0) to it }
        assertTrue(late[false]!!.dy > (early[false]?.dy ?: 0f))
        assertTrue(late[true]!!.dy < early[true]!!.dy)
    }

    // endregion
}
