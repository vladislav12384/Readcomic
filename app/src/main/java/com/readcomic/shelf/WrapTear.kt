package com.readcomic.shelf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The paper wrapping of a comic being torn open with a finger.
 *
 * The paper over the front (and a little past its edges, where it folds round them: [margin]) is
 * a grid of cells of about 1.6 mm. [mask] says which are torn away; [owner] says which piece of
 * paper took each one: the strip the finger ripped, or, at the end, one of the pieces the rest
 * of the paper comes apart into. Every piece is drawn from exactly its own cells, so what comes
 * off is exactly what was there.
 *
 * The finger rips a strip along its path, wider or narrower as the tear wanders, with ragged
 * edges; the strip lies where it was at the finger and peels up and curls behind it; once torn
 * free (the finger lifts, runs off the paper or over a hole) it flutters away. [release] breaks
 * what is left of the front along a few more tears into pieces that flip off one after another.
 *
 * Coordinates: meters on the front of the comic, its centre at 0, x to the right, y up.
 * Not thread-safe: lives on the render thread.
 */
class WrapTear(val width: Float, val height: Float, val margin: Float, seed: Int) {
    private val random = Random(seed)
    private val noiseSeed = random.nextInt()

    val columns = ceil((width + 2f * margin) / CELL).toInt().coerceIn(8, MAX_CELLS)
    val rows = ceil((height + 2f * margin) / CELL).toInt().coerceIn(8, MAX_CELLS)
    private val cellX = (width + 2f * margin) / columns
    private val cellY = (height + 2f * margin) / rows
    private val left = -width / 2f - margin
    private val bottom = -height / 2f - margin

    /** Row by row from the bottom; 0 paper, 255 torn away. */
    val mask = ByteArray(columns * rows)

    /** Row by row from the bottom: the piece that took each cell (1..250), 0 none yet, 255 one that is gone. */
    val owner = ByteArray(columns * rows)

    /** Changes whenever [mask] or [owner] does. */
    var version = 0
        private set

    // How much further than the finger's strip the paper gives at every cell: a tear never runs
    // straight, it follows the fibres a few millimetres either side.
    private val rough = FloatArray(columns * rows) { i ->
        val x = left + (i % columns + 0.5f) * cellX
        val y = bottom + (i / columns + 0.5f) * cellY
        (fbm(x / 0.012f, y / 0.012f, noiseSeed) - 0.5f) * 0.0048f + (valueNoise(x / 0.0035f, y / 0.0035f, noiseSeed + 7) - 0.5f) * 0.0016f
    }

    private val frontCells: Int
    private var frontTorn = 0

    init {
        var count = 0
        for (i in mask.indices) if (isFront(i)) count++
        frontCells = max(count, 1)
    }

    /** How much of the front is torn open, 0..1. */
    val progress: Float get() = frontTorn.toFloat() / frontCells

    private fun isFront(i: Int): Boolean {
        val x = left + (i % columns + 0.5f) * cellX
        val y = bottom + (i / columns + 0.5f) * cellY
        return abs(x) <= width / 2f && abs(y) <= height / 2f
    }

    /** Whether the paper at (x, y) is torn away (or there is no paper there at all). */
    fun isTorn(x: Float, y: Float): Boolean {
        val c = floor((x - left) / cellX).toInt()
        val r = floor((y - bottom) / cellY).toInt()
        if (c !in 0 until columns || r !in 0 until rows) return true
        return mask[r * columns + c] != 0.toByte()
    }

    // region Pieces of paper

    private val used = BooleanArray(256)
    private var nextId = 1

    private fun allocate(): Int {
        repeat(MAX_ID) {
            val id = nextId
            nextId = if (nextId >= MAX_ID) 1 else nextId + 1
            if (!used[id]) {
                used[id] = true
                return id
            }
        }
        return GONE
    }

    /** A piece is gone for good: its cells belong to nobody that is drawn, its id may be used again. */
    private fun retire(id: Int) {
        if (id !in 1..MAX_ID) return
        used[id] = false
        val b = id.toByte()
        for (i in owner.indices) if (owner[i] == b) owner[i] = GONE.toByte()
        version++
    }

    // endregion

    // region Tearing

    /** A strip of paper being torn off or flying away. */
    class Scrap(val id: Int, val seed: Int) {
        /** The finger's path while it tore this strip: x, y; the newest point last. */
        val points = ArrayList<Float>()

        /** Half widths of the strip to the left and right of the path at every point. */
        val widths = ArrayList<Float>()
        var attached = true

        /** How the finger was pulling it (m/s, on the paper) when it tore free. */
        var lastPull = Vec3.ZERO

        // Once free: its shape in the world around its centre, and how it moves.
        var shape: FloatArray? = null
        var position = Vec3.ZERO
        var velocity = Vec3.ZERO
        var rotation = Quat.IDENTITY
        var spin = Vec3.ZERO
        var age = 0f

