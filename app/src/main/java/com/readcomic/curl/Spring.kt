package com.readcomic.curl

import kotlin.math.abs
import kotlin.math.exp

/**
 * Critically damped 2D spring, integrated analytically so it is stable for any time step.
 * Used to make the page follow the finger with a little bit of weight and to finish turns.
 */
class Spring2D {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f

    fun snapTo(px: Float, py: Float) {
        x = px
        y = py
        vx = 0f
        vy = 0f
    }

    fun step(targetX: Float, targetY: Float, omega: Float, dt: Float) {
        val e = exp(-omega * dt)

        val dx = x - targetX
        val cx = vx + omega * dx
        x = targetX + (dx + cx * dt) * e
        vx = (vx - omega * cx * dt) * e

        val dy = y - targetY
        val cy = vy + omega * dy
        y = targetY + (dy + cy * dt) * e
        vy = (vy - omega * cy * dt) * e
    }

    fun isAt(targetX: Float, targetY: Float, posEps: Float, velEps: Float): Boolean =
        abs(x - targetX) < posEps && abs(y - targetY) < posEps && abs(vx) < velEps && abs(vy) < velEps
}
