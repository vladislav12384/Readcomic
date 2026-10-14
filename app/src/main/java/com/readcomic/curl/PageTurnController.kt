package com.readcomic.curl

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * All the reading state that is not tied to OpenGL: current page, the page being turned
 * (driven by a spring towards the finger or towards its final position) and zoom/pan.
 *
 * Coordinates are view pixels. Not thread-safe: it lives on the render thread.
 */
class PageTurnController(private val density: Float) {

    interface Listener {
        fun onPageChanged(index: Int)
        fun onPageTurned()
    }

    enum class Direction { FORWARD, BACKWARD }

    /** Path the finger "would" take for an animated turn: a quadratic Bézier curve. */
    private class Guide(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
    ) {
        var t = 0f

        fun point(): Pair<Float, Float> {
            // Ease out: the corner reacts to the tap at once and slows down as the sheet flies away.
            val e = 1f - (1f - t) * (1f - t)
            val a = (1f - e) * (1f - e)
            val b = 2f * (1f - e) * e
            val c = e * e
            return (a * x0 + b * x1 + c * x2) to (a * y0 + b * y1 + c * y2)
        }
    }

    private inner class Turn(val direction: Direction, val grabRelY: Float, val turningIndex: Int) {
        val spring = Spring2D()
        var targetX = 0f
        var targetY = 0f
        var dragging = true
        var completing = false
        var guide: Guide? = null

        /** Turning past the last page: the sheet only lifts a little and always falls back. */
        val rubber get() = direction == Direction.FORWARD && turningIndex >= pageCount - 1

        fun rect() = pageRect(turningIndex)
        fun grabX() = rect().right
        fun grabY() = rect().let { it.top + it.height * grabRelY }
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

    var pageCount = 0
        private set
    var currentIndex = 0
        private set

    private val aspects = HashMap<Int, Float>()
    private var defaultAspect = 0.65f

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

    fun setViewSize(width: Int, height: Int) {
        viewWidth = max(width, 1).toFloat()
        viewHeight = max(height, 1).toFloat()
        resetZoom()
    }

    fun setPages(count: Int, startIndex: Int) {
        pageCount = count
        currentIndex = startIndex.coerceIn(0, max(count - 1, 0))
        turn = null
        resetZoom()
    }

    fun setPageAspect(index: Int, aspect: Float) {
        if (aspect <= 0f || aspect.isNaN()) return
        if (aspects.isEmpty()) defaultAspect = aspect
        aspects[index] = aspect
        if (!isZoomed) resetZoom()
    }

    private fun aspectOf(index: Int) = aspects[index] ?: defaultAspect

    /** Rectangle of a page at zoom 1, fitted into the view with a small margin for the stack edge. */
    fun pageRect(index: Int): PageRect {
        val margin = 6f * density
        val availableWidth = viewWidth - 2f * margin
        val availableHeight = viewHeight - 2f * margin
        val aspect = aspectOf(index)
        var width = availableWidth
        var height = width / aspect
        if (height > availableHeight) {
            height = availableHeight
            width = height * aspect
        }
        return PageRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }

    // region What the renderer needs

    val turningIndex: Int? get() = turn?.turningIndex

    /** The flat page that is visible below everything else, or null if there is none (end of the comic). */
    val underIndex: Int?
        get() {
            val t = turn ?: return currentIndex.takeIf { it in 0 until pageCount }
            val index = if (t.direction == Direction.FORWARD) currentIndex + 1 else currentIndex
            return index.takeIf { it in 0 until pageCount }
        }

    fun currentCurl(): CurlGeometry? {
        val t = turn ?: return null
        return CurlMath.compute(t.grabX(), t.grabY(), t.spring.x, t.spring.y, t.rect())
    }

    /** Pages worth keeping in memory, the most important first. */
    fun wantedPages(): List<Int> =
        listOf(currentIndex, currentIndex + 1, currentIndex - 1, currentIndex + 2)
            .filter { it in 0 until pageCount }

    // endregion

    // region Turning

    fun beginDrag(downX: Float, downY: Float, x: Float, y: Float): Boolean {
        if (isZoomed || pageCount == 0) return false
        // A sheet that is falling back can be caught mid-air and pulled again.
        val falling = turn
        if (falling != null && !falling.dragging && !falling.completing && falling.guide == null) {
            falling.dragging = true
            drag(x, y)
            return true
        }
        finishTurnNow()
        val direction = if (x < downX) Direction.FORWARD else Direction.BACKWARD
        val turningIndex = if (direction == Direction.FORWARD) currentIndex else currentIndex - 1
        if (turningIndex < 0) return false

        val rect = pageRect(turningIndex)
        var rel = ((downY - rect.top) / rect.height).coerceIn(0.02f, 0.98f)
        // Grabbing close to a corner grabs the corner itself.
        if (rel > 0.86f) rel = 0.99f
        if (rel < 0.14f) rel = 0.01f

        val t = Turn(direction, rel, turningIndex)
        if (direction == Direction.FORWARD) {
            t.spring.snapTo(t.grabX(), t.grabY())
        } else {
            t.spring.snapTo(CurlMath.hiddenX(t.grabX(), rect, 0f), t.grabY())
        }
        turn = t
        drag(x, y)
        return true
    }

    fun drag(x: Float, y: Float) {
        val t = turn ?: return
        if (!t.dragging) return
        if (t.rubber) {
            val travel = max(t.grabX() - x, 0f)
            val limit = t.rect().width * 0.22f
            t.targetX = t.grabX() - limit * (1f - exp(-travel / limit))
            t.targetY = t.grabY() + (y - t.grabY()) * 0.3f
        } else {
            t.targetX = x
            t.targetY = y
        }
    }

    fun endDrag(velocityX: Float) {
        val t = turn ?: return
        if (!t.dragging) return
        t.dragging = false
        val rect = t.rect()
        val fling = 500f * density
        val x = t.targetX
        t.completing = when {
            t.rubber -> false
            t.direction == Direction.FORWARD ->
                velocityX < -fling || (velocityX < fling && x < rect.left + rect.width * 0.6f)
            else ->
                velocityX > fling || (velocityX > -fling && x > rect.left + rect.width * 0.4f)
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
    fun turnForward() {
        if (pageCount == 0) return
        finishTurnNow()
        if (currentIndex >= pageCount - 1) return
        resetZoom()
        val t = Turn(Direction.FORWARD, 0.99f, currentIndex)
        t.spring.snapTo(t.grabX(), t.grabY())
        t.dragging = false
        t.completing = true
        aimAtEnd(t)
        val r = t.rect()
        t.guide = Guide(
            t.grabX(), t.grabY(),
            t.grabX() - r.width * 0.55f, t.grabY() - r.height * 0.4f,
            t.targetX, t.grabY() - r.height * 0.15f,
        )
        t.targetY = t.grabY() - r.height * 0.15f
        turn = t
    }

    fun turnBackward() {
        if (pageCount == 0) return
        finishTurnNow()
        if (currentIndex <= 0) return
        resetZoom()
        val t = Turn(Direction.BACKWARD, 0.99f, currentIndex - 1)
        val r = t.rect()
        val hiddenX = CurlMath.hiddenX(t.grabX(), r, 0f)
        t.spring.snapTo(hiddenX, t.grabY() - r.height * 0.15f)
        t.dragging = false
        t.completing = true
        aimAtEnd(t)
        t.guide = Guide(
            hiddenX, t.grabY() - r.height * 0.15f,
            t.grabX() - r.width * 0.55f, t.grabY() - r.height * 0.4f,
            t.grabX(), t.grabY(),
        )
        turn = t
    }

    fun jumpTo(index: Int) {
        if (pageCount == 0) return
        finishTurnNow()
        val target = index.coerceIn(0, pageCount - 1)
        if (target == currentIndex) return
        currentIndex = target
        resetZoom()
        listener?.onPageChanged(currentIndex)
    }

    private fun hiddenTargetX(t: Turn) = CurlMath.hiddenX(t.grabX(), t.rect(), 0f)

    private fun aimAtEnd(t: Turn) {
        val hidden = (t.direction == Direction.FORWARD) == t.completing
        if (hidden) {
            t.targetX = hiddenTargetX(t)
            t.targetY = t.spring.y
        } else {
            t.targetX = t.grabX()
            t.targetY = t.grabY()
        }
    }

    private fun finishTurnNow() {
        val t = turn ?: return
        turn = null
        if (!t.dragging && t.completing) applyTurn(t)
    }

    private fun applyTurn(t: Turn) {
        currentIndex = if (t.direction == Direction.FORWARD) currentIndex + 1 else currentIndex - 1
        listener?.onPageChanged(currentIndex)
        listener?.onPageTurned()
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
            val target = DOUBLE_TAP_SCALE
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
        val r = pageRect(currentIndex)
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
                    guide.t = min(1f, guide.t + dt / GUIDE_DURATION)
                    val (gx, gy) = guide.point()
                    t.spring.step(gx, gy, GUIDE_OMEGA, dt)
                    if (guide.t >= 1f) t.guide = null
                } else {
                    t.spring.step(t.targetX, t.targetY, SETTLE_OMEGA, dt)
                }
                val hidden = (t.direction == Direction.FORWARD) == t.completing
                val done = if (t.guide != null) {
                    false
                } else if (hidden) {
                    // Once the sheet is completely off screen nobody can see the rest of the motion.
                    t.spring.x <= t.targetX + t.rect().width * 0.12f
                } else {
                    t.spring.isAt(t.targetX, t.targetY, 0.75f, 20f)
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
        private const val DRAG_OMEGA = 30f
        private const val SETTLE_OMEGA = 13f
        private const val GUIDE_OMEGA = 24f
        private const val GUIDE_DURATION = 0.42f
        private const val ZOOM_DURATION = 0.28f
        private const val FLING_FRICTION = 4.5f
    }
}
