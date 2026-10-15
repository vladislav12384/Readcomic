package com.readcomic.shelf

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The satin bookmark of a comic being read: it comes out of the top of the pages and curls over
 * towards the cover. It is soft: it droops the way gravity pulls it and swings when the comic is
 * moved or turned in the hands, then settles.
 *
 * Its shape is one bend: [bendX] sideways and [bendZ] towards the cover (radians at the tip,
 * growing along the ribbon), moved like a damped spring towards where gravity and the comic's
 * acceleration put it.
 */
class Ribbon {
    var bendX = REST_X
        private set
    var bendZ = REST_Z
        private set
    private var speedX = 0f
    private var speedZ = 0f

    // The middle of the ribbon a step ago, for the comic's motion.
    private var lastMiddle: Vec3? = null
    private var lastVelocity = Vec3.ZERO
    private var started = false

    /**
     * Follows the comic, standing at [position] turned by [rotation], [size] its width, height
     * and thickness, for [dt] seconds. Returns whether the ribbon still moves.
     */
    fun step(position: Vec3, rotation: Quat, size: Vec3, dt: Float): Boolean {
        if (dt <= 0f) return moving()
        val inverse = Quat(-rotation.x, -rotation.y, -rotation.z, rotation.w)
        // Gravity in the comic's own axes: on the shelf the comic leans back, in the hands it
        // may be held at any angle.
        val down = inverse.rotate(Vec3(0f, -1f, 0f))
        val restX = (REST_X + down.x * GRAVITY_BEND).coerceIn(-MAX_BEND, MAX_BEND)
        val restZ = (REST_Z + down.z * GRAVITY_BEND).coerceIn(-MAX_BEND, MAX_BEND)

        val middle = position + rotation.rotate(anchor(size) + Vec3(0f, LENGTH * 0.5f, 0f))
        val previous = lastMiddle
        var accel = Vec3.ZERO
        if (previous != null && started) {
            val velocity = (middle - previous) * (1f / dt)
            // A jump (the shelves laid out anew) is not a motion the ribbon can feel.
            if ((middle - previous).length() < 0.15f) {
                accel = inverse.rotate((velocity - lastVelocity) * (1f / dt))
                lastVelocity = velocity
            } else {
                lastVelocity = Vec3.ZERO
            }
        } else if (!started) {
            // Comes into being at rest, as it would lie.
            bendX = restX
            bendZ = restZ
            started = true
        }
        lastMiddle = middle
        val ax = accel.x.coerceIn(-MAX_ACCEL, MAX_ACCEL)
        val az = accel.z.coerceIn(-MAX_ACCEL, MAX_ACCEL)

        // A damped spring towards the rest bend, pushed the other way by the acceleration
        // (left behind like anything hanging loose), in small steps.
        val steps = max(1, (dt / MAX_STEP).toInt() + 1)
        val h = dt / steps
        repeat(steps) {
            speedX += (-OMEGA * OMEGA * (bendX - restX) - 2f * DAMPING * OMEGA * speedX - ax / REACH) * h
            speedZ += (-OMEGA * OMEGA * (bendZ - restZ) - 2f * DAMPING * OMEGA * speedZ - az / REACH) * h
            bendX = (bendX + speedX * h).coerceIn(-MAX_BEND, MAX_BEND)
            bendZ = (bendZ + speedZ * h).coerceIn(-MAX_BEND, MAX_BEND)
        }
        this.restX = restX
        this.restZ = restZ
        return moving()
    }

    private var restX = REST_X
    private var restZ = REST_Z

    private fun moving() = !started || abs(bendX - restX) > 0.002f || abs(bendZ - restZ) > 0.002f ||
        abs(speedX) > 0.01f || abs(speedZ) > 0.01f