        val size get() = points.size / 2

        fun length(): Float {
            var total = 0f
            for (i in 1 until size) total += hypot(points[2 * i] - points[2 * i - 2], points[2 * i + 1] - points[2 * i - 1])
            return total
        }
    }

    val scraps = ArrayList<Scrap>()
    private var current: Scrap? = null
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    private var down = false

    /** Paper torn by the last call that tore anything, in square meters, to make it heard and felt. */
    var lastTornArea = 0f
        private set

    /** The finger comes down at (x, y): it pierces the paper there, if there is any. Returns whether it did. */
    fun touch(x: Float, y: Float): Boolean {
        down = true
        lastX = x
        lastY = y
        travelled = 0f
        current = null
        lastTornArea = 0f
        if (released || isTorn(x, y) || abs(x) > width / 2f + margin || abs(y) > height / 2f + margin) return false
        val scrap = Scrap(allocate(), random.nextInt())
        val torn = stampSegment(x, y, x, y, POKE, POKE, POKE, POKE, scrap.id)
        lastTornArea = torn * cellX * cellY
        if (torn == 0) {
            retire(scrap.id)
            return false
        }
        scrap.points += listOf(x, y)
        scrap.widths += listOf(POKE, POKE)
        scraps += scrap
        current = scrap
        return true
    }

    /**
     * The finger moved to (x, y) at [speed] m/s. Rips the paper along the way; returns the area
     * torn (square meters).
     */
    fun drag(x: Float, y: Float, speed: Float): Float {
        if (!down || released) return 0f
        val distance = hypot(x - lastX, y - lastY)
        if (distance < 1e-5f) return 0f
        val steps = max(1, ceil(distance / STEP).toInt())
        var area = 0f
        var px = lastX
        var py = lastY
        // Faster pulls rip wider strips.
        val base = BASE_HALF_WIDTH + SPEED_HALF_WIDTH * (speed / 0.5f).coerceIn(0f, 1f)
        for (k in 1..steps) {
            val nx = lastX + (x - lastX) * k / steps
            val ny = lastY + (y - lastY) * k / steps
            val s0 = travelled
            travelled += hypot(nx - px, ny - py)
            // A tear opens narrow where it starts and widens as it runs.
            val along = current?.length() ?: 0f
            val opening = (0.45f + 0.55f * (along / 0.015f)).coerceIn(0.45f, 1f)
            val l0 = halfWidth(s0, 0) * base * opening
            val r0 = halfWidth(s0, 1) * base * opening
            val l1 = halfWidth(travelled, 0) * base * opening
            val r1 = halfWidth(travelled, 1) * base * opening
            val existing = current
            val strip = existing ?: Scrap(allocate(), random.nextInt())
            val torn = stampSegment(px, py, nx, ny, l0, r0, l1, r1, strip.id)
            area += torn * cellX * cellY
            if (torn >= MIN_TORN_CELLS) {
                if (existing == null) {
                    strip.points += listOf(px, py)
                    strip.widths += listOf(l0, r0)
                    scraps += strip
                    current = strip
                }
                strip.points += listOf(nx, ny)
                strip.widths += listOf(l1, r1)
                strip.lastPull = Vec3((x - lastX) / distance * speed, (y - lastY) / distance * speed, 0f)
                // A long strip tears off by its own weight, and a new one starts.
                if (strip.length() > MAX_STRIP) {
                    strip.attached = false
                    current = null
                }
            } else if (existing == null) {
                // A crumb, not a strip: it is simply gone.
                retire(strip.id)
            } else {
                // Over a hole or off the paper: the strip has come away.
                existing.attached = false
                current = null
            }
            px = nx
            py = ny
        }
        lastX = x
        lastY = y
        lastTornArea = area
        return area
    }

    /** The finger lifts: the strip it was tearing comes away. */
    fun lift() {
        down = false
        current?.attached = false
        current = null
    }

    /** 0.5..1.5: how wide the tear runs at [s] meters along the finger's path, on [side] 0 left, 1 right. */
    private fun halfWidth(s: Float, side: Int): Float = 0.55f + 0.95f * valueNoise(s / 0.035f, side * 17.3f, noiseSeed + 11)

