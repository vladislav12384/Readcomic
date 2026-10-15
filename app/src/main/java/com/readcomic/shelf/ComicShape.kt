package com.readcomic.shelf

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a comic is bound, which decides its shape. */
enum class Binding {
    /** A thin issue: folded sheets held by two staples through the rounded fold. */
    STAPLED,

    /** A trade paperback: sheets glued into a flat spine, the soft cover wrapped around them. */
    PAPERBACK,

    /** A thick volume: stiff boards larger than the pages, a rounded spine, hinges along it. */
    HARDCOVER,
}

/**
 * The 3D shape of a comic. Built in meters around the comic's centre (x across, the spine at
 * -width / 2; y up; z through the thickness, the front cover at +thickness / 2) and stored in
 * the unit box, so the comic's model matrix (which scales by its size) puts it in place.
 *
 * Vertex layout: position, normal, uv, part. Parts: 0 front cover, 1 back cover, 2 spine (and
 * the hinges), 3 fore-edge of the pages, 4 top of the pages, 5 bottom of the pages, 6 edges of
 * the boards, 7 insides of the cover and endpapers.
 */
object ComicShape {
    const val PART_FRONT = 0
    const val PART_BACK = 1
    const val PART_SPINE = 2
    const val PART_FORE_EDGE = 3
    const val PART_TOP = 4
    const val PART_BOTTOM = 5
    const val PART_BOARD_EDGE = 6
    const val PART_INSIDE = 7

    /** Stiff boards of a hardcover. */
    const val BOARD = 0.0022f

    /** How far the boards of a hardcover stand out past the pages. */
    const val SQUARE = 0.0025f

    private const val HINGE = 0.006f

    fun binding(pageCount: Int) = when {
        pageCount <= 64 -> Binding.STAPLED
        pageCount < 200 -> Binding.PAPERBACK
        else -> Binding.HARDCOVER
    }

    /** Thickness in meters: real paper is about 0.1 mm a sheet, plus the covers. */
    fun thickness(pageCount: Int): Float {
        val sheets = (pageCount + 1) / 2
        val paper = 0.0007f + sheets * 0.00011f
        val total = if (binding(pageCount) == Binding.HARDCOVER) paper + 2 * BOARD else paper
        return total.coerceIn(0.0012f, 0.045f)
    }

    /** Radius of the rounding where the spine meets the covers (the whole fold for stapled comics). */
    fun spineRadius(binding: Binding, thickness: Float) = when (binding) {
        Binding.STAPLED -> thickness / 2f
        Binding.PAPERBACK -> min(0.0015f, thickness * 0.3f)
        Binding.HARDCOVER -> min(thickness * 0.18f, 0.005f)
    }

    class Mesh(val vertices: FloatArray, val indices: ShortArray)

    fun build(width: Float, height: Float, thickness: Float, binding: Binding): Mesh {
        val builder = Builder(width, height, thickness)
        when (binding) {
            Binding.STAPLED -> builder.stapled()
            Binding.PAPERBACK -> builder.paperback()
            Binding.HARDCOVER -> builder.hardcover()
        }
        return builder.mesh()
    }

    /** A point of a cross-section (seen from above) with its outward normal. */
    private class Point(val x: Float, val z: Float, val nx: Float, val nz: Float, val u: Float)

    /** A run of cross-section points with one material; normals are smooth along it. */
    private class Run(val part: Int, val points: List<Point>)

    private class Builder(val w: Float, val h: Float, val t: Float) {
        private val vertices = ArrayList<Float>(4096)
        private val indices = ArrayList<Short>(4096)

        fun mesh() = Mesh(vertices.toFloatArray(), indices.toShortArray())