    /**
     * The ribbon as triangles in the world (see [INDICES]): three vertices across (edge,
     * middle, edge) in [ROWS] + 1 rows from the pages to the swallowtail cut at its end. Each
     * vertex: position, normal, uv (u across, v along), 0.
     */
    fun mesh(position: Vec3, rotation: Quat, size: Vec3): FloatArray {
        val out = FloatArray((ROWS + 1) * 3 * 9)
        val bend = sqrt(bendX * bendX + bendZ * bendZ)
        val dirX = if (bend > 1e-4f) bendX / bend else 0f
        val dirZ = if (bend > 1e-4f) bendZ / bend else 1f
        val side = Vec3(1f, 0f, 0f)
        // Out of the pages the ribbon lies flat between them; bending over, it turns so that it
        // bends the soft way, across its width.
        val across = Vec3(dirZ, 0f, -dirX).let { if (it.dot(side) < 0f) it * -1f else it }
        var p = anchor(size)
        val ds = LENGTH / ROWS
        for (row in 0..ROWS) {
            val s = row * ds
            // Bent more and more towards the tip.
            val theta = bend * (s / LENGTH).pow(PROFILE)
            val tangent = Vec3(sin(theta) * dirX, cos(theta), sin(theta) * dirZ)
            if (row > 0) {
                val half = bend * ((s - ds * 0.5f) / LENGTH).pow(PROFILE)
                p = p + Vec3(sin(half) * dirX, cos(half), sin(half) * dirZ) * ds
            }
            val twist = (s / LENGTH / 0.35f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            val axis = side * (1f - twist) + across * twist
            val width = (axis - tangent * tangent.dot(axis)).let { if (it.length() > 1e-4f) it.normalized() else side }
            val normal = width.cross(tangent).normalized()
            val v = row.toFloat() / ROWS
            for (column in 0..2) {
                var local = p + width * ((column - 1) * WIDTH * 0.5f)
                // The swallowtail: the middle of the end is cut back.
                if (row == ROWS && column == 1) local = local - tangent * (WIDTH * 0.7f)
                val world = position + rotation.rotate(local)
                val n = rotation.rotate(normal)
                val i = (row * 3 + column) * 9
                out[i] = world.x
                out[i + 1] = world.y
                out[i + 2] = world.z
                out[i + 3] = n.x
                out[i + 4] = n.y
                out[i + 5] = n.z
                out[i + 6] = column * 0.5f
                out[i + 7] = if (row == ROWS && column == 1) v - 0.7f * WIDTH / LENGTH else v
                out[i + 8] = 0f
            }
        }
        return out
    }

    /** Where it comes out of the pages: towards the fore-edge, a little below the top. */
    private fun anchor(size: Vec3) = Vec3(size.x * 0.28f, size.y * 0.5f - 0.004f, 0f)

    companion object {
        const val ROWS = 14
        const val LENGTH = 0.08f
        const val WIDTH = 0.011f

        /**
         * How the ribbon lies when nothing pulls it: out of the top, over to the fore-edge side
         * and a little towards the cover, its end hanging down (radians at the tip).
         */
        const val REST_X = 1.5f
        const val REST_Z = 0.65f
        private const val PROFILE = 1.2f
        private const val GRAVITY_BEND = 1.1f
        private const val MAX_BEND = 2.6f

        // A swing a little faster than twice a second, dying out in a few swings.
        private const val OMEGA = 15f
        private const val DAMPING = 0.16f

        /** How far the acceleration bends it: radians per m/s², about OMEGA² × this. */
        private const val REACH = 0.045f
        private const val MAX_ACCEL = 40f
        private const val MAX_STEP = 1f / 240f

        /** Triangles of [mesh]: two strips side by side. */
        val INDICES: ShortArray = ShortArray(ROWS * 12).also { out ->
            var k = 0
            for (row in 0 until ROWS) {
                for (column in 0..1) {
                    val a = (row * 3 + column).toShort()
                    val b = (row * 3 + column + 1).toShort()
                    val c = ((row + 1) * 3 + column).toShort()
                    val d = ((row + 1) * 3 + column + 1).toShort()
                    out[k++] = a; out[k++] = c; out[k++] = b
                    out[k++] = b; out[k++] = c; out[k++] = d
                }
            }
        }
    }
}