    /**
     * Tears the paper within the strip along a to b: [l0] / [r0] wide to the left / right at a,
     * [l1] / [r1] at b; the cells torn go to the piece [id]. Returns how many were torn now.
     */
    private fun stampSegment(ax: Float, ay: Float, bx: Float, by: Float, l0: Float, r0: Float, l1: Float, r1: Float, id: Int, roughness: Float = 1f): Int {
        val reach = max(max(l0, r0), max(l1, r1)) + ROUGH_REACH
        val c0 = floor((min(ax, bx) - reach - left) / cellX).toInt().coerceIn(0, columns - 1)
        val c1 = floor((max(ax, bx) + reach - left) / cellX).toInt().coerceIn(0, columns - 1)
        val r0i = floor((min(ay, by) - reach - bottom) / cellY).toInt().coerceIn(0, rows - 1)
        val r1i = floor((max(ay, by) + reach - bottom) / cellY).toInt().coerceIn(0, rows - 1)
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        val mark = id.toByte()
        var torn = 0
        for (r in r0i..r1i) {
            val y = bottom + (r + 0.5f) * cellY
            for (c in c0..c1) {
                val i = r * columns + c
                if (mask[i] != 0.toByte()) continue
                val x = left + (c + 0.5f) * cellX
                val t = if (length2 < 1e-12f) 0f else (((x - ax) * dx + (y - ay) * dy) / length2).coerceIn(0f, 1f)
                val qx = ax + dx * t - x
                val qy = ay + dy * t - y
                val d = sqrt(qx * qx + qy * qy)
                val leftSide = dx * (y - ay) - dy * (x - ax) >= 0f
                val radius = if (leftSide) l0 + (l1 - l0) * t else r0 + (r1 - r0) * t
                // Narrow tears wander less.
                if (d < radius + rough[i] * roughness * (radius / ROUGH_FULL).coerceIn(0.25f, 1f)) {
                    mask[i] = TORN
                    owner[i] = mark
                    torn++
                    if (isFront(i)) frontTorn++
                }
            }
        }
        if (torn > 0) {
            version++
            distanceValid = false
        }
        return torn
    }

    // endregion

    // region The rest coming off

    /** A piece of what was left of the paper, coming off at the end. */
    private class Fragment(
        val id: Int,
        /** Its paper as it lay on the comic: vertices (position, normal, uv = where on the sheet, 0). */
        val local: FloatArray,
        val indices: ShortArray,
        val center: Vec3,
        /** It flips over this line (through [pivot], along [axis]), its far side towards the reader. */
        val pivot: Vec3,
        val axis: Vec3,
        val outward: Vec3,
        val delay: Float,
        val spin: Vec3,
        val launch: Float,
    )

    private val fragments = ArrayList<Fragment>()

    /** Whether any piece of paper is still in the air (or being torn off). */
    val busy: Boolean get() = scraps.isNotEmpty() || fragments.isNotEmpty()

    /** Whether the rest of the paper has come apart (nothing more is torn by the finger). */
    var released = false
        private set
    private var releaseTime = 0f
    private var releasePosition = Vec3.ZERO
    private var releaseRotation = Quat.IDENTITY

