package com.readcomic.shelf

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.readcomic.curl.GlConfigChooser
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.hypot

/**
 * The wall of shelves with the collection.
 *
 * - drag up / down to scroll the shelves;
 * - tap a comic to take it off the shelf, drag to turn it around in hand;
 * - tap the comic in hand to open it ([Callbacks.onOpen]), tap anywhere else to put it back.
 */
@SuppressLint("ViewConstructor")
class ShelfView(context: Context) : GLSurfaceView(context) {

    interface Callbacks {
        /** The comic in hand changed (null: back on the shelf). */
        fun onHeldChanged(id: String?)
        fun onOpen(id: String)
    }

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private val controller = ShelfController(density)
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artCache = ShelfArtCache(scope) { requestRender() }
    private val renderer: ShelfRenderer

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(GlConfigChooser())
        preserveEGLContextOnPause = true
        renderer = ShelfRenderer(context.assets, controller, artCache, ::requestRender)
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
        }
    }

    // region Public API (UI thread)

    /** The comics on the shelves, in order, and where their pictures come from. */
    fun setItems(items: List<ShelfItem>, art: ShelfArt) = onGl {
        artCache.art = art
        controller.setItems(items)
    }

    fun putBack() = onGl { controller.putBack() }

    /** How far the shelves are scrolled, to come back to the same place. */
    val scroll: Float get() = controller.scroll

    fun restoreScroll(value: Float) = onGl { controller.scroll = value }

    /** Colour of the room behind the wall, components in 0..1. */
    fun setClearColor(red: Float, green: Float, blue: Float) {
        renderer.clearColor = floatArrayOf(red, green, blue)
        requestRender()
    }

    /** Paint of the wall behind the shelves, linear components in 0..1. */
    fun setWallColor(red: Float, green: Float, blue: Float) {
        renderer.wallColor = floatArrayOf(red, green, blue)
        requestRender()
    }

    /** The shelf lights come on one after another, as when walking into the room. */
    fun switchLightsOn() = onGl { controller.switchLightsOn() }

    // endregion

    private inline fun onGl(crossinline block: () -> Unit) {
        queueEvent { block() }
        requestRender()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
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
    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
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
                    lastX = x
                    lastY = y
                }
                if (dragging) {
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x
                    lastY = y
                    onGl { controller.drag(dx, dy) }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Follow the remaining finger if the tracked one lifts.
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    val other = if (event.actionIndex == 0) 1 else 0
                    pointerId = event.getPointerId(other)
                    lastX = event.getX(other)
                    lastY = event.getY(other)
                }
            }

            MotionEvent.ACTION_UP -> {
                val tracker = velocity
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000, maxFling)
                    val vx = tracker?.getXVelocity(pointerId) ?: 0f
                    val vy = tracker?.getYVelocity(pointerId) ?: 0f
                    onGl { controller.release(vx, vy) }
                } else {
                    onGl { controller.release(0f, 0f) }
                    tap(event.x, event.y)
                }
                finishGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                onGl { controller.release(0f, 0f) }
                finishGesture()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) = onGl {
        val inHand = controller.inHandId
        val hit = controller.tap(x, y)
        if (inHand != null && hit == inHand) mainHandler.post { callbacks?.onOpen(inHand) }
    }

    private fun finishGesture() {
        velocity?.recycle()
        velocity = null
        dragging = false
        pointerId = MotionEvent.INVALID_POINTER_ID
    }

    // endregion
}
