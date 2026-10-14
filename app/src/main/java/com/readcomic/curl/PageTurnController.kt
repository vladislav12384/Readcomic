package com.readcomic.curl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All the reading state that is not tied to OpenGL: the comic laid out as a book, the sheet
 * being turned (driven by a spring towards the finger or towards its final position) and zoom.
 *
 * Two layouts:
 * - **spread**: an open book. Leaf k carries page 2k on its front (right-hand side) and page
 *   2k + 1 on its back, so turning a leaf shows the next page on its reverse, and that page stays
 *   on the left. The cover is alone on the right, like a closed comic.
 * - **single**: one page at a time, the turned page flies off to the left.
 *
 * Images wider than tall are two-page spreads: in the book layout they are split into two
 * facing pages (a blank page is inserted if needed so the halves meet at the spine).
 *
 * Coordinates are view pixels. Not thread-safe: it lives on the render thread.
 */
class PageTurnController(private val density: Float) {

    interface Listener {
        /** The visible pages changed; image indices, equal for a single page. */
        fun onPageChanged(first: Int, last: Int)
        fun onPageTurned()

        /** The closed comic landed on the table. */
        fun onLanded() = Unit

        /** The comic lies closed on the table ([closed]) or was opened. */
        fun onClosedChanged(closed: Boolean) = Unit

        /** The comic is drawn for the first time (a closed one starts to fall). */
        fun onShown() = Unit
    }

    /**
     * Where the comic is while it arrives: lifted above the table by [lift] (0 lies on it, 1 is
     * where a hand holds it), turned by [angle] and tilted back by [tilt] (degrees) around the
     * centre of the closed comic ([pivotX], [pivotY]), shown at [scale] and moved by
     * ([shiftX], [shiftY]). [stacks] fades the edges of the pages in as it lands; [hidden]
     * before it appears at all.
     */
    class Pose(
        val lift: Float,
        val angle: Float,
        val tilt: Float,
        val pivotX: Float,
        val pivotY: Float,
        val scale: Float,
        val shiftX: Float,
        val shiftY: Float,
        val stacks: Float,
        val hidden: Boolean,
        /** How high "in the hand" is, as a part of the distance to the eye. */
        val height: Float = 0.12f,
        /** The lamp over the table, 1 while the comic lies closed, gone once it is open. */
        val spot: Float = 0f,
        /**
         * The table's light and shade (vignette, lamp, shadows): 0 is a plain colour, as the
         * shelves fade out to, and it comes up as the comic falls.
         */
        val ambience: Float = 1f,
    )

    enum class ReadingMode { AUTO, SPREAD, SINGLE }

    private enum class Part { FULL, LEFT, RIGHT, BLANK }

    private class Page(val image: Int, val part: Part)

    /** Path the finger "would" take for an animated turn: a quadratic Bézier curve. */
    private class Guide(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val duration: Float,
    ) {
        var t = 0f

        fun point(): Pair<Float, Float> {
            // Ease out: the corner reacts to the tap at once and slows down towards the end.
            val e = 1f - (1f - t) * (1f - t)
            val a = (1f - e) * (1f - e)
            val b = 2f * (1f - e) * e
            val c = e * e
            return (a * x0 + b * x1 + c * x2) to (a * y0 + b * y1 + c * y2)
        }
    }

    /**
     * A sheet being turned. Its geometry lives in "curl space" where the sheet always lies to
     * the right of its spine and is turned to the left; turning back in a book is mirrored.
     */
    private inner class Turn(
        val forward: Boolean,
        val spread: Boolean,
        /** Spread: the leaf. Single: the page being turned. */
        val sheet: Int,
        val grabRelY: Float,
    ) {
        val spring = Spring2D()
        var targetX = 0f
        var targetY = 0f
        var dragging = true
        var completing = false
        var guide: Guide? = null

        val mirrored get() = spread && !forward

        /** Opening the closed comic: lands on this position instead of the next one. */
        var openTo: Int? = null

        /** Pulling at the last page: it only lifts and falls back. */
        val rubber get() = forward && (if (spread) position >= spreadCount - 1 else sheet >= pages.size - 1)

        fun rect() = if (spread) rightSlot() else singleRect(sheet)
        fun grabX() = rect().right
        fun grabY() = rect().let { it.top + it.height * grabRelY }
        fun toCurlX(x: Float) = if (mirrored) 2f * spineX - x else x

        /** Single layout: the end where the sheet is gone, off the screen to the left. */
        fun hiddenX() = CurlMath.hiddenX(grabX(), rect(), 0f)

        fun endIsHidden() = !spread && forward == completing
    }

    /**
     * The comic arriving on the table closed: it falls from the hand, lands, and waits to be
     * opened at [image] (where reading continues).
     */
    private class Arrival(val image: Int) {
        /** Seconds since it started to fall; negative while waiting for the cover to load. */
        var time = -1f
        var waited = 0f
        var landed = false
    }

    /**
     * Opening at a bookmark: the cover, then bunches of the pages read so far, flip over one
     * after another (quick at first, slower towards the end) and the comic lands open there.
     */
    private inner class Riffle(val target: Int) {
        inner class Bunch(
            /** First leaf (book) or page (single) of the bunch, and the one after its last. */
            val first: Int,
            val end: Int,
            val start: Float,
            val relY: Float,
        ) {
            val spring = Spring2D()
            var guide: Guide? = null
            var flying = false
            var landed = false

            fun rect() = if (isSpread) rightSlot() else singleRect(first)
            fun grabX() = rect().right
            fun grabY() = rect().let { it.top + it.height * relY }

            /** How far over it is, 0 lying on the right to 1 landed. */
            fun progress(): Float = when {
                landed -> 1f
                !flying -> 0f
                isSpread -> ((grabX() - spring.x) / (2f * rect().width)).coerceIn(0f, 1f)
                else -> ((grabX() - spring.x) / rect().width).coerceIn(0f, 1f)
            }

            fun curl(): CurlGeometry? =
                CurlMath.compute(grabX(), grabY(), spring.x, spring.y, rect(), landFlat = isSpread)

            fun front(): Face? = if (isSpread) face(2 * first, false) else face(first, false)

            /** What shows on its back: the left page of the spread after it. */
            fun back(): Face? = if (isSpread) face(2 * end - 1, true) else null
        }

        val bunches: List<Bunch>
        var time = 0f

        init {
            // Units are leaves in a book, pages one at a time. At most a handful of bunches fly:
            // the cover alone, then the rest shared out evenly.
            val units = target
            val count = min(units, MAX_BUNCHES)
            val ends = (1..count).map { k -> if (k == 1) 1 else 1 + ((units - 1) * (k - 1).toFloat() / (count - 1)).toInt() }
                .let { list -> list.dropLast(1) + units }
                .distinct()
            var first = 0
            val total = RIFFLE_SPREAD_TIME + RIFFLE_STEP * (ends.size - 1)
            bunches = ends.mapIndexed { k, end ->
                val f = if (ends.size == 1) 0f else k.toFloat() / (ends.size - 1)
                // Released by a thumb: the first ones go in quick succession, the last ones linger.
                val start = if (ends.size == 1) 0f else total * f * f * 0.75f + total * 0.25f * f
                val relY = 0.99f - 0.05f * ((k * 7) % 5) / 4f
                Bunch(first, end, start, relY).also { first = end }
            }
        }

        val done get() = bunches.all { it.landed }

        /** How far the comic has opened: the cover's way over. */
        fun opening() = bunches.firstOrNull()?.progress() ?: 1f
    }

