package com.readcomic.shelf

import com.readcomic.curl.PageTurnController
import com.readcomic.curl.Spring2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/** A comic on the shelf. */
class ShelfItem(
    val id: String,
    /** Cover width / height. */
    val aspect: Float,
    val pageCount: Int,
    /** Being read: a bookmark ribbon sticks out of it. */
    val inProgress: Boolean,
)

class ComicDraw(
    val id: String,
    val model: FloatArray,
    /** Width, height and thickness in meters. */
    val size: Vec3,
    /** Pages, for the lines of the sheets on the page edges. */
    val pages: Int,
    val binding: Binding,
    val ribbon: FloatArray?,
    val held: Boolean,
)

class LabelDraw(val id: String, val model: FloatArray)

/** Everything the renderer needs for one frame (all in meters, Y up, the wall is the plane z = 0). */
class ShelfFrame(
    val viewProj: FloatArray,
    val eye: Vec3,
    val lightViewProj: FloatArray,
    val light: Vec3,
    val wall: FloatArray,
    val planks: List<FloatArray>,
    /** The top board of the bookcase, when it is on screen. */
    val cornice: FloatArray?,
    /** The brass name plate on the cornice (a quad). */
    val plaque: FloatArray?,
    val comics: List<ComicDraw>,
    val labels: List<LabelDraw>,
    /** How much everything but the comic in hand is darkened, 0..1. */
    val dim: Float,
    /** Everything but the comic in hand fades into the colour of the reading table, 0..1. */
    val fade: Float,
    /** Comics in the collection, for the name plate. */
    val count: Int,
    /** Seconds since the shelf lights were switched on. */
    val lightsTime: Float,
)

/**
 * A wall of wooden shelves with the comics standing on them face out, leaning against the wall.
 * Scrolls vertically; a tapped comic comes off the shelf into the reader's hands, where it can be
 * turned around with a finger. Not thread-safe: lives on the render thread.
 */
class ShelfController(private val density: Float) {

    interface Listener {
        /** The comic in hand changed (null: back on the shelf). */
        fun onHeldChanged(id: String?)

        /** The comic in hand was brought before the eyes to be read: the reader takes over. */
        fun onPresented(id: String) = Unit
    }

    var listener: Listener? = null

    private var items: List<ShelfItem> = emptyList()
    private var sized = false
    private var viewWidth = 1f
    private var viewHeight = 1f

    var columns = 2
        private set
    private var distance = 1.7f

    // Camera scroll: the height the screen is centred on (read from the UI thread to be saved).
    @Volatile
    private var cameraY = 0f
    private var pendingScroll: Float? = null
    private var scrollVelocity = 0f
    private var dragging = false

    // The comic in hand.
    var heldId: String? = null
        private set
    private var holding = false
    private val hold = Spring2D()
    private var yaw = 0f
    private var pitch = 0f
    private var yawVelocity = 0f
    private var rotating = false

    /** Where on screen (0..1) the comic in hand is shown, so the UI card does not cover it. */
    private var handAnchorX = 0.5f
    private var handAnchorY = 0.42f
    private var handFill = 0.6f

    val isHolding get() = heldId != null

    // Shelf lights: they switch on one after another when the library opens.
    private var lightsTime = LIGHTS_DONE

    fun switchLightsOn() {
        lightsTime = 0f
    }

    /** How far the shelves are scrolled; set it to come back to the same place later. */
    var scroll: Float
        get() = cameraY
        set(value) {
            // Kept until the view size and the comics are known, the limits depend on both.
            pendingScroll = value
            applyPendingScroll()
        }

    fun setItems(newItems: List<ShelfItem>) {
        items = newItems
        if (heldId != null && items.none { it.id == heldId }) {
            heldId = null
            holding = false
            hold.snapTo(0f, 0f)
        }
        clampScroll(hard = true)
        applyPendingScroll()
    }

    private fun applyPendingScroll() {
        val value = pendingScroll ?: return
        if (items.isEmpty() || !sized) return
        pendingScroll = null
        cameraY = value
        scrollVelocity = 0f
        clampScroll(hard = true)
    }

