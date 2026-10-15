package com.readcomic.curl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Comic page view with a realistic page curl.
 *
 * - drag a page to the left / right to turn it (it follows the finger, release to finish or cancel);
 * - tap the left / right third of the screen to turn with an animation, tap the middle for [Callbacks.onCenterTap];
 * - pinch or double tap to zoom, drag to pan while zoomed.
 */
@SuppressLint("ViewConstructor")
class PageCurlView(context: Context) : GLSurfaceView(context) {

    interface Callbacks {
        /** Visible page images changed: [first]..[last], equal when a single page is shown. */
        fun onPageChanged(first: Int, last: Int)
        fun onCenterTap()

        /** The reader went on reading (turning, zooming, panning): menus should get out of the way. */
        fun onReadingGesture() = Unit

        /** The comic lies closed on the table, waiting for a touch ([closed]), or was opened. */
        fun onClosedChanged(closed: Boolean) = Unit
    }

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private val controller = PageTurnController(density)
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textures = PageTextureCache(
        scope = scope,
        requestRender = { requestFrame() },
        onLoaded = { index, aspect -> controller.setPageAspect(index, aspect) },
    )
    private val renderer: CurlRenderer

    private val pacer = FramePacer(render = { requestRender() }, wantsFrames = { renderer.wantsFrames })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(GlConfigChooser())
        preserveEGLContextOnPause = true
        renderer = CurlRenderer(context.assets, controller, textures) { requestFrame() }
        renderer.frameClock = { pacer.frameNanos }
        renderer.beforeFrame = ::followFingers
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isHapticFeedbackEnabled = true
        controller.listener = object : PageTurnController.Listener {
            override fun onPageChanged(first: Int, last: Int) {
                mainHandler.post { callbacks?.onPageChanged(first, last) }
            }

            override fun onPageTurned() {
                mainHandler.post { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }

            override fun onLanded() {
                mainHandler.post { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
            }

            override fun onClosedChanged(closed: Boolean) {
                mainHandler.post { callbacks?.onClosedChanged(closed) }
            }
        }
    }

    /** A frame at the next display refresh (frames keep coming while anything moves). Any thread. */
    private fun requestFrame() = pacer.requestFrame()

    // region Public API (UI thread)

    /**
     * @param aspects width / height of every page image (0 where unknown); wide images are
     * shown as two facing pages in the book layout.
     */
    fun setComic(loader: PageLoader, aspects: FloatArray, startPage: Int, closed: Boolean = false) = onGl {
        textures.loader = loader
        controller.setPages(aspects, startPage, closed)
    }

    fun setReadingMode(mode: PageTurnController.ReadingMode) = onGl { controller.setReadingMode(mode) }

    fun turnNext() = onGl { controller.turnForward() }

    fun turnPrevious() = onGl { controller.turnBackward() }

    fun jumpTo(index: Int) = onGl { controller.jumpTo(index) }

    /** The table under the comic (see table.frag) and how dark its corners get. */
    fun setTable(kind: Int, vignette: Float) {
        renderer.table = kind
        renderer.vignette = vignette
        requestFrame()
    }

    /**
     * Reading at night: [strength] 0 (day) to 1, [warmth] and [brightness] 0..1. Called for every
     * step of the change, so it fades in and out.
     */
    fun setNight(strength: Float, warmth: Float, brightness: Float) {
        renderer.night = floatArrayOf(strength, warmth, brightness)
        requestFrame()
    }

    // endregion

    private inline fun onGl(crossinline block: () -> Unit) {
        queueEvent { block() }
        requestFrame()
    }

    private val leftEdge = Rect()
    private val rightEdge = Rect()
    private val edges = listOf(leftEdge, rightEdge)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Pages are pulled from the screen edges: keep the system back gesture away from the
            // middle of both edges (Android allows at most 200dp per edge).
            val height = min((200 * density).toInt(), this.height)
            val y = (this.height - height) / 2
            val width = (40 * density).toInt()
            leftEdge.set(0, y, width, y + height)
            rightEdge.set(this.width - width, y, this.width, y + height)
            systemGestureExclusionRects = edges
        }
    }

    override fun onPause() {
        super.onPause()
        pacer.pause()
    }

    override fun onResume() {
        super.onResume()
        pacer.resume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pacer.stop()
        scope.cancel()
    }

    // region Touch handling

    private enum class Mode { NONE, TURN, PAN, SCALE, IGNORE }

    /** What the fingers drive, as seen by the render thread (only touched there). */
    private enum class Follow { NOTHING, TURN, PAN, PINCH }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var mode = Mode.NONE
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var tapTurnedPage = false

    /** Positions of the finger (or the pinch) that drives the page; read by the render thread. */
    private val track = TouchTrack()
    private var follow = Follow.NOTHING
    private val sample = FloatArray(3)

    /** A touch that stops a scrolling page is not a tap: it must not turn the page by accident. */
    private var touchStoppedFling = false
    private var flingStartedAt = 0L
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    /** Render thread, start of a frame: puts the page where the fingers were at that moment. */
    private fun followFingers(frameNanos: Long) {
        if (follow == Follow.NOTHING || !track.sample(frameNanos - RESAMPLE_DELAY_NANOS, sample)) return
        when (follow) {
            Follow.PAN -> controller.panTo(sample[0], sample[1])
            Follow.PINCH -> controller.pinchTo(sample[0], sample[1], sample[2])
            Follow.TURN -> controller.drag(sample[0], sample[1])
            Follow.NOTHING -> Unit
        }
    }

    private enum class Zone { LEFT, CENTER, RIGHT }

    private fun zoneOf(x: Float): Zone = when {
        x < width * 0.3f -> Zone.LEFT
        x > width * 0.7f -> Zone.RIGHT
        else -> Zone.CENTER
    }

    private fun readingGesture() {
        callbacks?.onReadingGesture()
    }

    private fun turnFromZone(zone: Zone) {
        if (zone != Zone.CENTER) readingGesture()
        when (zone) {
            Zone.LEFT -> turnPrevious()
            Zone.RIGHT -> turnNext()
            Zone.CENTER -> Unit
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // A closed comic is opened, not zoomed.
                if (controller.isClosed) return false
                readingGesture()
                val wasTurning = mode == Mode.TURN
                mode = Mode.SCALE
                val fx = detector.focusX
                val fy = detector.focusY
                val span = detector.currentSpan
                track.clear()
                track.add(detector.eventTime * NANOS_PER_MILLI, fx, fy, span)
                onGl {
                    if (wasTurning) controller.cancelDrag()
                    controller.beginPinch(fx, fy, span)
                    follow = Follow.PINCH
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                track.add(detector.eventTime * NANOS_PER_MILLI, detector.focusX, detector.focusY, detector.currentSpan)
                requestFrame()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onGl {
                    follow = Follow.NOTHING
                    controller.endPinch()
                }
            }
        },
    ).apply {
        // Double tap is already "zoom in / out"; double-tap-and-drag zoom would fight with it.
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapTurnedPage = false
                if (controller.isClosed) {
                    // Anywhere on the table: the comic lying there opens.
                    tapTurnedPage = true
                    onGl { controller.open() }
                    return true
                }
                if (touchStoppedFling) {
                    tapTurnedPage = true
                    return true
                }
                // The sides turn pages even while zoomed in: no need to zoom out first.
                val zone = zoneOf(e.x)
                if (zone == Zone.CENTER) return false
                tapTurnedPage = true
                turnFromZone(zone)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!tapTurnedPage) {
                    performClick()
                    callbacks?.onCenterTap()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controller.isClosed) return true
                val zone = zoneOf(e.x)
                if (zone != Zone.CENTER) {
                    // Quick taps on the side keep turning pages; zooming is a double tap in the middle.
                    if (!touchStoppedFling) turnFromZone(zone)
                } else {
                    readingGesture()
                    val x = e.x
                    val y = e.y
                    onGl { controller.doubleTap(x, y) }
                }
                return true
            }
        },
    )

    override fun performClick(): Boolean = super.performClick()

    /** Every position the finger [id] passed through since the last event, with its time. */
    private fun record(event: MotionEvent, id: Int) {
        val index = event.findPointerIndex(id)
        if (index < 0) return
        for (h in 0 until event.historySize) {
            track.add(historicalNanos(event, h), event.getHistoricalX(index, h), event.getHistoricalY(index, h))
        }
        track.add(eventNanos(event), event.getX(index), event.getY(index))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                mode = Mode.NONE
                pacer.touching = true
                renderer.touching = true
                track.clear()
                record(event, pointerId)
                touchStoppedFling = SystemClock.uptimeMillis() - flingStartedAt < FLING_STOP_WINDOW_MS
                flingStartedAt = 0L
                // A finger on the glass stops a thrown page (one stretched past an edge still settles).
                onGl { controller.catchFling() }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode != Mode.SCALE) {
                    val wasTurning = mode == Mode.TURN
                    mode = Mode.SCALE
                    onGl {
                        if (wasTurning) controller.cancelDrag()
                        if (follow != Follow.PINCH) follow = Follow.NOTHING
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 || event.getPointerId(event.actionIndex) == pointerId) {
                    // Continue with a finger that stays on the screen.
                    val remaining = (0 until event.pointerCount).first { it != event.actionIndex }
                    pointerId = event.getPointerId(remaining)
                    if (event.pointerCount == 2) {
                        // Down to one finger after a pinch: it pans the zoomed page from here.
                        val x = event.getX(remaining)
                        val y = event.getY(remaining)
                        track.clear()
                        track.add(eventNanos(event), x, y)
                        mode = if (controller.isZoomed) Mode.PAN else Mode.IGNORE
                        if (mode == Mode.PAN) {
                            onGl {
                                controller.beginPan(x, y)
                                follow = Follow.PAN
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0 && mode != Mode.SCALE) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    record(event, pointerId)
                    if (mode == Mode.NONE && hypot(x - downX, y - downY) > touchSlop) {
                        mode = when {
                            controller.isZoomed -> Mode.PAN
                            abs(x - downX) > abs(y - downY) * 0.7f -> Mode.TURN
                            else -> Mode.IGNORE
                        }
                        if (mode == Mode.TURN || mode == Mode.PAN) readingGesture()
                        // The gesture starts here: no jump by the slop distance.
                        track.clear()
                        track.add(eventNanos(event), x, y)
                        val sx = downX
                        val sy = downY
                        when (mode) {
                            Mode.TURN -> onGl {
                                if (controller.beginDrag(sx, sy, x, y)) follow = Follow.TURN
                            }
                            Mode.PAN -> onGl {
                                controller.beginPan(x, y)
                                follow = Follow.PAN
                            }
                            else -> Unit
                        }
                    }
                    if (mode == Mode.TURN || mode == Mode.PAN) requestFrame()
                }
            }

            MotionEvent.ACTION_UP -> {
                tracker.computeCurrentVelocity(1000, maxFlingVelocity)
                val vx = tracker.getXVelocity(pointerId)
                val vy = tracker.getYVelocity(pointerId)
                when (mode) {
                    Mode.TURN -> onGl {
                        follow = Follow.NOTHING
                        controller.endDrag(vx)
                    }
                    Mode.PAN -> {
                        if (hypot(vx, vy) > minFlingVelocity * 4) flingStartedAt = SystemClock.uptimeMillis()
                        onGl {
                            follow = Follow.NOTHING
                            controller.endPan(vx, vy)
                        }
                    }
                    else -> onGl { follow = Follow.NOTHING }
                }
                finishTouch(tracker)
            }

            MotionEvent.ACTION_CANCEL -> {
                val turning = mode == Mode.TURN
                val panning = mode == Mode.PAN
                onGl {
                    follow = Follow.NOTHING
                    if (turning) controller.cancelDrag()
                    if (panning) controller.endPan(0f, 0f)
                }
                finishTouch(tracker)
            }
        }
        return true
    }

    private fun finishTouch(tracker: VelocityTracker) {
        mode = Mode.NONE
        pointerId = MotionEvent.INVALID_POINTER_ID
        pacer.touching = false
        renderer.touching = false
        tracker.recycle()
        velocityTracker = null
    }

    private fun eventNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= 34) event.eventTimeNanos else event.eventTime * NANOS_PER_MILLI

    private fun historicalNanos(event: MotionEvent, h: Int): Long =
        if (Build.VERSION.SDK_INT >= 34) event.getHistoricalEventTimeNanos(h) else event.getHistoricalEventTime(h) * NANOS_PER_MILLI

    // endregion

    private companion object {
        /** A fling is considered still running this long after it started. */
        const val FLING_STOP_WINDOW_MS = 700L
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Fingers are read this long before the refresh a frame is drawn for: the input system
         * delivers positions measured up to about 5 ms before it, so there is always a real
         * sample on both sides to interpolate between.
         */
        const val RESAMPLE_DELAY_NANOS = 6_000_000L
    }
}