        private fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, part: Int): Int {
            // Into the unit box; normals go the other way (the inverse transpose of the scale).
            var ax = nx * w
            var ay = ny * h
            var az = nz * t
            val length = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-9f)
            ax /= length
            ay /= length
            az /= length
            vertices += listOf(x / w, y / h, z / t, ax, ay, az, u, v, part.toFloat())
            return vertices.size / 9 - 1
        }

        private fun quad(a: Int, b: Int, c: Int, d: Int) {
            indices += listOf(a.toShort(), b.toShort(), c.toShort(), a.toShort(), c.toShort(), d.toShort())
        }

        private fun v(y: Float) = (h / 2f - y) / h

        /** The walls of [run] from [y0] up to [y1], facing out of the cross-section. */
        private fun wall(run: Run, y0: Float, y1: Float) {
            var previous: Pair<Int, Int>? = null
            for (p in run.points) {
                val bottom = vertex(p.x, y0, p.z, p.nx, 0f, p.nz, p.u, v(y0), run.part)
                val top = vertex(p.x, y1, p.z, p.nx, 0f, p.nz, p.u, v(y1), run.part)
                previous?.let { (b0, t0) -> quad(b0, bottom, top, t0) }
                previous = bottom to top
            }
        }

        /** A flat cap at [y] over the cross-section [outline], fanned from a point inside it. */
        private fun cap(outline: List<Point>, y: Float, cx: Float, cz: Float, part: Int) {
            val ny = if (part == PART_BOTTOM) -1f else 1f
            val center = vertex(cx, y, cz, 0f, ny, 0f, 0f, 0f, part)
            val ring = outline.map { vertex(it.x, y, it.z, 0f, ny, 0f, 0f, 0f, part) }
            for (i in ring.indices) {
                val next = ring[(i + 1) % ring.size]
                indices += listOf(center.toShort(), ring[i].toShort(), next.toShort())
            }
        }

        /** A shell of thickness [depth] along [runs], capped at both ends and at both sides. */
        private fun shell(runs: List<Run>, depth: Float, y0: Float, y1: Float) {
            for (run in runs) {
                wall(run, y0, y1)
                val inner = Run(PART_INSIDE, run.points.map { Point(it.x - it.nx * depth, it.z - it.nz * depth, -it.nx, -it.nz, it.u) })
                wall(inner, y0, y1)
                for (y in listOf(y0, y1)) {
                    val ny = if (y == y0) -1f else 1f
                    var previous: Pair<Int, Int>? = null
                    for ((outer, inside) in run.points.zip(inner.points)) {
                        val a = vertex(outer.x, y, outer.z, 0f, ny, 0f, 0f, 0f, PART_BOARD_EDGE)
                        val b = vertex(inside.x, y, inside.z, 0f, ny, 0f, 0f, 0f, PART_BOARD_EDGE)
                        previous?.let { (pa, pb) -> quad(pa, a, b, pb) }
                        previous = a to b
                    }
                }
            }
            // The cut edges of the boards along the fore-edge.
            for (run in listOf(runs.first(), runs.last())) {
                val end = if (run === runs.first()) run.points.first() else run.points.last()
                val nx = 1f
                val a = vertex(end.x, y0, end.z, nx, 0f, 0f, 0f, 0f, PART_BOARD_EDGE)
                val b = vertex(end.x, y1, end.z, nx, 0f, 0f, 0f, 0f, PART_BOARD_EDGE)
                val c = vertex(end.x, y1, end.z - end.nz * depth, nx, 0f, 0f, 0f, 0f, PART_BOARD_EDGE)
                val d = vertex(end.x, y0, end.z - end.nz * depth, nx, 0f, 0f, 0f, 0f, PART_BOARD_EDGE)
                quad(a, b, c, d)
            }
        }

        private fun arc(cx: Float, cz: Float, radius: Float, from: Float, to: Float, steps: Int, u0: Float, u1: Float): List<Point> =
            (0..steps).map { i ->
                val f = i / steps.toFloat()
                val angle = from + (to - from) * f
                val nx = cos(angle)
                val nz = sin(angle)
                Point(cx + nx * radius, cz + nz * radius, nx, nz, u0 + (u1 - u0) * f)
            }

        /** Front, fore-edge and back of a block that is flush all round (stapled and paperback). */
        private fun flushSides(spineX: Float, frontStart: Float, backStart: Float): List<Run> {
            val half = t / 2f
            val right = w / 2f
            val front = Run(PART_FRONT, listOf(Point(frontStart, half, 0f, 1f, (frontStart + w / 2f) / w), Point(right, half, 0f, 1f, 1f)))
            val fore = Run(PART_FORE_EDGE, listOf(Point(right, half, 1f, 0f, 1f), Point(right, -half, 1f, 0f, 0f)))
            val back = Run(PART_BACK, listOf(Point(right, -half, 0f, -1f, 0f), Point(backStart, -half, 0f, -1f, (w / 2f - backStart) / w)))
            check(spineX <= frontStart)
            return listOf(front, fore, back)
        }

        fun stapled() {
            val r = t / 2f
            val cx = -w / 2f + r
            // The fold: half a circle from the back cover round to the front.
            val spine = Run(PART_SPINE, arc(cx, 0f, r, (-PI / 2).toFloat(), (-3 * PI / 2).toFloat(), 14, 0f, 1f))
            val runs = flushSides(-w / 2f, cx, cx) + spine
            runs.forEach { wall(it, -h / 2f, h / 2f) }
            val outline = runs.flatMap { it.points.dropLast(1) }
            cap(outline, h / 2f, 0f, 0f, PART_TOP)
            cap(outline, -h / 2f, 0f, 0f, PART_BOTTOM)
        }

        fun paperback() {
            val r = spineRadius(Binding.PAPERBACK, t)
            val x0 = -w / 2f + r
            val half = t / 2f
            // The square spine with rounded corners, from the back round to the front.
            val corners = arc(x0, -half + r, r, (-PI / 2).toFloat(), (-PI).toFloat(), 4, 0f, 0f) +
                arc(x0, half - r, r, (-PI).toFloat(), (-3 * PI / 2).toFloat(), 4, 0f, 0f)
            val spineLength = corners.zipWithNext { a, b -> sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) }
            val total = spineLength.sum()
            var walked = 0f
            val spinePoints = corners.mapIndexed { i, p ->
                if (i > 0) walked += spineLength[i - 1]
                Point(p.x, p.z, p.nx, p.nz, walked / total)
            }
            val runs = flushSides(-w / 2f, x0, x0) + Run(PART_SPINE, spinePoints)
            runs.forEach { wall(it, -h / 2f, h / 2f) }
            val outline = runs.flatMap { it.points.dropLast(1) }
            cap(outline, h / 2f, 0f, 0f, PART_TOP)
            cap(outline, -h / 2f, 0f, 0f, PART_BOTTOM)
        }

        fun hardcover() {
            val half = t / 2f
            val b = min(BOARD, t * 0.2f)
            val sagitta = spineRadius(Binding.HARDCOVER, t)
            // The rounded spine: an arc through both covers' edges, bulging out by the sagitta.
            val radius = (sagitta * sagitta + half * half) / (2f * sagitta)
            val centerX = -w / 2f + radius
            val alpha = asin((half / radius).coerceAtMost(1f))
            val spineEnd = -w / 2f + sagitta
            val hingeEnd = spineEnd + HINGE
            val right = w / 2f

            fun side(front: Boolean): List<Run> {
                val s = if (front) 1f else -1f
                val part = if (front) PART_FRONT else PART_BACK
                fun u(x: Float) = if (front) (x + w / 2f) / w else (w / 2f - x) / w
                // A groove along the hinge, where the cover bends when the book opens.
                val groove = 0.0007f
                val hinge = Run(
                    PART_SPINE,
                    (0..6).map { i ->
                        val f = i / 6f
                        val x = spineEnd + HINGE * f
                        val dip = sin(PI.toFloat() * f)
                        // The surface is z = s (half - groove sin(pi f)); its outward normal leans along x by the slope.
                        val nx = cos(PI.toFloat() * f) * groove * PI.toFloat() / HINGE
                        val length = sqrt(nx * nx + 1f)
                        Point(x, s * (half - groove * dip), nx / length, s / length, if (front) 1f else 0f)
                    },
                )
                val board = Run(part, listOf(Point(hingeEnd, s * half, 0f, s, u(hingeEnd)), Point(right, s * half, 0f, s, u(right))))
                return listOf(hinge, board)
            }

            val spine = Run(
                PART_SPINE,
                arc(centerX, 0f, radius, (PI + alpha).toFloat(), (PI - alpha).toFloat(), 10, 0f, 1f),
            )
            val back = side(front = false).reversed().map { Run(it.part, it.points.reversed()) }
            val front = side(front = true)
            shell(back + spine + front, b, -h / 2f, h / 2f)

            // The block of pages, a little smaller than the boards: its spine side follows the
            // rounded back and its fore-edge is hollowed the same way.
            val top = h / 2f - SQUARE
            val blockHalf = half - b
            val foreX = right - SQUARE
            val hollow = sagitta * 0.8f
            val steps = 8
            val fore = (0..steps).map { i ->
                val z = blockHalf - 2f * blockHalf * i / steps
                val k = z / blockHalf
                val x = foreX - hollow * (1f - k * k)
                // d x / d z = 2 hollow z / blockHalf^2: the normal leans towards the middle.
                val slope = 2f * hollow * z / (blockHalf * blockHalf)
                val length = sqrt(1f + slope * slope)
                Point(x, z, 1f / length, -slope / length, (z + blockHalf) / (2f * blockHalf))
            }
            val innerRadius = radius - b
            val spineSide = arc(centerX, 0f, innerRadius, (PI - alpha).toFloat(), (PI + alpha).toFloat(), 8, 0f, 0f)
                .filter { it.z < blockHalf && it.z > -blockHalf }
            val blockFront = Run(PART_INSIDE, listOf(Point(spineEnd, blockHalf, 0f, 1f, 0f), Point(fore.first().x, blockHalf, 0f, 1f, 0f)))
            val blockBack = Run(PART_INSIDE, listOf(Point(fore.last().x, -blockHalf, 0f, -1f, 0f), Point(spineEnd, -blockHalf, 0f, -1f, 0f)))
            val foreRun = Run(PART_FORE_EDGE, fore)
            listOf(blockFront, foreRun, blockBack).forEach { wall(it, -top, top) }
            val outline = blockFront.points.dropLast(1) + fore.dropLast(1) + blockBack.points.dropLast(1) +
                spineSide.reversed().ifEmpty { listOf(Point(spineEnd - sagitta * 0.5f, 0f, -1f, 0f, 0f)) }
            cap(outline, top, 0f, 0f, PART_TOP)
            cap(outline, -top, 0f, 0f, PART_BOTTOM)
        }
    }
}