    private var riffle: Riffle? = null

    private class ZoomAnimation(
        val fromScale: Float, val fromX: Float, val fromY: Float,
        val toScale: Float, val toX: Float, val toY: Float,
    ) {
        var t = 0f
    }

    var listener: Listener? = null

    var viewWidth = 1f
        private set
    var viewHeight = 1f
        private set

    var readingMode = ReadingMode.AUTO
        private set

    /** Effective layout: a book spread or a single page. */
    var isSpread = true
        private set

    private var aspects = FloatArray(0)
    private var singlePages: List<Page> = emptyList()

    /** One page at a time in portrait: two-page images are shown as two pages. */
    private var splitWide = false

    /** Direction of the last move, so pages are prepared on the side the reader is heading to. */
    private var lastForward = true
    private var spreadPages: List<Page> = emptyList()
    private val pages get() = if (isSpread) spreadPages else singlePages
    private var slotAspect = DEFAULT_ASPECT

    /** Spread index in the book layout, page index in the single layout. */
    var position = 0
        private set

    val imageCount get() = aspects.size
    private val spreadCount get() = spreadPages.size / 2 + 1
    private val positionCount get() = if (isSpread) spreadCount else singlePages.size
    private val leafCount get() = (spreadPages.size + 1) / 2

    private var turn: Turn? = null

    /** Also read from the UI thread to decide what a tap means, hence volatile. */
    @Volatile
    var scale = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    private var zoomAnimation: ZoomAnimation? = null
    private var flingVx = 0f
    private var flingVy = 0f

    // Past an edge (after a fling, or let go while stretched) the page springs back on its own.
    private var bounceX = false
    private var bounceY = false

    // The gesture in progress is applied from where it started, not step by step, so the content
    // point under the fingers stays exactly under them (no drift adding up from many small steps).
    private var anchorOffsetX = 0f
    private var anchorOffsetY = 0f
    private var anchorFingerX = 0f
    private var anchorFingerY = 0f
    private var pinchScale = 1f
    private var pinchSpan = 1f
    private var pinchContentX = 0f
    private var pinchContentY = 0f
    private var focusX = 0f
    private var focusY = 0f

    val isZoomed get() = scale > 1.01f
    val isTurning get() = turn != null

    private var arrival: Arrival? = null

    /** Whether [Listener.onShown] was called for the comic set last. */
    private var shown = false

    /** The comic lies closed (or is still falling): a touch opens it. Read from the UI thread too. */
    @Volatile
    var isClosed = false
        private set

    /** Set by the renderer every frame: the cover can be shown, the comic may fall. */
    var coverReady = false

    // region Setup

    fun setViewSize(width: Int, height: Int) {
        viewWidth = max(width, 1).toFloat()
        viewHeight = max(height, 1).toFloat()
        updateLayout()
        resetZoom()
    }

    fun setReadingMode(mode: ReadingMode) {
        readingMode = mode
        updateLayout()
    }

    /**
     * @param imageAspects width / height of every page image, 0 where unknown.
     * @param startImage the image to open the comic at.
     */
    fun setPages(imageAspects: FloatArray, startImage: Int, closed: Boolean = false) {
        aspects = imageAspects.copyOf()
        splitWide = viewHeight > viewWidth
        singlePages = buildSinglePages()
        spreadPages = buildSpreadPages()
        slotAspect = typicalAspect()
        turn = null
        isSpread = wantsSpread()
        resetZoom()
        shown = false
        if (closed && pages.isNotEmpty()) {
            // The comic comes onto the table closed and opens where reading continues.
            arrival = Arrival(startImage)
            position = 0
            setClosed(true)
        } else {
            arrival = null
            position = positionOf(startImage)
            setClosed(false)
        }
    }

    private fun setClosed(closed: Boolean) {
        if (isClosed == closed) return
        isClosed = closed
        listener?.onClosedChanged(closed)
    }

    /** Aspect learnt from a decoded page; layouts are not rebuilt so nothing jumps while reading. */
    fun setPageAspect(image: Int, aspect: Float) {
        if (image !in aspects.indices || aspect <= 0f || aspect.isNaN()) return
        if (aspects[image] > 0f) return
        aspects[image] = aspect
        if (aspects.count { it > 0f } == 1) slotAspect = typicalAspect()
        if (!isZoomed) resetZoom()
    }

    private fun wantsSpread() = when (readingMode) {
        ReadingMode.SPREAD -> true
        ReadingMode.SINGLE -> false
        ReadingMode.AUTO -> viewWidth > viewHeight
    }

    private fun updateLayout() {
        val spread = wantsSpread()
        val split = viewHeight > viewWidth
        if (spread == isSpread && split == splitWide) return
        val anchor = visibleImages().first
        finishTurnNow()
        isSpread = spread
        if (split != splitWide) {
            splitWide = split
            singlePages = buildSinglePages()
        }
        position = positionOf(anchor)
        resetZoom()
        notifyPosition()
    }

    private fun buildSinglePages(): List<Page> {
        val list = ArrayList<Page>(aspects.size + 4)
        for (image in aspects.indices) {
            if (splitWide && isWide(image)) {
                list += Page(image, Part.LEFT)
                list += Page(image, Part.RIGHT)
            } else {
                list += Page(image, Part.FULL)
            }
        }
        return list
    }

    /** Width / height of a page (half of a two-page image), null while unknown. */
    private fun pageAspect(page: Page): Float? {
        val aspect = aspects.getOrElse(page.image) { 0f }.takeIf { it > 0f } ?: return null
        return if (page.part == Part.FULL) aspect else aspect / 2f
    }

    private fun isWide(image: Int) = aspects.getOrElse(image) { 0f } > WIDE_ASPECT

    private fun buildSpreadPages(): List<Page> {
        val list = ArrayList<Page>(aspects.size + 4)
        for (image in aspects.indices) {
            if (isWide(image)) {
                // The left half has to be a left-hand page (odd index) to meet its other half at the spine.
                if (list.size % 2 == 0) list += Page(-1, Part.BLANK)
                list += Page(image, Part.LEFT)
                list += Page(image, Part.RIGHT)
            } else {
                list += Page(image, Part.FULL)
            }
        }
        return list
    }

    private fun typicalAspect(): Float {
        val known = aspects.filter { it > 0f && it <= WIDE_ASPECT }.sorted()
        if (known.isNotEmpty()) return known[known.size / 2]
        val halves = aspects.filter { it > WIDE_ASPECT }.map { it / 2f }.sorted()
        return if (halves.isNotEmpty()) halves[halves.size / 2] else DEFAULT_ASPECT
    }

