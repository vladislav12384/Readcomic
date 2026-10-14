package com.readcomic.curl

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draws a GL view once per display refresh, started by the Choreographer, and tells every
 * frame which refresh it is for. Animations then advance by exactly the time between
 * refreshes, and fingers are read at that moment (see [TouchTrack]): no uneven steps.
 *
 * @param render asks the GL view for one frame (GLSurfaceView.requestRender)
 * @param wantsFrames whether the last frame drawn wants another one (something still moves)
 */
class FramePacer(private val render: () -> Unit, private val wantsFrames: () -> Boolean) {
    private val choreographer = Choreographer.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pacing = AtomicBoolean(false)

    /** The refresh the coming frame is for (System.nanoTime clock), 0 before the first one. */
    @Volatile
    var frameNanos = 0L
        private set

    /** While a finger is down frames keep coming, even if nothing moves by itself. */
    @Volatile
    var touching = false

    @Volatile
    private var paused = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameNanos = frameTimeNanos
            render()
            pacing.set(false)
            if (wantsFrames() || touching) requestFrame()
        }
    }

    private val start = Runnable { choreographer.postFrameCallback(callback) }

    /** A frame at the next refresh, and more while anything moves. Any thread. */
    fun requestFrame() {
        if (paused || !pacing.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) start.run() else mainHandler.post(start)
    }

    /** No frames while the activity is paused: the surface would not show them anyway. */
    fun pause() {
        paused = true
        stop()
    }

    fun resume() {
        paused = false
        requestFrame()
    }

    fun stop() {
        choreographer.removeFrameCallback(callback)
        mainHandler.removeCallbacks(start)
        pacing.set(false)
    }
}
