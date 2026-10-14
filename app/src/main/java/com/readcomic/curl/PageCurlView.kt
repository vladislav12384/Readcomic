package com.readcomic.curl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs
import kotlin.math.hypot

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
        fun onPageChanged(index: Int)
        fun onCenterTap()
    }

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private val controller = PageTurnController(density)
    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textures = PageTextureCache(
        scope = scope,
        runOnGlThread = { runnable -> onGl { runnable.run() } },
        onLoaded = { index, aspect -> controller.setPageAspect(index, aspect) },
    )
    private val renderer: CurlRenderer

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(ConfigChooser())
        preserveEGLContextOnPause = true
        renderer = CurlRenderer(context.assets, controller, textures, density, ::requestRender)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isHapticFeedbackEnabled = true
        controller.listener = object : PageTurnController.Listener {
            override fun onPageChanged(index: Int) {
                mainHandler.post { callbacks?.onPageChanged(index) }
            }

            override fun onPageTurned() {
                mainHandler.post { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
        }
    }

    // region Public API (UI thread)

    fun setComic(loader: PageLoader, pageCount: Int, startPage: Int) = onGl {
        textures.loader = loader
        controller.setPages(pageCount, startPage)
    }

    fun turnNext() = onGl { controller.turnForward() }

    fun turnPrevious() = onGl { controller.turnBackward() }

    fun jumpTo(index: Int) = onGl { controller.jumpTo(index) }

    /** Table colour behind the pages, components in 0..1. */
    fun setBackgroundTint(red: Float, green: Float, blue: Float) {
        renderer.backgroundColor = floatArrayOf(red, green, blue)
        requestRender()
    }

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

    private enum class Mode { NONE, TURN, PAN, SCALE, IGNORE }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var tapTurnedPage = false

    private enum class Zone { LEFT, CENTER, RIGHT }

    private fun zoneOf(x: Float): Zone = when {
        x < width * 0.3f -> Zone.LEFT
        x > width * 0.7f -> Zone.RIGHT
        else -> Zone.CENTER
    }

    private fun turnFromZone(zone: Zone) {
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
                if (mode == Mode.TURN) onGl { controller.cancelDrag() }
                mode = Mode.SCALE
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val fx = detector.focusX
                val fy = detector.focusY
                val dx = fx - lastFocusX
                val dy = fy - lastFocusY
                lastFocusX = fx
                lastFocusY = fy
                onGl {
                    controller.scaleBy(factor, fx, fy)
                    controller.panBy(dx, dy)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onGl { controller.endScale() }
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
                if (controller.isZoomed) return false
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
                val zone = zoneOf(e.x)
                if (!controller.isZoomed && zone != Zone.CENTER) {
                    // Quick taps on the side keep turning pages.
                    turnFromZone(zone)
                } else {
                    val x = e.x
                    val y = e.y
                    onGl { controller.doubleTap(x, y) }
                }
                return true
            }
        },
    )

    override fun performClick(): Boolean = super.performClick()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                mode = Mode.NONE
                onGl { controller.flingPan(0f, 0f) }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.TURN) onGl { controller.cancelDrag() }
                mode = Mode.SCALE
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Continue panning with the finger that stays on the screen.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                mode = if (controller.isZoomed) Mode.PAN else Mode.IGNORE
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.getX(0)
                val y = event.getY(0)
                when (mode) {
                    Mode.NONE -> if (hypot(x - downX, y - downY) > touchSlop) {
                        mode = when {
                            controller.isZoomed -> Mode.PAN
                            abs(x - downX) > abs(y - downY) * 0.7f -> Mode.TURN
                            else -> Mode.IGNORE
                        }
                        if (mode == Mode.TURN) {
                            val sx = downX
                            val sy = downY
                            onGl { controller.beginDrag(sx, sy, x, y) }
                        }
                        lastX = x
                        lastY = y
                    }

                    Mode.TURN -> onGl { controller.drag(x, y) }

                    Mode.PAN -> {
                        val dx = x - lastX
                        val dy = y - lastY
                        onGl { controller.panBy(dx, dy) }
                    }

                    Mode.SCALE, Mode.IGNORE -> Unit
                }
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_UP -> {
                tracker.computeCurrentVelocity(1000)
                val vx = tracker.xVelocity
                val vy = tracker.yVelocity
                when (mode) {
                    Mode.TURN -> onGl { controller.endDrag(vx) }
                    Mode.PAN -> onGl { controller.flingPan(vx, vy) }
                    else -> Unit
                }
                mode = Mode.NONE
                tracker.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.TURN) onGl { controller.cancelDrag() }
                mode = Mode.NONE
                tracker.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    // endregion

    /** Prefers ES 3 with 4x MSAA for smooth page edges, falls back gracefully. */
    private class ConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val attempts = listOf(
                attributes(EGL_OPENGL_ES3_BIT, 4),
                attributes(EGL_OPENGL_ES3_BIT, 0),
                attributes(EGL_OPENGL_ES2_BIT, 4),
                attributes(EGL_OPENGL_ES2_BIT, 0),
            )
            for (attributes in attempts) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("No suitable EGL config")
        }

        private fun attributes(renderable: Int, samples: Int): IntArray {
            val list = mutableListOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, renderable,
            )
            if (samples > 0) {
                list += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            }
            list += EGL10.EGL_NONE
            return list.toIntArray()
        }

        private companion object {
            const val EGL_OPENGL_ES2_BIT = 0x0004
            const val EGL_OPENGL_ES3_BIT = 0x0040
        }
    }
}