    private fun positionOf(image: Int): Int {
        if (pages.isEmpty()) return 0
        val index = pages.indexOfFirst { it.image == image }.takeIf { it >= 0 } ?: 0
        return if (isSpread) (index + 1) / 2 else index
    }

    // endregion

    // region Geometry

    /** Room around the pages, kept small: on a phone every pixel of the page counts. */
    private val margin get() = (if (isSpread) 6f else 4f) * density

    private val spineX get() = viewWidth / 2f

    /** Right-hand page of the spread; the left one is its mirror image around the spine. */
    fun rightSlot(): PageRect {
        val availableWidth = (viewWidth - 2f * margin) / 2f
        val availableHeight = viewHeight - 2f * margin
        var width = availableWidth
        var height = width / slotAspect
        if (height > availableHeight) {
            height = availableHeight
            width = height * slotAspect
        }
        return PageRect(spineX, (viewHeight - height) / 2f, width, height)
    }

    private fun leftSlot(): PageRect = rightSlot().let { PageRect(it.left - it.width, it.top, it.width, it.height) }

    /** A page on its own, fitted into the view. */
    fun singleRect(index: Int): PageRect {
        val aspect = singlePages.getOrNull(index)?.let { pageAspect(it) } ?: slotAspect
        val availableWidth = viewWidth - 2f * margin
        val availableHeight = viewHeight - 2f * margin
        var width = availableWidth
        var height = width / aspect
        if (height > availableHeight) {
            height = availableHeight
            width = height * aspect
        }
        return PageRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }

    /** What is visible at the current position (for zoom limits). */
    private fun contentRect(): PageRect {
        if (!isSpread) return singleRect(position)
        val hasLeft = pages.getOrNull(2 * position - 1) != null
        val hasRight = pages.getOrNull(2 * position) != null
        val right = rightSlot()
        return when {
            hasLeft && !hasRight -> leftSlot()
            hasRight && !hasLeft -> right
            else -> PageRect(right.left - right.width, right.top, right.width * 2f, right.height)
        }
    }

    private fun face(index: Int, flip: Boolean): Face? {
        val page = pages.getOrNull(index) ?: return null
        if (page.part == Part.BLANK) return Face.blank(flip)
        val sub = when (page.part) {
            Part.LEFT -> Face.LEFT_HALF
            Part.RIGHT -> Face.RIGHT_HALF
            else -> Face.FULL
        }
        // The first and the last page are the outside of the covers.
        val glossy = page.image == 0 || page.image == imageCount - 1
        if (!isSpread) return Face(page.image, Face.FULL, sub, flip, glossy)
        val imageAspect = aspects[page.image].takeIf { it > 0f } ?: slotAspect
        val pageAspect = if (page.part == Part.FULL) imageAspect else imageAspect / 2f
        return Face(page.image, letterbox(pageAspect, slotAspect), sub, flip, glossy)
    }

    /** Where a page of [pageAspect] sits on a sheet of [sheetAspect]; small differences are stretched away. */
    private fun letterbox(pageAspect: Float, sheetAspect: Float): FloatArray {
        val ratio = pageAspect / sheetAspect
        return when {
            abs(ratio - 1f) < 0.07f -> Face.FULL
            ratio > 1f -> (1f / ratio).let { h -> floatArrayOf(0f, (1f - h) / 2f, 1f, h) }
            else -> floatArrayOf((1f - ratio) / 2f, 0f, ratio, 1f)
        }
    }

    // endregion

    // region What the renderer needs

    fun scene(): Scene {
        riffle?.let { return riffleScene(it) }
        val t = turn
        val curl = currentCurl()
        return if (isSpread) spreadScene(t, curl) else singleScene(t, curl)
    }

    /** The comic opening at its bookmark: bunches of pages waiting, in the air and landed. */
    private fun riffleScene(r: Riffle): Scene {
        val waiting = r.bunches.firstOrNull { !it.flying }
        val lastLanded = r.bunches.lastOrNull { it.landed }
        // The earliest ones are on top: they were above the others on the pile.
        val flying = r.bunches.filter { it.flying && !it.landed }.reversed().map { b ->
            Sheet(b.rect(), false, b.front()!!, b.back(), b.curl())
        }
        val flats = ArrayList<Sheet>(2)
        val stacks = ArrayList<StackEdge>(2)
        if (isSpread) {
            val slot = rightSlot()
            lastLanded?.let { b -> b.back()?.let { flats += Sheet(slot, true, it, null, null) } }
            val rightIndex = waiting?.let { 2 * it.first } ?: (2 * r.target)
            face(rightIndex, false)?.let { flats += Sheet(slot, false, it, null, null) }
            lastLanded?.let { b ->
                stackEdge(leftSlot(), b.end - 1, -1f)?.let { stacks += it }
            }
            if (rightIndex < pages.size) stackEdge(slot, leafCount - rightIndex / 2 - 1, 1f)?.let { stacks += it }
            val first = r.bunches.first().progress()
            val leftAlpha = if (lastLanded != null) 1f else first * first
            val drops = listOf(DropShadow(leftSlot(), leftAlpha), DropShadow(slot, 1f)).filter { it.alpha > 0.002f }
            return Scene(flats, null, stacks, drops, gutter = 0.16f, flying = flying)
        }
        val top = waiting?.first ?: r.target
        face(r.target, false)?.let { flats += Sheet(singleRect(r.target), false, it, null, null) }
        if (top != r.target) face(top, false)?.let { flats += Sheet(singleRect(top), false, it, null, null) }
        val rect = singleRect(top)
        stackEdge(rect, pages.size - 1 - top, 1f)?.let { stacks += it }
        return Scene(flats, null, stacks, listOf(DropShadow(rect, 1f)), gutter = 0.10f, flying = flying)
    }