    /**
     * What is left of the front comes apart, at [time], the comic at [position] / [rotation]
     * (the pieces fall from there): a few more tears across it, then every piece of paper they
     * leave flips off on its own. [paper] builds the shape of the paper at a point of the sheet.
     */
    fun release(position: Vec3, rotation: Quat, time: Float, paper: (Float, Float) -> Vec3) {
        if (released) return
        lift()
        released = true
        releaseTime = time
        releasePosition = position
        releaseRotation = rotation
        // A few tears across what is left, so that it comes away in pieces, not in one sheet.
        val lines = 2 + random.nextInt(2)
        for (k in 0 until lines) {
            val horizontal = k % 2 == 0
            val at = (random.nextFloat() - 0.5f) * (if (horizontal) height else width) * 0.6f
            val tilt = (random.nextFloat() - 0.5f) * 0.5f
            var s = -0.6f
            var px = 0f
            var py = 0f
            var first = true
            while (s <= 0.6f) {
                val wobble = (valueNoise(s * 9f, k * 5.3f, noiseSeed + 21) - 0.5f) * 0.012f
                val x = if (horizontal) s * width else at + tilt * s * height + wobble
                val y = if (horizontal) at + tilt * s * width + wobble else s * height
                if (!first) stampSegment(px, py, x, y, 0.0011f, 0.0011f, 0.0011f, 0.0011f, GONE, roughness = 0.35f)
                px = x
                py = y
                first = false
                s += 0.02f
            }
        }
        // The pieces: what holds together of the front that is still there.
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (seen[start] || mask[start] != 0.toByte() || !isFront(start)) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val c = i % columns
                val r = i / columns
                for ((dc, dr) in NEIGHBOURS) {
                    val nc = c + dc
                    val nr = r + dr
                    if (nc !in 0 until columns || nr !in 0 until rows) continue
                    val j = nr * columns + nc
                    if (seen[j] || mask[j] != 0.toByte() || !isFront(j)) continue
                    seen[j] = true
                    queue[tail++] = j
                }
            }
            val cells = queue.copyOf(tail)
            if (fragments.size >= MAX_FRAGMENTS) continue
            fragments += fragment(cells, paper) ?: continue
        }
        version++
    }

    private fun fragment(cells: IntArray, paper: (Float, Float) -> Vec3): Fragment? {
        val id = allocate()
        if (id == GONE) return null
        val mark = id.toByte()
        var c0 = columns
        var c1 = 0
        var r0 = rows
        var r1 = 0
        var sx = 0f
        var sy = 0f
        for (i in cells) {
            owner[i] = mark
            val c = i % columns
            val r = i / columns
            c0 = min(c0, c)
            c1 = max(c1, c)
            r0 = min(r0, r)
            r1 = max(r1, r)
            sx += left + (c + 0.5f) * cellX
            sy += bottom + (r + 0.5f) * cellY
        }
        val cx = sx / cells.size
        val cy = sy / cells.size
        // A grid over it, a little past its cells; what is not its own is not drawn.
        val x0 = left + (c0 - 1) * cellX
        val x1 = left + (c1 + 2) * cellX
        val y0 = bottom + (r0 - 1) * cellY
        val y1 = bottom + (r1 + 2) * cellY
        val nx = max(2, ceil((x1 - x0) / FRAGMENT_STEP).toInt()) + 1
        val ny = max(2, ceil((y1 - y0) / FRAGMENT_STEP).toInt()) + 1
        val positions = Array(nx * ny) { k ->
            val x = x0 + (x1 - x0) * (k % nx) / (nx - 1)
            val y = y0 + (y1 - y0) * (k / nx) / (ny - 1)
            paper(x, y)
        }
        val local = FloatArray(nx * ny * 9)
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                val k = j * nx + i
                val p = positions[k]
                val a = positions[j * nx + min(i + 1, nx - 1)] - positions[j * nx + max(i - 1, 0)]
                val b = positions[min(j + 1, ny - 1) * nx + i] - positions[max(j - 1, 0) * nx + i]
                val n = a.cross(b).let { if (it.length() < 1e-10f) Vec3(0f, 0f, 1f) else it.normalized() }
                local[k * 9] = p.x
                local[k * 9 + 1] = p.y
                local[k * 9 + 2] = p.z
                local[k * 9 + 3] = n.x
                local[k * 9 + 4] = n.y
                local[k * 9 + 5] = n.z
                local[k * 9 + 6] = x0 + (x1 - x0) * i / (nx - 1)
                local[k * 9 + 7] = y0 + (y1 - y0) * j / (ny - 1)
            }
        }
        val indices = ShortArray((nx - 1) * (ny - 1) * 6)
        var k = 0
        for (j in 0 until ny - 1) {
            for (i in 0 until nx - 1) {
                val a = j * nx + i
                val c = a + nx
                indices[k++] = a.toShort()
                indices[k++] = (a + 1).toShort()
                indices[k++] = (c + 1).toShort()
                indices[k++] = a.toShort()
                indices[k++] = (c + 1).toShort()
                indices[k++] = c.toShort()
            }
        }
        // Off away from the middle of the comic, its inner edge the last to let go.
        val out = hypot(cx, cy).let { d -> if (d < 0.01f) Vec3(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, 0f).let { it * (1f / max(it.length(), 1e-3f)) } else Vec3(cx / d, cy / d, 0f) }
        var reach = 0f
        for (i in cells) {
            val x = left + (i % columns + 0.5f) * cellX - cx
            val y = bottom + (i / columns + 0.5f) * cellY - cy
            reach = max(reach, -(x * out.x + y * out.y))
        }
        val center = Vec3(cx, cy, paper(cx, cy).z)
        val pivot = center - out * reach
        val spinAxis = Vec3(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f)
        return Fragment(
            id = id,
            local = local,
            indices = indices,
            center = center,
            pivot = pivot,
            axis = out.cross(Vec3(0f, 0f, 1f)).normalized(),
            outward = out,
            delay = 0.04f + 0.35f * random.nextFloat() * random.nextFloat() + 0.2f * hypot(cx / width, cy / height),
            spin = if (spinAxis.length() < 1e-3f) Vec3(0f, 1f, 0f) else spinAxis.normalized() * (2f + 3.5f * random.nextFloat()),
            launch = 0.7f + 0.6f * random.nextFloat(),
        )
    }

    /** A fragment [tau] seconds into coming off: where a point of it ([p], in the comic's meters) is. */
    private fun place(f: Fragment, p: Vec3, tau: Float): Vec3 {
        if (tau <= 0f) return p
        // It flips up about its inner edge...
        val e = (tau / FLIP_TIME).coerceIn(0f, 1f)
        val angle = FLIP_ANGLE * e * e * (3f - 2f * e)
        val flip = Quat.axisAngle(f.axis, angle)
        var q = f.pivot + flip.rotate(p - f.pivot)
        // ...then lets go and falls, turning over.
        val free = max(tau - FLIP_TIME * 0.6f, 0f)
        if (free > 0f) {
            val c = f.pivot + flip.rotate(f.center - f.pivot)
            val turn = Quat.axisAngle(f.spin, f.spin.length() * free)
            q = c + turn.rotate(q - c)
            val velocity = f.outward * (0.16f * f.launch) + Vec3(0f, 0.18f, 0.22f * f.launch)
            q = q + velocity * free + Vec3(sin(free * 6f + f.id) * 0.012f, -0.5f * PIECE_GRAVITY * free * free, 0f)
        }
        return q
    }

    // endregion

    // region Lifted edges

    private val distance = FloatArray(columns * rows)
    private var distanceValid = false

    /**
     * How far (m) the paper at (x, y) rises off the comic: torn edges curl up a few millimetres,
     * fading with the distance from the tear.
     */
    fun lift(x: Float, y: Float): Float {
        if (frontTorn == 0 && version == 0) return 0f
        if (!distanceValid) updateDistance()
        val fx = ((x - left) / cellX - 0.5f).coerceIn(0f, columns - 1f)
        val fy = ((y - bottom) / cellY - 0.5f).coerceIn(0f, rows - 1f)
        val c = min(fx.toInt(), columns - 2).coerceAtLeast(0)
        val r = min(fy.toInt(), rows - 2).coerceAtLeast(0)
        val tx = fx - c
        val ty = fy - r
        val i = r * columns + c
        val d = (distance[i] * (1 - tx) + distance[i + 1] * tx) * (1 - ty) +
            (distance[i + columns] * (1 - tx) + distance[i + columns + 1] * tx) * ty
        return EDGE_LIFT * exp(-d / EDGE_LIFT_REACH)
    }

    /** Distance of every cell from the nearest torn one (chamfer, two passes). */
    private fun updateDistance() {
        val far = 1f
        val diagonal = hypot(cellX, cellY)
        for (i in distance.indices) distance[i] = if (mask[i] != 0.toByte()) 0f else far
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val i = r * columns + c
                var d = distance[i]
                if (c > 0) d = min(d, distance[i - 1] + cellX)
                if (r > 0) {
                    d = min(d, distance[i - columns] + cellY)
                    if (c > 0) d = min(d, distance[i - columns - 1] + diagonal)
                    if (c < columns - 1) d = min(d, distance[i - columns + 1] + diagonal)
                }
                distance[i] = d
            }
        }
        for (r in rows - 1 downTo 0) {
            for (c in columns - 1 downTo 0) {
                val i = r * columns + c
                var d = distance[i]
                if (c < columns - 1) d = min(d, distance[i + 1] + cellX)
                if (r < rows - 1) {
                    d = min(d, distance[i + columns] + cellY)
                    if (c < columns - 1) d = min(d, distance[i + columns + 1] + diagonal)
                    if (c > 0) d = min(d, distance[i + columns - 1] + diagonal)
                }
                distance[i] = d
            }
        }
        distanceValid = true
    }

    // endregion

    // region Pieces in the air

    /**
     * Moves the free strips and the pieces coming off for [dt] seconds, [time] now; [position] /
     * [rotation] place the comic, [paper] is how far the paper lies in front of its centre.
     * Returns whether any is still to be seen.
     */
    fun stepScraps(position: Vec3, rotation: Quat, paper: Float, dt: Float, time: Float): Boolean {
        val iterator = scraps.iterator()
        while (iterator.hasNext()) {
            val scrap = iterator.next()
            if (scrap.attached) continue
            if (scrap.shape == null) free(scrap, position, rotation, paper, time)
            scrap.age += dt
            // Paper falls slowly, held back by the air, rocking from side to side.
            val rock = sin(scrap.age * 7f + scrap.seed % 7) * 0.9f
            scrap.velocity = scrap.velocity + Vec3(rock * 0.6f, -FALL_GRAVITY, 0f) * dt
            scrap.velocity = scrap.velocity * exp(-AIR_DRAG * dt)
            scrap.position = scrap.position + scrap.velocity * dt
            val spin = scrap.spin * exp(-0.4f * scrap.age)
            val angle = spin.length() * dt
            if (angle > 1e-6f) scrap.rotation = (Quat.axisAngle(spin, angle) * scrap.rotation).normalizedQuat()
            if (scrap.age > SCRAP_LIFE) {
                iterator.remove()
                retire(scrap.id)
            }
        }
        val done = fragments.filter { time - releaseTime - it.delay > FRAGMENT_LIFE }
        if (done.isNotEmpty()) {
            fragments.removeAll(done.toSet())
            done.forEach { retire(it.id) }
        }
        return scraps.isNotEmpty() || fragments.isNotEmpty()
    }

    /** Torn off: its shape as it is now goes into the world, and it starts falling. */
    private fun free(scrap: Scrap, position: Vec3, rotation: Quat, paper: Float, time: Float) {
        val local = shapeOf(scrap, paper, time)
        val world = FloatArray(local.size)
        var cx = 0f
        var cy = 0f
        var cz = 0f
        val count = local.size / 9
        for (v in 0 until count) {
            val p = position + rotation.rotate(Vec3(local[v * 9], local[v * 9 + 1], local[v * 9 + 2]))
            val n = rotation.rotate(Vec3(local[v * 9 + 3], local[v * 9 + 4], local[v * 9 + 5]))
            world[v * 9] = p.x
            world[v * 9 + 1] = p.y
            world[v * 9 + 2] = p.z
            world[v * 9 + 3] = n.x
            world[v * 9 + 4] = n.y
            world[v * 9 + 5] = n.z
            world[v * 9 + 6] = local[v * 9 + 6]
            world[v * 9 + 7] = local[v * 9 + 7]
            world[v * 9 + 8] = 0f
            cx += p.x
            cy += p.y
            cz += p.z
        }
        val center = Vec3(cx / count, cy / count, cz / count)
        for (v in 0 until count) {
            world[v * 9] -= center.x
            world[v * 9 + 1] -= center.y
            world[v * 9 + 2] -= center.z
        }
        scrap.shape = world
        scrap.position = center
        scrap.rotation = Quat.IDENTITY
        val r = Random(scrap.seed)
        // Off towards the reader and up a little, then down; turning over as it goes.
        val out = rotation.rotate(Vec3(0f, 0f, 1f))
        val pull = rotation.rotate(scrap.lastPull)
        scrap.velocity = out * (0.25f + 0.2f * r.nextFloat()) + pull * 0.35f + Vec3(0f, 0.35f, 0f)
        scrap.spin = Vec3(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f).let {
            if (it.length() < 1e-3f) Vec3(1f, 0f, 0f) else it.normalized() * (2.5f + 3f * r.nextFloat())
        }
    }

    /**
     * Every piece of paper off the comic or coming off, as triangles in the world: vertices
     * (position, normal, uv, code) and indices. uv is where on the sheet the paper was (so the
     * piece is drawn from exactly its cells); the code says which piece and how far along it is:
     * a strip being torn (its id + 0.98 × how much it still lies on the cover), a strip in the
     * air (256 + id + 0.98 × age), or a piece of the rest coming off (512 + id + 0.98 × age).
     * Null when there are none.
     */
    fun pieceMesh(position: Vec3, rotation: Quat, paper: Float, time: Float): Pair<FloatArray, ShortArray>? {
        if (scraps.none { it.size >= 2 } && fragments.isEmpty()) return null
        val parts = ArrayList<FloatArray>()
        val partIndices = ArrayList<ShortArray>()
        var base = 0
        for (scrap in scraps) {
            if (scrap.size < 2) continue
            val mesh = scrap.shape?.let { shape ->
                val age = (scrap.age / SCRAP_LIFE).coerceIn(0f, 1f)
                val out = FloatArray(shape.size)
                val count = shape.size / 9
                for (v in 0 until count) {
                    val p = scrap.position + scrap.rotation.rotate(Vec3(shape[v * 9], shape[v * 9 + 1], shape[v * 9 + 2]))
                    val n = scrap.rotation.rotate(Vec3(shape[v * 9 + 3], shape[v * 9 + 4], shape[v * 9 + 5]))
                    out[v * 9] = p.x
                    out[v * 9 + 1] = p.y
                    out[v * 9 + 2] = p.z
                    out[v * 9 + 3] = n.x
                    out[v * 9 + 4] = n.y
                    out[v * 9 + 5] = n.z
                    out[v * 9 + 6] = shape[v * 9 + 6]
                    out[v * 9 + 7] = shape[v * 9 + 7]
                    out[v * 9 + 8] = 256f + scrap.id + 0.98f * age
                }
                out
            } ?: run {
                val local = shapeOf(scrap, paper, time)
                val count = local.size / 9
                for (v in 0 until count) {
                    val p = position + rotation.rotate(Vec3(local[v * 9], local[v * 9 + 1], local[v * 9 + 2]))
                    val n = rotation.rotate(Vec3(local[v * 9 + 3], local[v * 9 + 4], local[v * 9 + 5]))
                    local[v * 9] = p.x
                    local[v * 9 + 1] = p.y
                    local[v * 9 + 2] = p.z
                    local[v * 9 + 3] = n.x
                    local[v * 9 + 4] = n.y
                    local[v * 9 + 5] = n.z
                    local[v * 9 + 8] = scrap.id + 0.98f * local[v * 9 + 8]
                }
                local
            }
            val rowsCount = mesh.size / 27
            val indices = ShortArray((rowsCount - 1) * 12)
            var k = 0
            for (row in 0 until rowsCount - 1) {
                for (column in 0..1) {
                    val a = base + row * 3 + column
                    val c = a + 3
                    indices[k++] = a.toShort()
                    indices[k++] = c.toShort()
                    indices[k++] = (a + 1).toShort()
                    indices[k++] = (a + 1).toShort()
                    indices[k++] = c.toShort()
                    indices[k++] = (c + 1).toShort()
                }
            }
            parts += mesh
            partIndices += indices
            base += mesh.size / 9
            if (base > 40000) break
        }
        for (f in fragments) {
            if (base + f.local.size / 9 > 65000) break
            val tau = time - releaseTime - f.delay
            val age = (tau / FRAGMENT_LIFE).coerceIn(0f, 1f)
            val out = FloatArray(f.local.size)
            val count = f.local.size / 9
            val turn = turnOf(f, tau)
            for (v in 0 until count) {
                val p = place(f, Vec3(f.local[v * 9], f.local[v * 9 + 1], f.local[v * 9 + 2]), tau)
                val n = turn.rotate(Vec3(f.local[v * 9 + 3], f.local[v * 9 + 4], f.local[v * 9 + 5]))
                val world = releasePosition + releaseRotation.rotate(p)
                val normal = releaseRotation.rotate(n)
                out[v * 9] = world.x
                out[v * 9 + 1] = world.y
                out[v * 9 + 2] = world.z
                out[v * 9 + 3] = normal.x
                out[v * 9 + 4] = normal.y
                out[v * 9 + 5] = normal.z
                out[v * 9 + 6] = f.local[v * 9 + 6]
                out[v * 9 + 7] = f.local[v * 9 + 7]
                out[v * 9 + 8] = 512f + f.id + 0.98f * age
            }
            parts += out
            partIndices += ShortArray(f.indices.size) { (f.indices[it] + base).toShort() }
            base += count
        }
        if (parts.isEmpty()) return null
        val flat = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(flat, offset)
            offset += part.size
        }
        val indices = ShortArray(partIndices.sumOf { it.size })
        offset = 0
        for (part in partIndices) {
            part.copyInto(indices, offset)
            offset += part.size
        }
        return flat to indices
    }

    /** How a fragment is turned [tau] seconds into coming off (for its normals). */
    private fun turnOf(f: Fragment, tau: Float): Quat {
        if (tau <= 0f) return Quat.IDENTITY
        val e = (tau / FLIP_TIME).coerceIn(0f, 1f)
        val flip = Quat.axisAngle(f.axis, FLIP_ANGLE * e * e * (3f - 2f * e))
        val free = max(tau - FLIP_TIME * 0.6f, 0f)
        return if (free > 0f) Quat.axisAngle(f.spin, f.spin.length() * free) * flip else flip
    }

    /**
     * A strip in the comic's own coordinates: lying on the paper where it is still being torn
     * (at the finger), peeling up and curling back over the path behind it. A little wider than
     * the strip itself, so that every cell it took is covered; uv is where on the sheet each
     * point was, the code slot how much it still lies on the cover.
     */
    private fun shapeOf(scrap: Scrap, paper: Float, time: Float): FloatArray {
        val n = scrap.size
        val out = FloatArray(n * 3 * 9)
        // Arc length from the newest point (the tear front) back to every point.
        val s = FloatArray(n)
        for (i in n - 2 downTo 0) {
            s[i] = s[i + 1] + hypot(scrap.points[2 * i + 2] - scrap.points[2 * i], scrap.points[2 * i + 3] - scrap.points[2 * i + 1])
        }
        val fx = scrap.points[2 * n - 2]
        val fy = scrap.points[2 * n - 1]
        val flutter = sin(time * 5f + scrap.seed % 11) * 0.1f
        val centers = Array(n) { Vec3.ZERO }
        val ups = FloatArray(n)
        var dirX = 0f
        var dirY = -1f
        for (i in n - 1 downTo 0) {
            val px = scrap.points[2 * i]
            val py = scrap.points[2 * i + 1]
            val dx = px - fx
            val dy = py - fy
            val d = hypot(dx, dy)
            if (d > 1e-4f) {
                dirX = dx / d
                dirY = dy / d
            }
            // Rolled up: the angle off the paper grows along the strip, up to standing over it.
            val (along, up) = curl(s[i], flutter)
            // Where it still lies flat it lies exactly where it was.
            val flat = 1f - smoothstep(0f, 0.012f, s[i])
            centers[i] = Vec3(
                px + (fx + dirX * along - px) * (1f - flat),
                py + (fy + dirY * along - py) * (1f - flat),
                paper + up + 0.00035f,
            )
            ups[i] = up
        }
        for (i in 0 until n) {
            val prev = if (i > 0) Pair(scrap.points[2 * i - 2], scrap.points[2 * i - 1]) else Pair(scrap.points[2 * i], scrap.points[2 * i + 1])
            val next = if (i < n - 1) Pair(scrap.points[2 * i + 2], scrap.points[2 * i + 3]) else Pair(scrap.points[2 * i], scrap.points[2 * i + 1])
            var tx = next.first - prev.first
            var ty = next.second - prev.second
            val tl = hypot(tx, ty)
            if (tl > 1e-6f) {
                tx /= tl
                ty /= tl
            } else {
                tx = 0f
                ty = 1f
            }
            // Across the strip, in the plane of the paper: the strip curls about this axis.
            val across = Vec3(-ty, tx, 0f)
            val tangent3 = (centers[min(i + 1, n - 1)] - centers[max(i - 1, 0)]).let { if (it.length() < 1e-6f) Vec3(tx, ty, 0f) else it.normalized() }
            var normal = across.cross(tangent3).let { if (it.length() < 1e-6f) Vec3(0f, 0f, 1f) else it.normalized() }
            if (normal.z < 0f && s[i] < 0.01f) normal = normal * -1f
            val wl = scrap.widths[2 * i] + COVER
            val wr = scrap.widths[2 * i + 1] + COVER
            val points = arrayOf(centers[i] + across * wl, centers[i], centers[i] - across * wr)
            val px = scrap.points[2 * i]
            val py = scrap.points[2 * i + 1]
            val sheet = arrayOf(px - ty * wl to py + tx * wl, px to py, px + ty * wr to py - tx * wr)
            val onCover = 1f - smoothstep(0.0003f, 0.004f, ups[i])
            for (column in 0..2) {
                val k = (i * 3 + column) * 9
                val p = points[column]
                out[k] = p.x
                out[k + 1] = p.y
                out[k + 2] = p.z
                out[k + 3] = normal.x
                out[k + 4] = normal.y
                out[k + 5] = normal.z
                out[k + 6] = sheet[column].first
                out[k + 7] = sheet[column].second
                out[k + 8] = onCover
            }
        }
        return out
    }

    /** Along the paper and up off it at [s] meters from the tear front of a peeling strip. */
    private fun curl(s: Float, flutter: Float): Pair<Float, Float> {
        // Bent with a radius of a few centimetres, up to past upright, then straight.
        val angleMax = CURL_ANGLE + flutter
        val r = CURL_RADIUS
        val bendLength = r * angleMax
        return if (s <= bendLength) {
            val theta = s / r
            Pair(r * sin(theta), r * (1f - cos(theta)))
        } else {
            val along = r * sin(angleMax) + (s - bendLength) * cos(angleMax)
            val up = r * (1f - cos(angleMax)) + (s - bendLength) * sin(angleMax)
            Pair(along, up)
        }
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // endregion

    companion object {
        const val CELL = 0.0016f
        private const val MAX_CELLS = 256

        /** Ids of pieces of paper: 1..[MAX_ID]; [GONE] marks cells of pieces no longer drawn. */
        private const val MAX_ID = 250
        const val GONE = 255
        private val TORN = 255.toByte()
        private const val POKE = 0.0035f
        private const val STEP = 0.0025f
        private const val BASE_HALF_WIDTH = 0.0085f
        private const val SPEED_HALF_WIDTH = 0.004f
        private const val ROUGH_REACH = 0.004f
        private const val ROUGH_FULL = 0.009f

        /** How much wider than the strip its shape is drawn, so that all of its cells are covered. */
        private const val COVER = ROUGH_REACH + CELL
        private const val MIN_TORN_CELLS = 3
        private const val MAX_STRIP = 0.15f
        private const val EDGE_LIFT = 0.0022f
        private const val EDGE_LIFT_REACH = 0.003f
        private const val CURL_RADIUS = 0.028f
        private const val CURL_ANGLE = 1.7f
        private const val FALL_GRAVITY = 3.2f
        private const val PIECE_GRAVITY = 2.2f
        private const val AIR_DRAG = 1.8f
        const val SCRAP_LIFE = 2.2f
        private const val MAX_FRAGMENTS = 60
        private const val FRAGMENT_STEP = 0.0032f
        private const val FLIP_TIME = 0.38f
        private const val FLIP_ANGLE = 1.25f
        const val FRAGMENT_LIFE = 1.4f
        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        private fun hash(x: Int, y: Int, seed: Int): Float {
            var h = x * 374761393 + y * 668265263 + seed * 144269504
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777215f
        }

        /** Smooth value noise in 0..1. */
        fun valueNoise(x: Float, y: Float, seed: Int): Float {
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            val fx = x - ix
            val fy = y - iy
            val ux = fx * fx * (3f - 2f * fx)
            val uy = fy * fy * (3f - 2f * fy)
            val a = hash(ix, iy, seed)
            val b = hash(ix + 1, iy, seed)
            val c = hash(ix, iy + 1, seed)
            val d = hash(ix + 1, iy + 1, seed)
            return (a + (b - a) * ux) * (1 - uy) + (c + (d - c) * ux) * uy
        }

        private fun fbm(x: Float, y: Float, seed: Int): Float =
            valueNoise(x, y, seed) * 0.6f + valueNoise(x * 2.1f, y * 2.1f, seed + 1) * 0.3f + valueNoise(x * 4.3f, y * 4.3f, seed + 2) * 0.1f
    }
}

/** The quaternion scaled back to unit length (small steps drift). */
internal fun Quat.normalizedQuat(): Quat {
    val l = sqrt(x * x + y * y + z * z + w * w)
    return if (l < 1e-9f) Quat.IDENTITY else Quat(x / l, y / l, z / l, w / l)
}
