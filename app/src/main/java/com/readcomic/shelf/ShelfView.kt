package com.readcomic.shelf

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.readcomic.curl.FramePacer
import com.readcomic.curl.GlConfigChooser
import com.readcomic.curl.PageTurnController
import com.readcomic.curl.TouchTrack
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.hypot

/**
 * The wall of shelves with the collection.
 *
 * - drag up / down to scroll the shelves;
 * - tap a comic to take it off the shelf, drag to turn it around in hand;
 * - tap the comic in hand to open it ([present]), tap anywhere else to put it back.
 */
@SuppressLint("ViewConstructor")
class ShelfView(context: Context) : GLSurfaceView(context) {

    interface Callbacks {
        /** The comic in hand changed (null: back on the shelf). */
        fun onHeldChanged(id: String?)

        /** The comic in hand is being brought before the eyes to be read; the room fades out. */
        fun onPresenting(id: String) = Unit

        /** Time for the reader to take over the comic [id]. */
        fun onOpen(id: String)
    }

    /** How the reader will lay pages out: the comic is presented at the size it starts at there. */
    var readingMode = PageTurnController.ReadingMode.AUTO

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private val controller = ShelfController(density)
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artCache = ShelfArtCache(scope) { requestFrame() }
    private val renderer: ShelfRenderer
    private val pacer = FramePacer(render = { requestRender() }, wantsFrames = { renderer.wantsFrames })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(GlConfigChooser())
        preserveEGLContextOnPause = true
        renderer = ShelfRenderer(context.assets, controller, artCache) { requestFrame() }
        renderer.frameClock = { pacer.frameNanos }
        renderer.beforeFrame = ::followFinger
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isHapticFeedbackEnabled = true
        controller.listener = object : ShelfController.Listener {
            override fun onHeldChanged(id: String?) {
                mainHandler.post {
                    if (id != null) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    callbacks?.onHeldChanged(id)
                }
            }

            override fun onPresented(id: String) {
                mainHandler.post { callbacks?.onOpen(id) }
            }
        }
    }

    // region Public API (UI thread)

    /** The comics on the shelves, in order, and where their pictures come from. */
    fun setItems(items: List<ShelfItem>, art: ShelfArt) = onGl {
        artCache.art = art
        controller.setItems(items)
    }

    fun putBack() = onGl { controller.putBack() }

    /**
     * Opens the comic in hand: it comes to the middle of the screen, as large as the reader will
     * first show it, while the room fades into the colour of the reading table
     * ([setFadeColor]); then [Callbacks.onOpen]. Nothing happens if no comic is in hand.
     */
    fun present() {
        val spread = when (readingMode) {
            PageTurnController.ReadingMode.SPREAD -> true
            PageTurnController.ReadingMode.SINGLE -> false
            PageTurnController.ReadingMode.AUTO -> width > height
        }
        onGl {
            val id = controller.inHandId ?: return@onGl
            if (controller.present(controller.readerFill(id, spread))) {
                mainHandler.post { callbacks?.onPresenting(id) }
            }
        }
    }

    /** Colour of the reading table (sRGB components in 0..1) the room fades into when a comic opens. */
    fun setFadeColor(red: Float, green: Float, blue: Float) {
        renderer.fadeColor = floatArrayOf(red, green, blue)
    }

    /** How far the shelves are scrolled, to come back to the same place. */
    val scroll: Float get() = controller.scroll

    fun restoreScroll(value: Float) = onGl { controller.scroll = value }

    /** Colour of the room behind the wall, components in 0..1. */
    fun setClearColor(red: Float, green: Float, blue: Float) {
        renderer.clearColor = floatArrayOf(red, green, blue)
        requestFrame()
    }

    /** Paint of the wall behind the shelves, linear components in 0..1. */
    fun setWallColor(red: Float, green: Float, blue: Float) {
        renderer.wallColor = floatArrayOf(red, green, blue)
        requestFrame()
    }

    /** The shelf lights come on one after another, as when walking into the room. */
    fun switchLightsOn() = onGl { controller.switchLightsOn() }

    // endregion

    private inline fun onGl(crossinline block: () -> Unit) {
        queueEvent { block() }
        requestFrame()
    }

    private fun requestFrame() = pacer.requestFrame()

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

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var velocity: VelocityTracker? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    /** The finger's path, read by the render thread at the time of each frame. */
    private val track = TouchTrack()
    private val sample = FloatArray(3)

    // Render thread only: whether the finger drives the shelves, and where it was last frame.
    private var following = false
    private var appliedX = 0f
    private var appliedY = 0f

    /** Render thread, start of a frame: moves the shelves (or the comic) by where the finger went since. */
    private fun followFinger(frameNanos: Long) {
        if (!following || !track.sample(frameNanos - RESAMPLE_DELAY_NANOS, sample)) return
        val dx = sample[0] - appliedX
        val dy = sample[1] - appliedY
        appliedX = sample[0]
        appliedY = sample[1]
        if (dx != 0f || dy != 0f) controller.drag(dx, dy)
    }

    private fun record(event: MotionEvent, index: Int) {
        for (h in 0 until event.historySize) {
            track.add(historicalNanos(event, h), event.getHistoricalX(index, h), event.getHistoricalY(index, h))
        }
        track.add(eventNanos(event), event.getX(index), event.getY(index))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                pacer.touching = true
                renderer.touching = true
                // A finger on the glass stops the shelves or the spinning comic.
                onGl { controller.drag(0f, 0f) }
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                velocity?.addMovement(event)
                val x = event.getX(index)
                val y = event.getY(index)
                if (!dragging && hypot(x - downX, y - downY) > touchSlop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Start from where the finger is now, without a jump of the slop distance.
                    track.clear()
                    track.add(eventNanos(event), x, y)
                    onGl {
                        following = true
                        appliedX = x
                        appliedY = y
                    }
                } else if (dragging) {
                    record(event, index)
                    requestFrame()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Follow the remaining finger if the tracked one lifts, from where it is.
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    val other = if (event.actionIndex == 0) 1 else 0
                    pointerId = event.getPointerId(other)
                    val x = event.getX(other)
                    val y = event.getY(other)
                    track.clear()
                    track.add(eventNanos(event), x, y)
                    onGl {
                        appliedX = x
                        appliedY = y
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val tracker = velocity
                if (dragging) {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000, maxFling)
                    val vx = tracker?.getXVelocity(pointerId) ?: 0f
                    val vy = tracker?.getYVelocity(pointerId) ?: 0f
                    onGl {
                        following = false
                        controller.release(vx, vy)
                    }
                } else {
                    onGl {
                        following = false
                        controller.release(0f, 0f)
                    }
                    tap(event.x, event.y)
                }
                finishGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                onGl {
                    following = false
                    controller.release(0f, 0f)
                }
                finishGesture()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) = onGl {
        val inHand = controller.inHandId
        val hit = controller.tap(x, y)
        if (inHand != null && hit == inHand) mainHandler.post { present() }
    }

    private fun finishGesture() {
        velocity?.recycle()
        velocity = null
        dragging = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pacer.touching = false
        renderer.touching = false
    }

    private fun eventNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= 34) event.eventTimeNanos else event.eventTime * NANOS_PER_MILLI

    private fun historicalNanos(event: MotionEvent, h: Int): Long =
        if (Build.VERSION.SDK_INT >= 34) event.getHistoricalEventTimeNanos(h) else event.getHistoricalEventTime(h) * NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val RESAMPLE_DELAY_NANOS = 6_000_000L
    }

    // endregion
}