    private fun spreadScene(t: Turn?, curl: CurlGeometry?): Scene {
        val s = position
        val slot = rightSlot()
        // Pages lying flat on the left and on the right while the leaf (if any) is in the air.
        val leftIndex: Int
        val rightIndex: Int
        var turning: Sheet? = null
        when {
            t == null -> {
                leftIndex = 2 * s - 1
                rightIndex = 2 * s
            }
            t.forward && t.openTo != null -> {
                // Opening at a bookmark: the cover and every page read so far go over at once,
                // showing the left page of the spread reading continues at.
                val open = t.openTo!!
                leftIndex = -1
                rightIndex = 2 * open
                turning = Sheet(slot, false, face(0, false)!!, face(2 * open - 1, true), curl)
            }
            t.forward -> {
                leftIndex = 2 * s - 1
                rightIndex = 2 * s + 2
                turning = Sheet(slot, false, face(2 * t.sheet, false)!!, face(2 * t.sheet + 1, true), curl)
            }
            else -> {
                leftIndex = 2 * s - 3
                rightIndex = 2 * s
                turning = Sheet(slot, true, face(2 * t.sheet + 1, true)!!, face(2 * t.sheet, false), curl)
            }
        }
        val flats = ArrayList<Sheet>(2)
        face(leftIndex, true)?.let { flats += Sheet(slot, true, it, null, null) }
        face(rightIndex, false)?.let { flats += Sheet(slot, false, it, null, null) }

        val stacks = ArrayList<StackEdge>(2)
        val left = leftSlot()
        if (leftIndex >= 0 && leftIndex < pages.size) {
            // Left page 2j - 1 is the back of leaf j - 1; leaves 0 .. j - 2 lie below it.
            val below = (leftIndex + 1) / 2 - 1
            stackEdge(left, below, -1f)?.let { stacks += it }
        }
        if (rightIndex < pages.size) {
            // Right page 2j is the front of leaf j; leaves j + 1 .. lie below it.
            val below = leafCount - rightIndex / 2 - 1
            stackEdge(slot, below, 1f)?.let { stacks += it }
        }

        // The table shadow of an empty side fades in as a leaf lands there (and out as it leaves).
        val progress = t?.let { ((it.grabX() - it.spring.x) / (2f * slot.width)).coerceIn(0f, 1f) } ?: 0f
        val arriving = progress * progress
        val leaving = (1f - progress) * (1f - progress)
        val leftAlpha = when {
            flats.any { it.mirror } -> 1f
            t == null -> 0f
            t.forward -> arriving
            else -> leaving
        }
        val rightAlpha = when {
            flats.any { !it.mirror } -> 1f
            t == null -> 0f
            t.forward -> leaving
            else -> arriving
        }
        val drops = listOf(DropShadow(left, leftAlpha), DropShadow(slot, rightAlpha)).filter { it.alpha > 0.002f }
        return Scene(flats, turning, stacks, drops, gutter = 0.16f)
    }

    private fun singleScene(t: Turn?, curl: CurlGeometry?): Scene {
        val under = when {
            t == null -> position
            t.openTo != null -> t.openTo!!
            t.forward -> position + 1
            else -> position
        }.takeIf { it in pages.indices }
        val flats = ArrayList<Sheet>(1)
        under?.let { index -> face(index, false)?.let { flats += Sheet(singleRect(index), false, it, null, null) } }
        val turning = t?.let { Sheet(singleRect(it.sheet), false, face(it.sheet, false)!!, null, curl) }
        val stackIndex = under ?: t?.sheet ?: position
        val rect = singleRect(stackIndex)
        val stacks = listOfNotNull(stackEdge(rect, pages.size - 1 - stackIndex, 1f))
        val drops = if (under != null || t != null) listOf(DropShadow(rect, 1f)) else emptyList()
        return Scene(flats, turning, stacks, drops, gutter = 0.10f)
    }

    private fun stackEdge(rect: PageRect, sheets: Int, side: Float): StackEdge? {
        if (sheets <= 0) return null
        val thickness = min(sqrt(sheets.toFloat()) * 0.9f * density, margin * 0.8f)
        val lines = max(1f, min(sheets.toFloat(), thickness / (1.5f * density)))
        return StackEdge(rect, side * thickness * 0.55f, thickness, lines)
    }

    fun currentCurl(): CurlGeometry? {
        val t = turn ?: return null
        return CurlMath.compute(t.grabX(), t.grabY(), t.spring.x, t.spring.y, t.rect(), landFlat = t.spread)
    }

    /**
     * Images to keep sharp, the most important first: what is on screen, then the pages the next
     * two turns in the reading direction will show, then one turn the other way.
     */
    fun wantedImages(): List<Int> {
        val closed = arrival
        if (closed != null) {
            // The cover now, what opening it will show, and the pages flying past on the way.
            val open = openingTarget(closed) ?: 0
            val shown = if (isSpread) listOf(0, 2 * open - 1, 2 * open, 1) else listOf(0, open, 1)
            val passing = riffle?.bunches?.flatMap { b -> if (isSpread) listOf(2 * b.first, 2 * b.end - 1) else listOf(b.first) }.orEmpty()
            return images(shown + passing)
        }
        val order = if (isSpread) {
            val s = position
            val forward = listOf(2 * s + 1, 2 * s + 2, 2 * s + 3, 2 * s + 4)
            val backward = listOf(2 * s - 2, 2 * s - 3, 2 * s - 4, 2 * s - 5)
            listOf(2 * s, 2 * s - 1) + if (lastForward) forward + backward.take(2) else backward + forward.take(2)
        } else {
            val p = position
            val forward = listOf(p + 1, p + 2, p + 3)
            val backward = listOf(p - 1, p - 2, p - 3)
            listOf(p) + if (lastForward) forward + backward.take(1) else backward + forward.take(1)
        }
        return images(order)
    }

    /** Images worth having at least a small, blurry version of, so nothing is ever blank. */
    fun previewImages(): List<Int> {
        val center = if (isSpread) 2 * position else position
        val order = (0..PREVIEW_RADIUS).flatMap { d ->
            if (lastForward) listOf(center + d, center - d) else listOf(center - d, center + d)
        }
        return images(order)
    }

    private fun images(indices: List<Int>) =
        indices.mapNotNull { pages.getOrNull(it)?.image?.takeIf { image -> image >= 0 } }.distinct()

    /** Images on screen right now (both halves of a spread). */
    fun visibleImageSet(): Set<Int> {
        val (first, last) = visibleImages()
        return if (first < 0) emptySet() else (first..last).toSet()
    }

    /** Size of the box an image is shown in, to decode it at a sensible resolution. */
    fun displayBox(image: Int): Pair<Float, Float> {
        if (!isSpread) return if (splitWide && isWide(image)) viewWidth * 2f to viewHeight else viewWidth to viewHeight
        val slot = rightSlot()
        return if (isWide(image)) slot.width * 2f to slot.height else slot.width to slot.height
    }

    // endregion

    // region Turning

    fun beginDrag(downX: Float, downY: Float, x: Float, y: Float): Boolean {
        if (isZoomed || pages.isEmpty()) return false
        val closed = arrival
        if (closed != null) {
            // The cover can be pulled open once the comic lies on the table.
            if (!closed.landed || x >= downX) return false
            val target = openingTarget(closed) ?: return false
            finishTurnNow()
            val t = newTurn(forward = true, relY = 0f) ?: return false
            val rect = t.rect()
            val rel = ((downY - rect.top) / rect.height).coerceIn(0.02f, 0.98f)
            val turn = Turn(true, t.spread, t.sheet, rel)
            turn.openTo = target
            turn.spring.snapTo(turn.grabX(), turn.grabY())
            this.turn = turn
            drag(x, y)
            return true
        }
        // A sheet that is falling back can be caught mid-air and pulled again.
        val falling = turn
        if (falling != null && !falling.dragging && !falling.completing && falling.guide == null) {
            falling.dragging = true
            drag(x, y)
            return true
        }
        finishTurnNow()
        val forward = x < downX
        val t = newTurn(forward, relY = 0f) ?: return false
        val rect = t.rect()
        var rel = ((downY - rect.top) / rect.height).coerceIn(0.02f, 0.98f)
        // Grabbing close to a corner grabs the corner itself.
        if (rel > 0.86f) rel = 0.99f
        if (rel < 0.14f) rel = 0.01f
        val turn = Turn(t.forward, t.spread, t.sheet, rel)
        if (turn.spread || turn.forward) {
            turn.spring.snapTo(turn.grabX(), turn.grabY())
        } else {
            turn.spring.snapTo(turn.hiddenX(), turn.grabY())
        }
        this.turn = turn
        drag(x, y)
        return true
    }

