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

    /** Top and bottom (pixels) of the comic in hand on a screen [width] by [height]. */
    private fun ShelfController.heldSpan(width: Int, height: Int): Triple<Float, Float, Pair<Float, Float>> {
        val frame = frame()
        val held = frame.comics.last()
        assertTrue(held.held)
        // The model is scaled to the comic: its corners are at ±0.5.
        val corners = listOf(-0.5f, 0.5f).flatMap { x -> listOf(-0.5f, 0.5f).flatMap { y -> listOf(-0.5f, 0.5f).map { z -> Vec3(x, y, z) } } }
        val screen = corners.map {
            val ndc = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(held.model, it))
            (ndc.x + 1f) / 2f * width to (1f - ndc.y) / 2f * height
        }
        return Triple(screen.minOf { it.second }, screen.maxOf { it.second }, screen.minOf { it.first } to screen.maxOf { it.first })
    }

    @Test
    fun theComicInHandIsInTheMiddleOfTheRoomItsCardLeaves() {
        // A short phone: the status bar at the top, the bar of gestures at the bottom.
        val c = controller(1080, 1920)
        c.setHandRoom(0f, 70f, 0f, 60f)
        val (x, y) = c.screenOf("c1", 1080, 1920)
        c.tap(x, y)
        c.settle()
        val (freeTop, freeBottom) = c.heldSpan(1080, 1920)
        // The card comes, taking the lower part of the screen.
        val cardTop = 1920f - 820f
        c.setHandRoom(0f, 70f, 0f, 820f)
        // The comic makes way smoothly, not with a jump.
        val path = arrayListOf(freeBottom)
        repeat(90) {
            c.step(1f / 60f)
            path += c.heldSpan(1080, 1920).second
        }
        val total = freeBottom - path.last()
        assertTrue("moved: $total", total > 100f)
        path.zipWithNext().forEach { (a, b) -> assertTrue("smoothly: $a -> $b", b <= a + 0.5f && a - b < total * 0.2f) }
        c.settle()
        val (top, bottom) = c.heldSpan(1080, 1920)
        assertTrue("above the card: $bottom", bottom < cardTop)
        assertTrue("below the status bar: $top", top > 70f)
        // Right in the middle between them, not high above the card.
        assertEquals("gap above ${top - 70f}, below ${cardTop - bottom}", top - 70f, cardTop - bottom, 20f)
        // With all that room it was a little larger.
        assertTrue(freeBottom - freeTop > bottom - top)

        // In landscape the card is on the right: in the middle of what is left of the screen.
        val wide = controller(2280, 1080)
        wide.setHandRoom(0f, 0f, 1000f, 0f)
        wide.take("c2")
        wide.settle()
        val (_, _, across) = wide.heldSpan(2280, 1080)
        assertTrue("left of the card: ${across.second}", across.second < 2280f - 1000f)
        assertEquals(across.first, 2280f - 1000f - across.second, 30f)
    }

    @Test
    fun theBookmarkOfAComicBeingReadStaysOnScreenToo() {
        val c = controller(1080, 1920)
        val cardTop = 1920f - 640f
        c.setHandRoom(0f, 70f, 0f, 640f)
        // c0 is being read: its bookmark rises out of its top.
        c.take("c0")
        c.settle()
        val (top, bottom) = c.heldSpan(1080, 1920)
        val frame = c.frame()
        val ribbon = frame.comics.last().ribbon!!
        val ribbonTop = (0 until ribbon.size / 9).minOf {
            val ndc = Mat4.transformPoint(frame.viewProj, Vec3(ribbon[it * 9], ribbon[it * 9 + 1], ribbon[it * 9 + 2]))
            (1f - ndc.y) / 2f * 1920f
        }
        assertTrue("the bookmark sticks out: $ribbonTop above $top", ribbonTop < top - 40f)
        assertTrue("and stays below the status bar: $ribbonTop", ribbonTop > 70f)
        assertTrue(bottom < cardTop)
        // The comic and its bookmark together are in the middle.
        assertEquals("gap above ${ribbonTop - 70f}, below ${cardTop - bottom}", ribbonTop - 70f, cardTop - bottom, 30f)
    }

    @Test
    fun aDisplayShowsFourAcrossUprightWithoutLabelsOrNamePlate() {
        val plain = controller()
        assertEquals(2, plain.columns)
        assertTrue(plain.frame().labels.isNotEmpty())
        assertTrue(plain.frame().plaque != null)

        val display = controller().apply { showcase = true }
        // Two across on a phone, as the plain bookcase, unless the reader asks for more.
        assertEquals(2, display.columns)
        display.columnsWanted = 4
        assertEquals(4, display.columns)
        val frame = display.frame()
        assertTrue(frame.labels.isEmpty())
        assertNull(frame.plaque)
        // Standing nearly upright: the cover faces the room.
        val comic = frame.comics.first()
        val normal = (Mat4.transformPoint(comic.model, Vec3(0f, 0f, 1f)) - Mat4.transformPoint(comic.model, Vec3.ZERO)).normalized()
        assertTrue("upright, z=${normal.z}", normal.z > 0.99f)
        // Back to the plain bookcase and two across: as it was.
        display.showcase = false
        display.columnsWanted = 2
        assertEquals(2, display.columns)
    }

    @Test
    fun theReaderChoosesHowManyAcrossAndAWideScreenStillFitsMore() {
        val c = controller()
        c.columnsWanted = 3
        assertEquals(3, c.columns)
        c.columnsWanted = 9
        assertEquals(4, c.columns)
        val wide = controller(2280, 1080).apply { columnsWanted = 3 }
        assertTrue(wide.columns >= 4)
    }

    @Test
    fun aDisplayStartsRightUnderItsHeader() {
        val c = controller().apply {
            showcase = true
            topRoom = 300f
        }
        c.settle()
        // The top of the first comic is seen below the header's bottom edge.
        val frame = c.frame()
        val first = frame.comics.first { it.id == "c0" }
        val top = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(first.model, Vec3(0f, 0.5f, 0f)))
        val y = (1f - top.y) / 2f * 2280f
        assertTrue("first comic at $y px, under a 300 px header", y > 300f)
        assertTrue("but not far below it, at $y px", y < 600f)
    }

    @Test
    fun showBackTurnsTheComicInHandToItsBackAndKeepsItThere() {
        val c = controller()
        c.take("c0")
        c.settle()
        fun facing(): Float {
            val held = c.frame().comics.last()
            val normal = Mat4.transformPoint(held.model, Vec3(0f, 0f, 1f)) - Mat4.transformPoint(held.model, Vec3.ZERO)
            return normal.normalized().z
        }
        assertTrue("starts facing front, z=${facing()}", facing() > 0.95f)
        c.showBack()
        c.settle()
        assertTrue("shows its back, z=${facing()}", facing() < -0.95f)
        c.showBack()
        c.settle()
        assertTrue("stays on its back, z=${facing()}", facing() < -0.95f)
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

    @Test
    fun aSeriesGetsASignAboveItInEveryRow() {
        val series = listOf(null, "Saga", "Saga", "Saga", null)
        val c = ShelfController(density = 2.75f).apply {
            setViewSize(1080, 2280)
            setItems(series.mapIndexed { i, name -> ShelfItem("c$i", 0.65f, 30, false, label = "label $i", series = name) })
        }
        val frame = c.frame()
        assertEquals(listOf("Saga" to 1, "Saga" to 2), frame.signs.map { it.text to it.span })
        assertEquals("label 3", frame.labels[3].text)
        // Above the comics, below the next shelf.
        val (_, signY) = screenOfModel(frame, frame.signs[1].model)
        val (_, comicY) = c.screenOf("c2")
        assertTrue(signY < comicY)
    }

    @Test
    fun aComicPickedInTheSearchIsBroughtIntoView() {
        val many = List(30) { ShelfItem("c$it", 0.65f, 24, false) }
        val c = ShelfController(density = 2.75f).apply {
            setViewSize(1080, 2280)
            setItems(many)
        }
        c.highlight(setOf("c29"))
        c.step(0.5f)
        val shaded = c.frame()
        assertTrue(shaded.searchDim > 0.5f)
        assertTrue(shaded.comics.filter { it.id != "c29" }.all { it.faded })

        c.highlight(null)
        c.focus("c29")
        c.settle()
        assertEquals("c29", c.heldId)
        assertEquals(0f, c.frame().searchDim, 0f)
        // The shelf it came from is on screen.
        val shelf = c.frame().comics.filter { it.id == "c28" }
        assertTrue(shelf.isNotEmpty())
        val (_, y) = screenOfModel(c.frame(), shelf.single().model)
        assertTrue("row of c29 is on screen: $y", y in 0f..2280f)
    }

    private fun screenOfModel(frame: ShelfFrame, model: FloatArray): Pair<Float, Float> {
        val ndc = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(model, Vec3.ZERO))
        return (ndc.x + 1f) / 2f * 1080f to (1f - ndc.y) / 2f * 2280f
    }

    @Test
    fun largerComicsOnTheSameShelves() {
        fun look(size: ShelfSize, width: Int, height: Int): Triple<Float, Float, Float> {
            val c = ShelfController(density = 2.75f).apply {
                setViewSize(width, height)
                this.size = size
                setItems(List(12) { ShelfItem("c$it", if (it == 1) 1.0f else 0.65f, 30, false) })
            }
            val frame = c.frame()
            val comic = frame.comics.single { it.id == "c0" }
            val left = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(comic.model, Vec3(-0.5f, 0f, 0.5f)))
            val right = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(comic.model, Vec3(0.5f, 0f, 0.5f)))
            // Neighbours never touch, even a square comic.
            val square = frame.comics.single { it.id == "c1" }
            val gap = Mat4.transformPoint(square.model, Vec3(-0.5f, 0f, 0f)).x - Mat4.transformPoint(comic.model, Vec3(0.5f, 0f, 0f)).x
            assertTrue("gap $gap", gap > 0.01f)
            // Where the first shelf is on screen.
            val plank = Mat4.transformPoint(frame.viewProj, Mat4.transformPoint(frame.planks.first(), Vec3.ZERO))
            return Triple((right.x - left.x) / 2f * width, plank.y, c.columns.toFloat())
        }
        for ((width, height) in listOf(1080 to 2280, 2280 to 1080)) {
            val small = look(ShelfSize.SMALL, width, height)
            val normal = look(ShelfSize.NORMAL, width, height)
            val large = look(ShelfSize.LARGE, width, height)
            assertTrue("about a tenth larger: ${normal.first / small.first}", normal.first / small.first in 1.1f..1.14f)
            assertTrue("larger still: ${large.first / small.first}", large.first / small.first > 1.18f)
            // As many on a shelf whatever their size.
            assertEquals(small.third, large.third, 0f)
        }
    }

    @Test
    fun theShelvesHugTheComicsWithJustRoomForTheBookmark() {
        for (size in ShelfSize.entries) {
            val c = ShelfController(density = 2.75f).apply {
                setViewSize(1080, 2280)
                this.size = size
                setItems(List(4) { ShelfItem("c$it", 0.65f, if (it == 0) 320 else 30, inProgress = it == 0) })
            }
            repeat(3) { c.step(1f / 60f) }
            val frame = c.frame()
            // The first shelf stands on y = 0; the board above it is the cornice.
            val ceiling = c.rowHeight - ShelfController.PLANK_THICKNESS
            val tops = frame.comics.map { comic ->
                listOf(-0.5f, 0.5f).maxOf { z -> Mat4.transformPoint(comic.model, Vec3(0f, 0.5f, z)).y }
            }
            val room = ceiling - tops.max()
            assertTrue("$size: little room above the comics: $room", room in 0.05f..0.07f)
            val ribbon = frame.comics.single { it.id == "c0" }.ribbon!!
            val ribbonTop = (1 until ribbon.size step 9).maxOf { ribbon[it] }
            assertTrue("$size: the bookmark under the next shelf: $ribbonTop < $ceiling", ribbonTop < ceiling - 0.003f)
        }
    }

    @Test
    fun theScrollIsKeptInShelvesWhenTheComicsChangeSize() {
        val c = ShelfController(density = 2.75f).apply {
            setViewSize(1080, 2280)
            setItems(List(40) { ShelfItem("c$it", 0.65f, 30, false) })
        }
        c.scroll = -3f
        c.size = ShelfSize.LARGE
        assertEquals(-3f, c.scroll, 1e-4f)
    }

    @Test
    fun largeComicsStillFitUnderTheNextShelfWithTheirSigns() {
        val c = ShelfController(density = 2.75f).apply {
            setViewSize(1080, 2280)
            size = ShelfSize.LARGE
            setItems(List(4) { ShelfItem("c$it", 0.65f, 30, false, series = "Saga") })
        }
        val frame = c.frame()
        val comic = frame.comics.single { it.id == "c0" }
        val top = Mat4.transformPoint(comic.model, Vec3(0f, 0.5f, 0.5f)).y
        val sign = frame.signs.first()
        val signBottom = Mat4.transformPoint(sign.model, Vec3(0f, -0.5f, 0f)).y
        val signTop = Mat4.transformPoint(sign.model, Vec3(0f, 0.5f, 0f)).y
        assertTrue("sign above the comics: $signBottom > $top", signBottom > top)
        assertTrue("sign under the next shelf: $signTop", signTop < c.rowHeight - ShelfController.PLANK_THICKNESS)
    }

    @Test
    fun theDisplayCaseLightsGoDownInThreeStepsFromHowItWasMade() {
        assertEquals(listOf(CaseLight.DIM, CaseLight.MEDIUM, CaseLight.BRIGHT), CaseLight.entries)
        // The brightest is the display case as it was made: the shaders take it unchanged.
        assertEquals(1f, CaseLight.BRIGHT.glow, 0f)
        assertTrue(CaseLight.DIM.glow > 0f && CaseLight.DIM.glow < CaseLight.MEDIUM.glow && CaseLight.MEDIUM.glow < CaseLight.BRIGHT.glow)
    }
}