    fun setViewSize(width: Int, height: Int) {
        sized = true
        viewWidth = max(width, 1).toFloat()
        viewHeight = max(height, 1).toFloat()
        val aspect = viewWidth / viewHeight
        val tanHalf = tan(FOV / 2f)
        // At least ~1.6 shelves visible; as many columns as fit across, never fewer than two.
        val minDistance = 1.6f * ROW_HEIGHT / (2f * tanHalf)
        val visibleWidth = 2f * minDistance * tanHalf * aspect
        columns = floor((visibleWidth - 0.06f) / SLOT_WIDTH).toInt().coerceIn(2, 8)
        val shelfWidth = columns * SLOT_WIDTH + 0.04f
        distance = max(minDistance, shelfWidth / 2f / (tanHalf * aspect)) + PLANK_DEPTH
        if (aspect > 1f) {
            handAnchorX = 0.36f
            handAnchorY = 0.5f
            handFill = 0.66f
        } else {
            handAnchorX = 0.5f
            handAnchorY = 0.38f
            handFill = 0.52f
        }
        clampScroll(hard = true)
        applyPendingScroll()
    }

    // region Geometry

    private val visibleHalfHeight get() = (distance - PLANK_DEPTH * 0.5f) * tan(FOV / 2f)
    private val visibleHalfWidth get() = visibleHalfHeight * viewWidth / viewHeight

    private val rows get() = max(1, ceil(items.size / columns.toFloat()).toInt())

    private fun rowBase(row: Int) = -row * ROW_HEIGHT

    private val corniceBottom get() = ROW_HEIGHT - PLANK_THICKNESS
    private val scrollTop get() = corniceBottom + CORNICE_HEIGHT + 0.10f - visibleHalfHeight
    private val scrollBottom get() = min(scrollTop, rowBase(rows - 1) - 0.08f + visibleHalfHeight)

    private fun sizeOf(item: ShelfItem): Vec3 {
        val height = COMIC_HEIGHT
        val width = height * item.aspect.coerceIn(0.5f, 1.0f)
        return Vec3(width, height, ComicShape.thickness(item.pageCount))
    }


    private fun slotX(column: Int) = (column - (columns - 1) / 2f) * SLOT_WIDTH

    /** A comic standing on its shelf, bottom edge on the plank, top leaning against the wall. */
    private fun shelfPose(index: Int, size: Vec3): Pair<Vec3, Quat> {
        val row = index / columns
        val column = index % columns
        val angle = -LEAN
        val rotation = Quat.axisAngle(Vec3(1f, 0f, 0f), angle)
        // Bottom back edge of the comic, in its own coordinates and then rotated.
        val corner = rotation.rotate(Vec3(0f, -size.y / 2f, -size.z / 2f))
        val rest = Vec3(slotX(column), rowBase(row), BOTTOM_Z)
        return (rest - corner) to rotation
    }

    private fun eye() = Vec3(0f, cameraY + 0.06f, distance)
    private fun target() = Vec3(0f, cameraY - 0.03f, 0f)

    private fun viewMatrix() = Mat4.lookAt(eye(), target(), Vec3(0f, 1f, 0f))
    // Nothing comes closer than the comic in hand; a far-off near plane keeps depth precise.
    private fun projection() = Mat4.perspective(FOV, viewWidth / viewHeight, 0.2f, 6f)

    /** Point straight in front of the camera at [depth], through the screen point (sx, sy) in 0..1. */
    private fun pointInFront(sx: Float, sy: Float, depth: Float): Vec3 {
        val inverse = Mat4.invert(Mat4.multiply(projection(), viewMatrix())) ?: return target()
        val near = Mat4.transformPoint(inverse, Vec3(sx * 2f - 1f, 1f - sy * 2f, -1f))
        val far = Mat4.transformPoint(inverse, Vec3(sx * 2f - 1f, 1f - sy * 2f, 1f))
        val direction = (far - near).normalized()
        val eye = eye()
        val forward = (target() - eye).normalized()
        // Distance along the ray so that the point is [depth] in front of the eye.
        return eye + direction * (depth / direction.dot(forward))
    }