    /** Which sheet a turn in this direction would move, or null if there is nothing to turn. */
    private fun newTurn(forward: Boolean, relY: Float): Turn? {
        if (isSpread) {
            val s = position
            return if (forward) {
                // Needs a right-hand page to lift (the last one only lifts a little).
                if (2 * s >= pages.size) null else Turn(true, true, s, relY)
            } else {
                if (s <= 0) null else Turn(false, true, s - 1, relY)
            }
        }
        return if (forward) {
            Turn(true, false, position, relY)
        } else {
            if (position <= 0) null else Turn(false, false, position - 1, relY)
        }
    }

    fun drag(x: Float, y: Float) {
        val t = turn ?: return
        if (!t.dragging) return
        val cx = t.toCurlX(x)
        if (t.rubber) {
            val travel = max(t.grabX() - cx, 0f)
            val limit = t.rect().width * 0.22f
            t.targetX = t.grabX() - limit * (1f - exp(-travel / limit))
            t.targetY = t.grabY() + (y - t.grabY()) * 0.3f
        } else {
            t.targetX = cx
            t.targetY = y
        }
    }

    fun endDrag(velocityX: Float) {
        val t = turn ?: return
        if (!t.dragging) return
        t.dragging = false
        val rect = t.rect()
        val fling = 500f * density
        val vx = if (t.mirrored) -velocityX else velocityX
        val x = t.targetX
        t.completing = when {
            t.rubber -> false
            // In a book the leaf goes over once the finger has crossed the spine.
            t.spread -> vx < -fling || (vx < fling && x < rect.left)
            t.forward -> vx < -fling || (vx < fling && x < rect.left + rect.width * 0.6f)
            else -> vx > fling || (vx > -fling && x > rect.left + rect.width * 0.4f)
        }
        aimAtEnd(t)
    }

    fun cancelDrag() {
        val t = turn ?: return
        if (!t.dragging) return
        t.dragging = false
        t.completing = false
        aimAtEnd(t)
    }

    /** Opens the closed comic with an animation (a touch on it, or a key). */
    fun open() {
        val closed = arrival ?: return
        if (turn != null || riffle != null) return
        if (!closed.landed) {
            // Impatient: it lands at once.
            closed.time = DROP_DURATION + SETTLE_DURATION
            closed.landed = true
        }
        val target = openingTarget(closed)
        if (target == null) {
            arrival = null
            setClosed(false)
            notifyPosition()
            return
        }
        if (riffle == null) riffle = Riffle(target)
    }

    /** How the closed comic is placed while it arrives and opens; null while simply reading. */
    fun pose(): Pose? {
        val closed = arrival ?: return null
        val rect = if (isSpread) rightSlot() else singleRect(0)
        val pivotX = rect.left + rect.width / 2f
        val pivotY = rect.top + rect.height / 2f
        // Closed, the comic lies in the middle of the table; a book slides over as it opens, so
        // that its spine ends up in the middle.
        val t = turn
        val opening = when {
            riffle != null -> riffle!!.opening()
            t == null || t.openTo == null -> 0f
            isSpread -> ((t.grabX() - t.spring.x) / (2f * t.rect().width)).coerceIn(0f, 1f)
            else -> ((t.grabX() - t.spring.x) / t.rect().width).coerceIn(0f, 1f)
        }
        val eased = opening * opening * (3f - 2f * opening)
        val shiftX = if (isSpread) -rect.width / 2f * (1f - eased) else 0f
        // Lying closed it is seen whole with the table around it; opening it brings it close.
        val rest = if (viewHeight > viewWidth) CLOSED_SCALE_PORTRAIT else CLOSED_SCALE_LANDSCAPE
        val scale = rest + (1f - rest) * eased
        // In the hand it is as large as the screen allows; that sets how high the hand is.
        val height = (1f - rest / IN_HAND_SCALE).coerceIn(0.02f, 0.3f)
        val spot = 1f - eased
        if (closed.time < 0f) {
            return Pose(1f, 0f, 0f, pivotX, pivotY, scale, shiftX, 0f, 0f, hidden = true, height = height, spot = spot, ambience = 0f)
        }
        val light = (closed.time / AMBIENCE_DURATION).coerceIn(0f, 1f)
        val ambience = light * light * (3f - 2f * light)

        // It comes to rest a little askew and off centre, as a comic put down on a table does,
        // and straightens out as it opens.
        val restAngle = REST_ANGLE * (1f - eased)
        val restX = rect.width * REST_SHIFT_X * (1f - eased)
        val restY = rect.height * REST_SHIFT_Y * (1f - eased)
        val lift: Float
        val angle: Float
        val tilt: Float
        val drift: Float
        if (closed.time < DROP_DURATION) {
            // Falling: slow at first, faster and faster, tipping a little on the way down.
            val u = closed.time / DROP_DURATION
            lift = 1f - u * u
            angle = restAngle * 1.5f * (1f - (1f - u) * (1f - u))
            tilt = DROP_TILT * sin(PI.toFloat() * u)
            drift = u * u
        } else {
            // A small hop where it hits the table, a short slide, and it settles.
            val since = closed.time - DROP_DURATION
            val decay = exp(-since * 10f)
            lift = 0.03f * decay * abs(sin(since * 17f))
            angle = restAngle * (1f + 0.5f * exp(-since * 7f) * cos(since * 11f))
            tilt = DROP_TILT * 0.06f * decay * sin(since * 17f)
            drift = 1f
        }
        val stacks = 1f - (lift / 0.12f).coerceIn(0f, 1f)
        return Pose(
            lift, angle, tilt, pivotX, pivotY, scale,
            shiftX + restX * drift, restY * drift, stacks, hidden = false, height = height, spot = spot,
            ambience = ambience,
        )
    }

    /** Where opening lands: the place reading continues, at least past the cover. */
    private fun openingTarget(closed: Arrival): Int? {
        val last = positionCount - 1
        if (last < 1) return null
        return positionOf(closed.image).coerceIn(1, last)
    }

    /** Turn with an animation, as if the bottom corner was picked up (tap or volume key). */
    fun turnForward() = if (arrival != null) open() else animatedTurn(forward = true)

    fun turnBackward() {
        if (arrival == null) animatedTurn(forward = false)
    }

