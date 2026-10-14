package com.readcomic.curl

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
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
    }

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

    val isZoomed get() = scale > 1.01f
    val isTurning get() = turn != null

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
    fun setPages(imageAspects: FloatArray, startImage: Int) {
        aspects = imageAspects.copyOf()
        splitWide = viewHeight > viewWidth
        singlePages = buildSinglePages()
        spreadPages = buildSpreadPages()
        slotAspect = typicalAspect()
        turn = null
        isSpread = wantsSpread()
        position = positionOf(startImage)
        resetZoom()
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
        val t = turn
        val curl = currentCurl()
        return if (isSpread) spreadScene(t, curl) else singleScene(t, curl)
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

    /** Turn with an animation, as if the bottom corner was picked up (tap or volume key). */
    fun turnForward() = animatedTurn(forward = true)

    fun turnBackward() = animatedTurn(forward = false)

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
        position = (if (t.forward) position + 1 else position - 1).coerceIn(0, max(positionCount - 1, 0))
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
    }

    fun doubleTap(x: Float, y: Float) {
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
        zoomAnimation = null
        flingVx = 0f
        flingVy = 0f
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

        if (abs(flingVx) > 1f || abs(flingVy) > 1f) {
            offsetX += flingVx * dt
            offsetY += flingVy * dt
            val decay = exp(-FLING_FRICTION * dt)
            flingVx *= decay
            flingVy *= decay
            clampOffsets()
            if (abs(flingVx) < 20f && abs(flingVy) < 20f) {
                flingVx = 0f
                flingVy = 0f
            }
            animating = true
        }

        return animating
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
    }
}
