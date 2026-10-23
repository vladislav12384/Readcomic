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
import com.readcomic.curl.TouchTrack
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

        /** The wrapping of the comic [id] is off: it goes on the shelf. */
        fun onUnwrapped(id: String) {}

        /** What the box of new comics is doing. */
        fun onUnboxState(state: UnboxState) {}
    }

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private val controller = ShelfController(density)
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artCache = ShelfArtCache(scope) { requestFrame() }
    private val renderer: ShelfRenderer
    private val pacer = FramePacer(render = { requestRender() }, wantsFrames = { renderer.wantsFrames })
    private val sound = UnboxSound()
    private var lastTick = 0L

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
        }
        controller.unboxing.listener = object : Unboxing.Listener {
            override fun onUnwrapped(id: String) {
                mainHandler.post { callbacks?.onUnwrapped(id) }
            }

            override fun onStateChanged(state: UnboxState) {
                mainHandler.post { callbacks?.onUnboxState(state) }
            }

            override fun onCue(cue: UnboxCue, strength: Float) {
                sound.cue(cue, strength)
                mainHandler.post { feel(cue) }
            }
        }
    }

    // region Public API (UI thread)

    /** The comics in the box of new ones, the next to come out first. */
    fun setBox(items: List<ShelfItem>) = onGl { controller.setBox(items) }

    /** Brings the box of new comics up in front of the reader. */
    fun openBox() = onGl { controller.openBox() }

    /** Puts the box back down. */
    fun putAwayBox() = onGl { controller.putAwayBox() }

    /** The rustle of paper while unpacking. */
    fun setSoundEnabled(enabled: Boolean) {
        sound.enabled = enabled
    }

    /** The comics on the shelves, in order, and where their pictures come from. */
    fun setItems(items: List<ShelfItem>, art: ShelfArt) = onGl {
        artCache.art = art
        controller.setItems(items)
    }

    fun putBack() = onGl { controller.putBack() }

    /** Turns the comic in hand round to show its back. */
    fun showBack() = onGl { controller.showBack() }

    /** Pixels the UI takes from each edge of the view while a comic is in hand; the comic keeps clear of them. */
    fun setHandRoom(left: Float, top: Float, right: Float, bottom: Float) = onGl { controller.setHandRoom(left, top, right, bottom) }

    /** How large the comics look on the shelves. */
    fun setShelfSize(size: ShelfSize) = onGl { controller.size = size }

    /** How many comics a shelf holds at least (2 to 4). */
    fun setColumns(columns: Int) = onGl { controller.columnsWanted = columns }

    /** Pixels the header takes at the top over a display; the bookcase starts under it. */
    fun setTopRoom(pixels: Float) = onGl { controller.topRoom = pixels }

    /** Comics matching a search stay lit, the rest go into the shade; null ends the search. */
    fun highlight(ids: Set<String>?) = onGl { controller.highlight(ids) }

    /** Scrolls the shelves to the comic [id] and takes it into the hands. */
    fun focus(id: String) = onGl { controller.focus(id) }

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

    /** The look of the bookcase. */
    fun setStyle(style: ShelfStyle) {
        renderer.style = style.shader
        renderer.mirrors = style.mirrors
        onGl { controller.showcase = style.showcase }
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
        sound.loudness(0f, 0f)
    }

    override fun onResume() {
        super.onResume()
        pacer.resume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pacer.stop()
        scope.cancel()
        sound.release()
        // Off to read (or elsewhere): only the pictures that were on screen stay at hand.
        ShelfArtMemory.keepWarm()
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

    // Whether this gesture belongs to the box of new comics (decided when it begins).
    @Volatile
    private var boxGesture = false
    private var appliedX = 0f
    private var appliedY = 0f

    /** A touch to go with a sound: a tick as paper tears, a knock as the box lands. */
    private fun feel(cue: UnboxCue) {
        val now = android.os.SystemClock.uptimeMillis()
        val kind = when (cue) {
            UnboxCue.TEAR_TICK -> {
                if (now - lastTick < 35) return
                lastTick = now
                HapticFeedbackConstants.CLOCK_TICK
            }
            UnboxCue.POKE -> HapticFeedbackConstants.KEYBOARD_TAP
            UnboxCue.SHINE -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            UnboxCue.BOX_LANDS, UnboxCue.TAPE_DONE -> HapticFeedbackConstants.VIRTUAL_KEY
            UnboxCue.FLAP, UnboxCue.RELEASE, UnboxCue.BOX_SHAKES -> HapticFeedbackConstants.CONTEXT_CLICK
        }
        performHapticFeedback(kind)
    }

    /** Render thread, start of a frame: moves the shelves (or the comic) by where the finger went since. */
    private fun followFinger(frameNanos: Long) {
        if (controller.unboxing.present) sound.loudness(controller.unboxing.tearLoudness, controller.unboxing.tapeLoudness)
        if (!following || !track.sample(frameNanos - RESAMPLE_DELAY_NANOS, sample)) return
        if (boxGesture) {
            // The box takes the finger where it is, not how far it went.
            controller.boxMove(sample[0], sample[1])
            return
        }
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
                if (controller.boxTakesTouches) {
                    // Tearing starts at the touch, without waiting for the finger to travel.
                    boxGesture = true
                    pacer.touching = true
                    renderer.touching = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    track.clear()
                    track.add(eventNanos(event), event.x, event.y)
                    val x = event.x
                    val y = event.y
                    onGl {
                        following = true
                        controller.boxDown(x, y)
                    }
                    return true
                }
                boxGesture = false
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
                if (boxGesture) {
                    if (!dragging && hypot(event.getX(index) - downX, event.getY(index) - downY) > touchSlop) dragging = true
                    record(event, index)
                    requestFrame()
                    return true
                }
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
                if (boxGesture) {
                    val x = event.x
                    val y = event.y
                    val tapped = !dragging
                    record(event, 0.coerceAtLeast(event.findPointerIndex(pointerId)))
                    onGl {
                        // The last stretch of the finger, then let go.
                        if (track.sample(Long.MAX_VALUE, sample)) controller.boxMove(sample[0], sample[1])
                        following = false
                        controller.boxUp()
                        if (tapped) controller.boxTap(x, y)
                    }
                    finishGesture()
                    return true
                }
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
                val box = boxGesture
                onGl {
                    following = false
                    if (box) controller.boxUp() else controller.release(0f, 0f)
                }
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