    private fun animatedTurn(forward: Boolean) {
        if (pages.isEmpty()) return
        finishTurnNow()
        val t = newTurn(forward, relY = 0.99f) ?: return
        if (t.rubber) return
        // Zoom stays: the page turns under the magnifier and the new one is shown from its start.
        zoomAnimation = null
        flingVx = 0f
        flingVy = 0f
        t.dragging = false
        t.completing = true
        val r = t.rect()
        if (t.spread) {
            // Over the spine in an arc, landing flat on the other side.
            t.spring.snapTo(t.grabX(), t.grabY())
            aimAtEnd(t)
            t.guide = Guide(
                t.grabX(), t.grabY(),
                r.left, t.grabY() - r.height * 0.55f,
                t.targetX, t.targetY,
                SPREAD_GUIDE_DURATION,
            )
        } else if (forward) {
            t.spring.snapTo(t.grabX(), t.grabY())
            aimAtEnd(t)
            t.targetY = t.grabY() - r.height * 0.15f
            t.guide = Guide(
                t.grabX(), t.grabY(),
                t.grabX() - r.width * 0.55f, t.grabY() - r.height * 0.4f,
                t.targetX, t.targetY,
                GUIDE_DURATION,
            )
        } else {
            val hiddenX = t.hiddenX()
            t.spring.snapTo(hiddenX, t.grabY() - r.height * 0.15f)
            aimAtEnd(t)
            t.guide = Guide(
                hiddenX, t.grabY() - r.height * 0.15f,
                t.grabX() - r.width * 0.55f, t.grabY() - r.height * 0.4f,
                t.grabX(), t.grabY(),
                GUIDE_DURATION,
            )
        }
        turn = t
    }

    /** Jumps to the position that shows [image]. */
    fun jumpTo(image: Int) {
        if (pages.isEmpty()) return
        finishTurnNow()
        val target = positionOf(image.coerceIn(0, imageCount - 1))
        if (target == position) return
        lastForward = target > position
        position = target
        resetZoom()
        notifyPosition()
    }

    /** First and last image on screen, (-1, -1) if nothing. */
    fun visibleImages(): Pair<Int, Int> {
        val images = if (isSpread) {
            listOfNotNull(pages.getOrNull(2 * position - 1), pages.getOrNull(2 * position))
        } else {
            listOfNotNull(pages.getOrNull(position))
        }.map { it.image }.filter { it >= 0 }
        return if (images.isEmpty()) -1 to -1 else images.min() to images.max()
    }

    private fun aimAtEnd(t: Turn) {
        when {
            t.spread && t.completing -> {
                t.targetX = CurlMath.landedX(t.grabX(), t.rect())
                t.targetY = t.grabY()
            }
            t.endIsHidden() -> {
                t.targetX = t.hiddenX()
                t.targetY = t.spring.y
            }
            else -> {
                t.targetX = t.grabX()
                t.targetY = t.grabY()
            }
        }
    }

    private fun finishTurnNow() {
        val t = turn ?: return
        turn = null
        if (!t.dragging && t.completing) applyTurn(t)
    }

    private fun applyTurn(t: Turn) {
        val opened = t.openTo
        if (opened != null) {
            arrival = null
            setClosed(false)
        }
        position = (opened ?: if (t.forward) position + 1 else position - 1).coerceIn(0, max(positionCount - 1, 0))
        lastForward = t.forward
        if (isZoomed) {
            // Keep the magnification and glide to where reading continues: the top left corner
            // of the next page, or the bottom right one when going back.
            val r = contentRect()
            if (t.forward) {
                animateZoomTo(scale, -r.left * scale, -r.top * scale)
            } else {
                animateZoomTo(scale, viewWidth - r.right * scale, viewHeight - r.bottom * scale)
            }
        }
        notifyPosition()
        listener?.onPageTurned()
    }

    private fun notifyPosition() {
        // While the comic lies closed, the place where reading continues must not be forgotten.
        if (arrival != null) return
        val (first, last) = visibleImages()
        if (first >= 0) listener?.onPageChanged(first, last)
    }

    // endregion

    // region Zoom

    fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        if (turn != null) cancelDrag()
        zoomAnimation = null
        flingVx = 0f
        flingVy = 0f
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        offsetX = focusX - (focusX - offsetX) * newScale / scale
        offsetY = focusY - (focusY - offsetY) * newScale / scale
        scale = newScale
        clampOffsets()
    }

    fun endScale() {
        if (scale < 1.1f) animateZoomTo(1f, 0f, 0f)
    }

    fun panBy(dx: Float, dy: Float) {
        zoomAnimation = null
        flingVx = 0f
        flingVy = 0f
        offsetX += dx
        offsetY += dy
        clampOffsets()
    }

    fun flingPan(velocityX: Float, velocityY: Float) {
        flingVx = velocityX
        flingVy = velocityY
        bounceX = false
        bounceY = false
    }

    /** A finger touched the page: a thrown page stops, one stretched past an edge still settles. */
    fun catchFling() {
        if (!bounceX) flingVx = 0f
        if (!bounceY) flingVy = 0f
    }

    /** A finger starts moving the zoomed page from ([x], [y]). */
    fun beginPan(x: Float, y: Float) {
        stopMotion()
        // Continue from where a stretch past the edge left the page, without a jump.
        anchorOffsetX = unstretch(offsetX, limitsX(scale), viewWidth)
        anchorOffsetY = unstretch(offsetY, limitsY(scale), viewHeight)
        anchorFingerX = x
        anchorFingerY = y
        focusX = x
        focusY = y
    }

    /** The finger is at ([x], [y]): the page follows it, giving way softly past the edges. */
    fun panTo(x: Float, y: Float) {
        offsetX = stretch(anchorOffsetX + x - anchorFingerX, limitsX(scale), viewWidth)
        offsetY = stretch(anchorOffsetY + y - anchorFingerY, limitsY(scale), viewHeight)
        focusX = x
        focusY = y
    }

    /** The finger left with a velocity in pixels per second: glide on, bounce off the edges. */
    fun endPan(velocityX: Float, velocityY: Float) {
        if (scale < 1f || scale > MAX_SCALE) {
            settleZoom()
            return
        }
        flingVx = velocityX
        flingVy = velocityY
        // Already stretched past an edge: straight back, the throw only softens the return.
        bounceX = offsetX !in limitsX(scale)
        bounceY = offsetY !in limitsY(scale)
        if (bounceX) flingVx *= 0.3f
        if (bounceY) flingVy *= 0.3f
    }

    /** Two fingers start a pinch centred at ([fx], [fy]), [span] apart. */
    fun beginPinch(fx: Float, fy: Float, span: Float) {
        if (arrival != null) return
        if (turn != null) cancelDrag()
        stopMotion()
        pinchScale = unstretchScale(scale)
        pinchSpan = max(span, 1f)
        pinchContentX = (fx - offsetX) / scale
        pinchContentY = (fy - offsetY) / scale
        focusX = fx
        focusY = fy
    }

    /** The pinch is now centred at ([fx], [fy]) and [span] apart. */
    fun pinchTo(fx: Float, fy: Float, span: Float) {
        if (arrival != null) return
        val s = stretchScale(pinchScale * max(span, 1f) / pinchSpan)
        scale = s
        offsetX = stretch(fx - pinchContentX * s, limitsX(s), viewWidth)
        offsetY = stretch(fy - pinchContentY * s, limitsY(s), viewHeight)
        focusX = fx
        focusY = fy
    }

    fun endPinch() = settleZoom()

    /** Brings a stretched zoom or position back into range, around the last point the fingers held. */
    private fun settleZoom() {
        val target = if (scale < 1.1f) 1f else scale.coerceIn(1f, MAX_SCALE)
        val contentX = (focusX - offsetX) / scale
        val contentY = (focusY - offsetY) / scale
        val (x, y) = clamp(target, focusX - contentX * target, focusY - contentY * target)
        if (target != scale || abs(x - offsetX) > 0.5f || abs(y - offsetY) > 0.5f) {
            animateZoomTo(target, x, y)
        } else if (target <= 1f) {
            resetZoom()
        }
    }

    private fun stopMotion() {
        zoomAnimation = null
        flingVx = 0f
        flingVy = 0f
        bounceX = false
        bounceY = false
    }

    private fun limitsX(s: Float): ClosedFloatingPointRange<Float> {
        if (s <= 1f) return 0f..0f
        val r = contentRect()
        if (r.width * s <= viewWidth) return (viewWidth / 2f - (r.left + r.width / 2f) * s).let { it..it }
        return (viewWidth - r.right * s)..(-r.left * s)
    }

    private fun limitsY(s: Float): ClosedFloatingPointRange<Float> {
        if (s <= 1f) return 0f..0f
        val r = contentRect()
        if (r.height * s <= viewHeight) return (viewHeight / 2f - (r.top + r.height / 2f) * s).let { it..it }
        return (viewHeight - r.bottom * s)..(-r.top * s)
    }

    /** Past the edge the page moves less and less, up to a quarter of the screen: a rubber band. */
    private fun stretch(raw: Float, limits: ClosedFloatingPointRange<Float>, size: Float): Float {
        if (raw in limits) return raw
        val edge = if (raw < limits.start) limits.start else limits.endInclusive
        val over = abs(raw - edge)
        val reach = size * OVERSCROLL
        val shown = reach * over * RUBBER / (over * RUBBER + reach)
        return edge + if (raw < edge) -shown else shown
    }

    private fun unstretch(shown: Float, limits: ClosedFloatingPointRange<Float>, size: Float): Float {
        if (shown in limits) return shown
        val edge = if (shown < limits.start) limits.start else limits.endInclusive
        val reach = size * OVERSCROLL
        val r = min(abs(shown - edge), reach * 0.99f)
        val over = reach * r / ((reach - r) * RUBBER)
        return edge + if (shown < edge) -over else over
    }

    /** Zooming out past the fit or in past the maximum gives way the same way. */
    private fun stretchScale(raw: Float): Float = when {
        raw < 1f -> 1f - 0.25f * (1f - raw) / ((1f - raw) + 0.25f)
        raw > MAX_SCALE -> MAX_SCALE + MAX_SCALE * 0.3f * (raw - MAX_SCALE) / ((raw - MAX_SCALE) + MAX_SCALE * 0.3f)
        else -> raw
    }

    private fun unstretchScale(shown: Float): Float = when {
        shown < 1f -> (1f - shown).coerceAtMost(0.249f).let { d -> 1f - 0.25f * d / (0.25f - d) }
        shown > MAX_SCALE -> (shown - MAX_SCALE).coerceAtMost(MAX_SCALE * 0.299f).let { d -> MAX_SCALE + MAX_SCALE * 0.3f * d / (MAX_SCALE * 0.3f - d) }
        else -> shown
    }

    fun doubleTap(x: Float, y: Float) {
        if (arrival != null) return
        if (isZoomed) {
            animateZoomTo(1f, 0f, 0f)
        } else {
            finishTurnNow()
            // In a spread, zoom so that one page fills the width; a single page gets a fixed step.
            val target = if (isSpread) (viewWidth / rightSlot().width).coerceIn(1.8f, MAX_SCALE) else DOUBLE_TAP_SCALE
            animateZoomTo(target, x - x * target, y - y * target)
        }
    }

    fun resetZoom() {
        stopMotion()
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        clampOffsets()
    }

    private fun animateZoomTo(toScale: Float, toX: Float, toY: Float) {
        val (cx, cy) = clamp(toScale, toX, toY)
        zoomAnimation = ZoomAnimation(scale, offsetX, offsetY, toScale, cx, cy)
        flingVx = 0f
        flingVy = 0f
        bounceX = false
        bounceY = false
    }

    private fun clampOffsets() {
        val (x, y) = clamp(scale, offsetX, offsetY)
        if (x != offsetX) flingVx = 0f
        if (y != offsetY) flingVy = 0f
        offsetX = x
        offsetY = y
    }

    private fun clamp(s: Float, ox: Float, oy: Float): Pair<Float, Float> {
        if (s <= 1f) return 0f to 0f
        val r = contentRect()
        val x = if (r.width * s <= viewWidth) {
            viewWidth / 2f - (r.left + r.width / 2f) * s
        } else {
            ox.coerceIn(viewWidth - r.right * s, -r.left * s)
        }
        val y = if (r.height * s <= viewHeight) {
            viewHeight / 2f - (r.top + r.height / 2f) * s
        } else {
            oy.coerceIn(viewHeight - r.bottom * s, -r.top * s)
        }
        return x to y
    }

    // endregion

    /** Advances animations. Returns true while another frame is needed. */
    fun step(dt: Float): Boolean {
        var animating = false
        if (!shown && (arrival?.time ?: 0f) >= 0f) {
            shown = true
            listener?.onShown()
        }

        riffle?.let { r ->
            r.time += dt
            for (b in r.bunches) {
                if (b.landed) continue
                if (!b.flying) {
                    if (r.time < b.start) continue
                    b.flying = true
                    b.spring.snapTo(b.grabX(), b.grabY())
                    val rect = b.rect()
                    b.guide = if (isSpread) {
                        // Over the spine in an arc, landing flat on the other side.
                        Guide(
                            b.grabX(), b.grabY(),
                            rect.left, b.grabY() - rect.height * 0.55f,
                            CurlMath.landedX(b.grabX(), rect), b.grabY(),
                            SPREAD_GUIDE_DURATION,
                        )
                    } else {
                        Guide(
                            b.grabX(), b.grabY(),
                            b.grabX() - rect.width * 0.55f, b.grabY() - rect.height * 0.4f,
                            CurlMath.hiddenX(b.grabX(), rect, 0f), b.grabY() - rect.height * 0.15f,
                            GUIDE_DURATION,
                        )
                    }
                }
                val guide = b.guide
                if (guide != null) {
                    guide.t = min(1f, guide.t + dt / guide.duration)
                    val (gx, gy) = guide.point()
                    b.spring.step(gx, gy, GUIDE_OMEGA, dt)
                    if (guide.t >= 1f) b.guide = null
                } else {
                    val rect = b.rect()
                    val tx = if (isSpread) CurlMath.landedX(b.grabX(), rect) else CurlMath.hiddenX(b.grabX(), rect, 0f)
                    b.spring.step(tx, b.grabY(), SETTLE_OMEGA, dt)
                    val there = if (isSpread) {
                        b.spring.isAt(tx, b.grabY(), 0.75f, 20f)
                    } else {
                        b.spring.x <= tx + rect.width * 0.12f
                    }
                    if (there) {
                        b.landed = true
                        listener?.onPageTurned()
                    }
                }
            }
            if (r.done) {
                riffle = null
                arrival = null
                position = r.target.coerceIn(0, max(positionCount - 1, 0))
                lastForward = true
                setClosed(false)
                notifyPosition()
            }
            animating = true
        }

        arrival?.let { closed ->
            if (closed.time < 0f) {
                // Falls once its cover can be seen (or after a moment, whatever happens).
                closed.waited += dt
                if (coverReady || closed.waited > COVER_WAIT) closed.time = 0f
                animating = true
            } else if (!closed.landed) {
                val before = closed.time
                closed.time += dt
                if (before < DROP_DURATION && closed.time >= DROP_DURATION) listener?.onLanded()
                if (closed.time >= DROP_DURATION + SETTLE_DURATION) closed.landed = true
                animating = true
            }
        }

        turn?.let { t ->
            if (t.dragging) {
                t.spring.step(t.targetX, t.targetY, DRAG_OMEGA, dt)
                animating = !t.spring.isAt(t.targetX, t.targetY, 0.3f, 5f)
            } else {
                val guide = t.guide
                if (guide != null) {
                    guide.t = min(1f, guide.t + dt / guide.duration)
                    val (gx, gy) = guide.point()
                    t.spring.step(gx, gy, GUIDE_OMEGA, dt)
                    if (guide.t >= 1f) t.guide = null
                } else {
                    t.spring.step(t.targetX, t.targetY, SETTLE_OMEGA, dt)
                }
                val done = when {
                    t.guide != null -> false
                    // Once the sheet is completely off screen nobody can see the rest of the motion.
                    t.endIsHidden() -> t.spring.x <= t.targetX + t.rect().width * 0.12f
                    else -> t.spring.isAt(t.targetX, t.targetY, 0.75f, 20f)
                }
                if (done) {
                    turn = null
                    if (t.completing) applyTurn(t)
                }
                animating = true
            }
        }

        zoomAnimation?.let { z ->
            z.t = min(1f, z.t + dt / ZOOM_DURATION)
            val k = 1f - (1f - z.t) * (1f - z.t) * (1f - z.t)
            scale = z.fromScale + (z.toScale - z.fromScale) * k
            offsetX = z.fromX + (z.toX - z.fromX) * k
            offsetY = z.fromY + (z.toY - z.fromY) * k
            if (z.t >= 1f) {
                zoomAnimation = null
                if (z.toScale <= 1f) resetZoom()
            }
            animating = true
        }

        if (zoomAnimation == null && (bounceX || bounceY || abs(flingVx) > 1f || abs(flingVy) > 1f)) {
            val (x, vx, bx) = glide(offsetX, flingVx, bounceX, limitsX(scale), dt)
            val (y, vy, by) = glide(offsetY, flingVy, bounceY, limitsY(scale), dt)
            offsetX = x
            offsetY = y
            flingVx = vx
            flingVy = vy
            bounceX = bx
            bounceY = by
            animating = bounceX || bounceY || abs(flingVx) > 1f || abs(flingVy) > 1f
        }

        return animating
    }

    private data class Glide(val position: Float, val velocity: Float, val bouncing: Boolean)

    /**
     * One axis of a thrown page: it slows down by friction; once past an edge a critically damped
     * spring takes it back, so a fast throw bounces softly off the edge instead of stopping dead.
     */
    private fun glide(position: Float, velocity: Float, bouncing: Boolean, limits: ClosedFloatingPointRange<Float>, dt: Float): Glide {
        var p = position
        var v = velocity
        var spring = bouncing
        if (!spring) {
            p += v * dt
            v *= exp(-FLING_FRICTION * dt)
            if (p !in limits) {
                // Hitting the edge: however hard the throw, the page only bounces a little.
                spring = true
                v = v.coerceIn(-BOUNCE_MAX_SPEED * density, BOUNCE_MAX_SPEED * density)
            }
            if (!spring && abs(v) < 20f) v = 0f
        }
        if (spring) {
            val target = p.coerceIn(limits.start, limits.endInclusive)
            val e = exp(-BOUNCE_OMEGA * dt)
            val d = p - target
            val c = v + BOUNCE_OMEGA * d
            p = target + (d + c * dt) * e
            v = (v - BOUNCE_OMEGA * c * dt) * e
            if (abs(p - target) < 0.5f && abs(v) < 10f) {
                p = target
                v = 0f
                spring = false
            }
        }
        return Glide(p, v, spring)
    }

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val DEFAULT_ASPECT = 0.65f

        /** Pages on either side of the current one that get a small preview texture. */
        private const val PREVIEW_RADIUS = 10

        /** Images wider than this (width / height) are two-page spreads. */
        private const val WIDE_ASPECT = 1.1f
        private const val DRAG_OMEGA = 30f
        private const val SETTLE_OMEGA = 13f
        private const val GUIDE_OMEGA = 24f
        private const val GUIDE_DURATION = 0.42f
        private const val SPREAD_GUIDE_DURATION = 0.55f
        private const val ZOOM_DURATION = 0.28f
        private const val FLING_FRICTION = 4.5f
        private const val BOUNCE_OMEGA = 14f

        // The closed comic falling onto the table: time to fall, to settle, its turn and tilt in the hand.
        private const val DROP_DURATION = 0.62f
        private const val SETTLE_DURATION = 0.4f
        private const val DROP_TILT = 9f
        private const val COVER_WAIT = 0.8f
        private const val AMBIENCE_DURATION = 0.7f
        private const val CLOSED_SCALE_PORTRAIT = 0.8f
        private const val CLOSED_SCALE_LANDSCAPE = 0.88f

        /** Size of the comic in the hand compared to reading size: the start of its fall. */
        const val IN_HAND_SCALE = 0.95f

        /**
         * Where a closed comic with a cover of [aspect] (width / height) is held up in a view of
         * that size, just before it falls onto the table: in the middle, a little smaller than
         * the page it opens to. [spread] tells whether the view shows two pages side by side.
         */
        fun handRect(viewWidth: Float, viewHeight: Float, density: Float, aspect: Float, spread: Boolean): PageRect {
            val margin = (if (spread) 6f else 4f) * density
            val availableWidth = (viewWidth - 2f * margin) / (if (spread) 2f else 1f)
            val availableHeight = viewHeight - 2f * margin
            val shape = if (aspect > 0f) aspect else DEFAULT_ASPECT
            val height = min(availableHeight, availableWidth / shape) * IN_HAND_SCALE
            val width = height * shape
            return PageRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }
        private const val REST_ANGLE = -2.6f
        private const val REST_SHIFT_X = 0.025f
        private const val REST_SHIFT_Y = 0.012f

        // Opening at a bookmark: at most this many bunches of pages fly over, within this time.
        private const val MAX_BUNCHES = 7
        private const val RIFFLE_SPREAD_TIME = 0.22f
        private const val RIFFLE_STEP = 0.11f

        /** Speed (dp per second) a thrown page keeps when it hits an edge. */
        private const val BOUNCE_MAX_SPEED = 1400f

        /** How far past an edge the page can be stretched (part of the screen), and how stiffly. */
        private const val OVERSCROLL = 0.22f
        private const val RUBBER = 0.55f
    }
}
