package com.readcomic.curl

/**
 * Recent positions of a finger (or of the middle of a pinch, with its span) and when they were
 * measured. The UI thread adds every sample a touch event carries; the render thread asks where
 * the finger was at the moment its frame stands for. Touch events and frames tick at different
 * moments, so applying events as they come moves the page by uneven steps (one frame gets two
 * events, the next none): visible as a slight judder. Reading the finger at the frame's own
 * time, between two real samples, gives motion as even as the finger's.
 */
class TouchTrack {
    private val times = LongArray(CAPACITY)
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val spans = FloatArray(CAPACITY)
    private var start = 0
    private var count = 0

    @Synchronized
    fun clear() {
        start = 0
        count = 0
    }

    /** A sample measured at [timeNanos] (System.nanoTime / uptime clock). */
    @Synchronized
    fun add(timeNanos: Long, x: Float, y: Float, span: Float = 0f) {
        if (count > 0) {
            val last = (start + count - 1) % CAPACITY
            if (timeNanos < times[last]) return
            if (timeNanos == times[last]) {
                xs[last] = x
                ys[last] = y
                spans[last] = span
                return
            }
        }
        val slot: Int
        if (count < CAPACITY) {
            slot = (start + count) % CAPACITY
            count++
        } else {
            slot = start
            start = (start + 1) % CAPACITY
        }
        times[slot] = timeNanos
        xs[slot] = x
        ys[slot] = y
        spans[slot] = span
    }

    /**
     * Where the finger was at [timeNanos]: between the two samples around it, or the nearest one
     * outside the recorded span (never guessed ahead, so a finger that stopped is matched exactly).
     * Writes x, y and span to [out]; false if nothing was recorded yet.
     */
    @Synchronized
    fun sample(timeNanos: Long, out: FloatArray): Boolean {
        if (count == 0) return false
        val first = start
        val last = (start + count - 1) % CAPACITY
        if (timeNanos <= times[first]) return copy(first, out)
        if (timeNanos >= times[last]) return copy(last, out)
        var i = count - 2
        while (i >= 0) {
            val a = (start + i) % CAPACITY
            if (times[a] <= timeNanos) {
                val b = (a + 1) % CAPACITY
                val f = (timeNanos - times[a]).toFloat() / (times[b] - times[a]).toFloat()
                out[0] = xs[a] + (xs[b] - xs[a]) * f
                out[1] = ys[a] + (ys[b] - ys[a]) * f
                out[2] = spans[a] + (spans[b] - spans[a]) * f
                return true
            }
            i--
        }
        return copy(first, out)
    }

    private fun copy(slot: Int, out: FloatArray): Boolean {
        out[0] = xs[slot]
        out[1] = ys[slot]
        out[2] = spans[slot]
        return true
    }

    private companion object {
        const val CAPACITY = 64
    }
}