    private fun handPose(size: Vec3): Pair<Vec3, Quat> {
        // Being presented: it comes to the middle, as large as the reader will start showing it.
        val p = presentEase()
        val anchorX = handAnchorX + (0.5f - handAnchorX) * p
        val anchorY = handAnchorY + (0.5f - handAnchorY) * p
        val fill = handFill + (presentFill - handFill) * p
        // Presented, the front cover itself (not the middle of the comic) is at that size.
        val depth = size.y / (2f * tan(FOV / 2f) * fill) + size.z * 0.5f * p
        val position = pointInFront(anchorX, anchorY, depth)
        // Face the eye (the camera looks slightly down), then the reader's own turning. Presented,
        // it is square to the camera, as flat on the screen as the page in the reader.
        val square = -atan2(eye().y - target().y, distance)
        val face = Quat.axisAngle(Vec3(1f, 0f, 0f), 0.05f + (square - 0.05f) * p)
        val turn = yaw + (presentYaw - yaw) * p
        val rotation = Quat.axisAngle(Vec3(0f, 1f, 0f), turn) * Quat.axisAngle(Vec3(1f, 0f, 0f), pitch * (1f - p)) * face
        return position to rotation
    }

    // Presenting the comic in hand for reading.
    private var presentTime = -1f
    private var presentFill = 0.5f
    private var presentYaw = 0f

