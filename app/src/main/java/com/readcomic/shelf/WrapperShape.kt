package com.readcomic.shelf

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The paper a new comic comes wrapped in: a sheet over its front, folded round its edges onto
 * the back, a little puffed and crinkled. Built in two halves, left and right of the middle, so
 * that the rest of it can fall away in two pieces once enough is torn.
 *
 * Meters around the comic's centre, like [WrapTear]; the front of the paper lies [radius] in
 * front of the centre. Vertex layout as everywhere on the shelf: position, normal, uv, part.
 * uv are the coordinates of the point on the unfolded sheet (meters, as in [WrapTear]); part 0
 * is the front with the fold round the edges, 1 the back.
 */
object WrapperShape {
    /** Air between the paper and the cover. */
    const val GAP = 0.0012f

    private const val STEP = 0.0045f
    private const val FOLD_STEP = 0.0022f

    fun radius(thickness: Float) = thickness / 2f + GAP

    /** How far the sheet over the front reaches past the edges: round them onto the back. */
    fun margin(thickness: Float) = PI.toFloat() * radius(thickness)

    class Halves(val left: FloatArray, val right: FloatArray, val leftIndices: ShortArray, val rightIndices: ShortArray)

    /** Where the paper at (x, y) on the sheet is, over the front of a comic of [size]. */
    fun frontPoint(x: Float, y: Float, size: Vec3, tear: WrapTear?, seed: Int): Vec3 =
        place(x, y, size.x, size.y, radius(size.z), margin(size.z), tear, seed)

    /** The wrapping of a comic [size] (width, height, thickness); torn edges lifted by [tear]. */
    fun build(size: Vec3, tear: WrapTear?, seed: Int): Halves {
        val (lv, li) = half(size, tear, seed, left = true)
        val (rv, ri) = half(size, tear, seed, left = false)
        return Halves(lv, rv, li, ri)
    }

    private fun half(size: Vec3, tear: WrapTear?, seed: Int, left: Boolean): Pair<FloatArray, ShortArray> {
        val w = size.x
        val h = size.y
        val r = radius(size.z)
        val m = margin(size.z)
        val sign = if (left) -1f else 1f
        // Grid lines along x: from the middle out to the edge, then round the fold.
        val xs = ArrayList<Float>()
        val front = max(2, ceil(w / 2f / STEP).toInt())
        val fold = max(4, ceil(m / FOLD_STEP).toInt())
        for (i in 0..front) xs += sign * w / 2f * i / front
        for (i in 1..fold) xs += sign * (w / 2f + m * i / fold)
        if (left) xs.reverse()
        val ys = ArrayList<Float>()
        val tall = max(2, ceil(h / STEP).toInt())
        for (i in fold downTo 1) ys += -h / 2f - m * i / fold
        for (i in 0..tall) ys += -h / 2f + h * i / tall
        for (i in 1..fold) ys += h / 2f + m * i / fold

        val nx = xs.size
        val ny = ys.size
        val positions = Array(nx * ny) { Vec3.ZERO }
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                positions[j * nx + i] = place(xs[i], ys[j], w, h, r, m, tear, seed)
            }
        }
        val backX = 4
        val backY = 8
        val vertexCount = nx * ny + (backX + 1) * (backY + 1)
        val out = FloatArray(vertexCount * 9)
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                val p = positions[j * nx + i]
                // The normal from the neighbours, so lifted edges and folds catch the light.
                val a = positions[j * nx + min(i + 1, nx - 1)] - positions[j * nx + max(i - 1, 0)]
                val b = positions[min(j + 1, ny - 1) * nx + i] - positions[max(j - 1, 0) * nx + i]
                var n = a.cross(b)
                n = if (n.length() < 1e-10f) Vec3(0f, 0f, 1f) else n.normalized()
                put(out, j * nx + i, p, n, xs[i], ys[j], 0f)
            }
        }
        // The back: flat, never torn.
        val base = nx * ny
        for (j in 0..backY) {
            for (i in 0..backX) {
                val x = sign * w / 2f * i / backX
                val y = -h / 2f + h * j / backY
                put(out, base + j * (backX + 1) + i, Vec3(x, y, -r), Vec3(0f, 0f, -1f), x, y, 1f)
            }
        }
        val indices = ShortArray((nx - 1) * (ny - 1) * 6 + backX * backY * 6)
        var k = 0
        fun quad(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
            indices[k++] = a.toShort()
            indices[k++] = b.toShort()
            indices[k++] = c.toShort()
            indices[k++] = d.toShort()
            indices[k++] = e.toShort()
            indices[k++] = f.toShort()
        }
        for (j in 0 until ny - 1) {
            for (i in 0 until nx - 1) {
                val a = j * nx + i
                val c = a + nx
                quad(a, a + 1, c + 1, a, c + 1, c)
            }
        }
        for (j in 0 until backY) {
            for (i in 0 until backX) {
                val a = base + j * (backX + 1) + i
                val c = a + backX + 1
                quad(a, c + 1, a + 1, a, c, c + 1)
            }
        }
        return out to indices
    }

    /**
     * The point of the paper at (ux, uy) on the unfolded sheet: on the front within the comic,
     * folded round the edges (and the corners) beyond it, lifted where it is torn nearby.
     */
    private fun place(ux: Float, uy: Float, w: Float, h: Float, r: Float, m: Float, tear: WrapTear?, seed: Int): Vec3 {
        val ex = max(kotlin.math.abs(ux) - w / 2f, 0f)
        val ey = max(kotlin.math.abs(uy) - h / 2f, 0f)
        // A little loose over the front, and crinkled.
        val inside = (1f - (2f * ux / w).let { it * it }).coerceAtLeast(0f) * (1f - (2f * uy / h).let { it * it }).coerceAtLeast(0f)
        val crinkle = (WrapTear.valueNoise(ux / 0.016f, uy / 0.016f, seed) - 0.5f) * 0.0007f +
            (WrapTear.valueNoise(ux / 0.006f, uy / 0.006f, seed + 5) - 0.5f) * 0.00025f
        val lift = tear?.lift(ux, uy) ?: 0f
        val puff = 0.0009f * inside + crinkle * (0.3f + 0.7f * inside) + lift
        if (ex <= 0f && ey <= 0f) return Vec3(ux, uy, r + puff)
        val e = sqrt(ex * ex + ey * ey)
        val phi = min(e / r, PI.toFloat())
        val dx = ex / e * kotlin.math.sign(ux)
        val dy = ey / e * kotlin.math.sign(uy)
        val cx = kotlin.math.sign(ux) * min(kotlin.math.abs(ux), w / 2f)
        val cy = kotlin.math.sign(uy) * min(kotlin.math.abs(uy), h / 2f)
        val rr = r + puff * cos(phi / 2f)
        return Vec3(cx + dx * rr * sin(phi), cy + dy * rr * sin(phi), rr * cos(phi))
    }

    private fun put(out: FloatArray, index: Int, p: Vec3, n: Vec3, u: Float, v: Float, part: Float) {
        val k = index * 9
        out[k] = p.x
        out[k + 1] = p.y
        out[k + 2] = p.z
        out[k + 3] = n.x
        out[k + 4] = n.y
        out[k + 5] = n.z
        out[k + 6] = u
        out[k + 7] = v
        out[k + 8] = part
    }
}