    private fun presentEase(): Float {
        if (presentTime < 0f) return 0f
        val t = (presentTime / PRESENT_DURATION).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Brings the comic in hand to the middle of the screen, front up, at the size the reader
     * starts at, while the room fades into the reading table. [fill] is that size as a part of
     * the screen's height. Returns false if nothing is in hand.
     */
    fun present(fill: Float): Boolean {
        val id = heldId ?: return false
        if (!holding || presentTime >= 0f || items.none { it.id == id }) return false
        presentFill = fill
        presentTime = 0f
        rotating = false
        yawVelocity = 0f
        // Front up the shortest way round.
        presentYaw = round(yaw / (2f * PI.toFloat())) * 2f * PI.toFloat()
        return true
    }

    /**
     * Height of the comic [id] as the reader first shows it, held up before it falls onto the
     * table, as a part of the screen's height. [spread] tells whether the reader will show two
     * pages side by side.
     */
    fun readerFill(id: String, spread: Boolean): Float {
        val item = items.firstOrNull { it.id == id } ?: return 0.6f
        val aspect = item.aspect.coerceIn(0.5f, 1f)
        return PageTurnController.handRect(viewWidth, viewHeight, density, aspect, spread).height / viewHeight
    }

    private fun easedHold(): Float {
        val t = hold.x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun poseOf(index: Int, item: ShelfItem, size: Vec3): Pair<Vec3, Quat> {
        val shelf = shelfPose(index, size)
        if (item.id != heldId) return shelf
        val hand = handPose(size)
        val t = easedHold()
        // Out towards the reader first, so it does not slide through the shelf.
        val lift = Vec3(0f, 0.02f, 0.12f) * sin(PI.toFloat() * t)
        return (Vec3.lerp(shelf.first, hand.first, t) + lift) to Quat.slerp(shelf.second, hand.second, t)
    }

    // endregion

    // region Frame

    fun frame(): ShelfFrame {
        val viewProj = Mat4.multiply(projection(), viewMatrix())
        val halfH = visibleHalfHeight
        val halfW = visibleHalfWidth

        val wall = Mat4.compose(Vec3(0f, cameraY, 0f), Quat.IDENTITY, Vec3(halfW * 2.6f, halfH * 2.6f, 1f))

        // Every shelf on screen, including empty ones below the collection.
        val topRow = max(0, floor(-(cameraY + halfH * 1.3f) / ROW_HEIGHT).toInt())
        val bottomRow = ceil(-(cameraY - halfH * 1.3f) / ROW_HEIGHT).toInt() + 1
        val planks = (topRow..bottomRow).map { row ->
            Mat4.compose(
                Vec3(0f, rowBase(row) - PLANK_THICKNESS / 2f, PLANK_DEPTH / 2f),
                Quat.IDENTITY,
                Vec3(halfW * 2.6f, PLANK_THICKNESS, PLANK_DEPTH),
            )
        }

        // The top board with the name plate, above the first shelf.
        val cornice = if (topRow == 0) {
            Mat4.compose(
                Vec3(0f, corniceBottom + CORNICE_HEIGHT / 2f, (PLANK_DEPTH + CORNICE_OVERHANG) / 2f),
                Quat.IDENTITY,
                Vec3(halfW * 2.6f, CORNICE_HEIGHT, PLANK_DEPTH + CORNICE_OVERHANG),
            )
        } else {
            null
        }
        val plaque = cornice?.let {
            Mat4.compose(
                Vec3(0f, corniceBottom + CORNICE_HEIGHT / 2f, PLANK_DEPTH + CORNICE_OVERHANG + 0.0008f),
                Quat.IDENTITY,
                Vec3(PLAQUE_WIDTH, PLAQUE_HEIGHT, 1f),
            )
        }

        val comics = ArrayList<ComicDraw>()
        val labels = ArrayList<LabelDraw>()
        var held: ComicDraw? = null
        items.forEachIndexed { index, item ->
            val row = index / columns
            val isHeld = item.id == heldId
            if (!isHeld && (row < topRow - 1 || row > bottomRow)) return@forEachIndexed
            val size = sizeOf(item)
            val (position, rotation) = poseOf(index, item, size)
            val model = Mat4.compose(position, rotation, size)
            val ribbon = if (item.inProgress) {
                // A satin bookmark sticking out of the top of the block.
                val local = Mat4.compose(
                    Vec3(0.28f, 0.5f + RIBBON_LENGTH / size.y / 2f - 0.02f, 0f),
                    Quat.IDENTITY,
                    Vec3(RIBBON_WIDTH / size.x, RIBBON_LENGTH / size.y, 0.25f),
                )
                Mat4.multiply(model, local)
            } else {
                null
            }
            val draw = ComicDraw(item.id, model, size, item.pageCount, ComicShape.binding(item.pageCount), ribbon, isHeld)
            if (isHeld) held = draw else comics += draw
            labels += LabelDraw(
                item.id,
                Mat4.compose(
                    Vec3(slotX(index % columns), rowBase(row) - PLANK_THICKNESS / 2f, PLANK_DEPTH + 0.0006f),
                    Quat.IDENTITY,
                    // With its brass holder around it.
                    Vec3(LABEL_WIDTH + 0.008f, LABEL_HEIGHT + 0.006f, 1f),
                ),
            )
        }
        held?.let { comics += it }

        // Light from above, to the left and in front; its shadow map covers what is on screen.
        val light = Vec3(-0.30f, 0.52f, 0.80f).normalized()
        val center = Vec3(0f, cameraY, 0.1f)
        val lightView = Mat4.lookAt(center + light * 2.5f, center, Vec3(0f, 0f, -1f))
        val extent = max(halfW, halfH) * 1.35f
        val lightProj = Mat4.orthographic(-extent, extent, -extent, extent, 0.5f, 5f)

        return ShelfFrame(
            viewProj = viewProj,
            eye = eye(),
            lightViewProj = Mat4.multiply(lightProj, lightView),
            light = light,
            wall = wall,
            planks = planks,
            cornice = cornice,
            plaque = plaque,
            comics = comics,
            labels = labels,
            dim = if (heldId != null) 0.5f * easedHold() else 0f,
            fade = presentEase(),
            count = items.size,
            lightsTime = lightsTime,
        )
    }

    // endregion

    // region Input

    /** Finger moved by (dx, dy) pixels: scrolls the shelves or turns the comic in hand. */
    fun drag(dx: Float, dy: Float) {
        if (presentTime >= 0f) return
        if (heldId != null && holding) {
            rotating = true
            yaw += dx * ROTATE_PER_PIXEL / density
            pitch = (pitch + dy * ROTATE_PER_PIXEL / density).coerceIn(-MAX_PITCH, MAX_PITCH)
            yawVelocity = 0f
            return
        }
        dragging = true
        scrollVelocity = 0f
        val metersPerPixel = 2f * visibleHalfHeight / viewHeight
        var delta = dy * metersPerPixel
        // Rubber band past the ends.
        if (cameraY > scrollTop || cameraY < scrollBottom) delta *= 0.35f
        cameraY += delta
    }

    fun release(velocityX: Float, velocityY: Float) {
        if (rotating) {
            rotating = false
            yawVelocity = velocityX * ROTATE_PER_PIXEL / density
            return
        }
        dragging = false
        scrollVelocity = velocityY * 2f * visibleHalfHeight / viewHeight
    }

    /** The comic in hand (not one on its way back to the shelf). */
    val inHandId get() = if (holding) heldId else null

    /** A tap at (x, y) pixels. Picks a comic off the shelf, or puts the one in hand back. */
    fun tap(x: Float, y: Float): String? {
        if (presentTime >= 0f) return null
        val hit = pick(x, y)
        val held = heldId
        if (held != null) {
            if (holding) {
                if (hit != held) putBack()
            } else if (hit == held) {
                // Caught on its way back to the shelf.
                take(held)
            }
            return hit
        }
        if (hit != null) take(hit)
        return hit
    }

    fun take(id: String) {
        if (items.none { it.id == id }) return
        if (heldId != id) {
            // Arrives turned a little, showing its spine, and turns to face the reader.
            yaw = -0.6f
            pitch = 0f
        }
        heldId = id
        holding = true
        yawVelocity = 0f
        if (hold.x <= 0.001f) hold.snapTo(0f, 0f)
        listener?.onHeldChanged(id)
    }

    fun putBack() {
        // Once on its way to the reader it is not put back.
        if (heldId == null || presentTime >= 0f) return
        holding = false
        rotating = false
        listener?.onHeldChanged(null)
    }

    /** The comic under the screen point (x, y), the one in hand first. */
    fun pick(x: Float, y: Float): String? {
        val inverse = Mat4.invert(Mat4.multiply(projection(), viewMatrix())) ?: return null
        val nx = x / viewWidth * 2f - 1f
        val ny = 1f - y / viewHeight * 2f
        val near = Mat4.transformPoint(inverse, Vec3(nx, ny, -1f))
        val far = Mat4.transformPoint(inverse, Vec3(nx, ny, 1f))
        var best: String? = null
        var bestT = Float.MAX_VALUE
        items.forEachIndexed { index, item ->
            val size = sizeOf(item)
            val (position, rotation) = poseOf(index, item, size)
            val model = Mat4.compose(position, rotation, size)
            val local = Mat4.invert(model) ?: return@forEachIndexed
            val a = Mat4.transformPoint(local, near)
            val b = Mat4.transformPoint(local, far)
            // Thin comics are easier to hit with a slightly thicker box.
            val t = rayBox(a, b - a, Vec3(0.5f, 0.5f, 0.5f + PICK_SLACK / size.z)) ?: return@forEachIndexed
            val priority = if (item.id == heldId) -1f else t
            if (priority < bestT) {
                bestT = priority
                best = item.id
            }
        }
        return best
    }

    /** Slab test of the segment origin + t * direction (t in 0..1) against a centred box. */
    private fun rayBox(origin: Vec3, direction: Vec3, half: Vec3): Float? {
        var tMin = 0f
        var tMax = 1f
        val o = floatArrayOf(origin.x, origin.y, origin.z)
        val d = floatArrayOf(direction.x, direction.y, direction.z)
        val h = floatArrayOf(half.x, half.y, half.z)
        for (i in 0..2) {
            if (abs(d[i]) < 1e-9f) {
                if (o[i] < -h[i] || o[i] > h[i]) return null
            } else {
                var t1 = (-h[i] - o[i]) / d[i]
                var t2 = (h[i] - o[i]) / d[i]
                if (t1 > t2) t1 = t2.also { t2 = t1 }
                tMin = max(tMin, t1)
                tMax = min(tMax, t2)
                if (tMin > tMax) return null
            }
        }
        return tMin
    }

    // endregion

    /** Advances animations. Returns true while another frame is needed. */
    fun step(dt: Float): Boolean {
        var animating = false

        if (presentTime >= 0f) {
            val before = presentTime
            presentTime += dt
            if (before < PRESENT_DURATION + PRESENT_HOLD && presentTime >= PRESENT_DURATION + PRESENT_HOLD) {
                heldId?.let { listener?.onPresented(it) }
            }
            if (presentTime < PRESENT_DURATION + PRESENT_HOLD) animating = true
        }

        if (lightsTime < LIGHTS_DONE) {
            lightsTime = min(LIGHTS_DONE, lightsTime + dt)
            animating = true
        }

        val target = if (holding) 1f else 0f
        hold.step(target, 0f, HOLD_OMEGA, dt)
        if (heldId != null) {
            when {
                holding -> if (!hold.isAt(1f, 0f, 0.0005f, 0.005f)) animating = true else hold.snapTo(1f, 0f)
                hold.x < 0.002f && abs(hold.vx) < 0.01f -> {
                    heldId = null
                    hold.snapTo(0f, 0f)
                }
                else -> animating = true
            }
        }

        if (heldId != null && !rotating) {
            // Coast, then settle facing the front or the back; the tilt eases back to level.
            yaw += yawVelocity * dt
            yawVelocity *= exp(-ROTATE_FRICTION * dt)
            if (abs(yawVelocity) < 0.6f) {
                val rest = round(yaw / PI.toFloat()) * PI.toFloat()
                yaw += (rest - yaw) * (1f - exp(-5f * dt))
                if (abs(rest - yaw) < 0.0005f) yaw = rest
            }
            pitch *= exp(-4f * dt)
            if (abs(yawVelocity) > 0.01f || abs(pitch) > 0.0005f) animating = true
            if (abs(round(yaw / PI.toFloat()) * PI.toFloat() - yaw) > 0.0005f) animating = true
        }

        if (!dragging) {
            if (scrollVelocity != 0f) {
                cameraY += scrollVelocity * dt
                scrollVelocity *= exp(-SCROLL_FRICTION * dt)
                if (cameraY > scrollTop || cameraY < scrollBottom) scrollVelocity *= exp(-12f * dt)
                if (abs(scrollVelocity) < 0.01f) scrollVelocity = 0f
                animating = true
            }
            if (cameraY > scrollTop || cameraY < scrollBottom) {
                val edge = cameraY.coerceIn(scrollBottom, scrollTop)
                cameraY += (edge - cameraY) * (1f - exp(-10f * dt))
                if (abs(edge - cameraY) < 0.0003f) cameraY = edge
                animating = true
            }
        }
        return animating
    }

    private fun clampScroll(hard: Boolean) {
        if (hard) cameraY = cameraY.coerceIn(scrollBottom, scrollTop)
    }

    companion object {
        const val FOV = 0.63f
        const val COMIC_HEIGHT = 0.258f
        const val SLOT_WIDTH = 0.23f
        const val ROW_HEIGHT = 0.40f
        const val PLANK_THICKNESS = 0.034f
        const val PLANK_DEPTH = 0.15f
        const val BOTTOM_Z = 0.065f
        const val LEAN = 0.19f
        const val CORNICE_HEIGHT = 0.075f
        const val CORNICE_OVERHANG = 0.012f
        const val PLAQUE_WIDTH = 0.26f
        const val PLAQUE_HEIGHT = 0.042f
        const val LIGHTS_DONE = 3f
        private const val PRESENT_DURATION = 0.42f
        private const val PRESENT_HOLD = 0.04f
        const val LABEL_WIDTH = 0.16f
        const val LABEL_HEIGHT = 0.025f
        const val RIBBON_WIDTH = 0.008f
        const val RIBBON_LENGTH = 0.05f
        private const val HOLD_OMEGA = 9f
        private const val ROTATE_PER_PIXEL = 0.0075f
        private const val MAX_PITCH = 0.8f
        private const val ROTATE_FRICTION = 2.2f
        private const val SCROLL_FRICTION = 3.2f
        private const val PICK_SLACK = 0.004f
    }
}
