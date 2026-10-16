package com.readcomic.shelf

import com.readcomic.curl.Spring2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/**
 * How large the comics are on the shelves, which stay as they are: [scale] times the usual
 * size of a comic.
 */
enum class ShelfSize(val label: String, val scale: Float) {
    SMALL("Меньше", 1f),
    NORMAL("Средние", 1.12f),
    LARGE("Крупные", 1.2f),
}

/** A comic on the shelf. */
class ShelfItem(
    val id: String,
    /** Cover width / height. */
    val aspect: Float,
    val pageCount: Int,
    /** Being read: a bookmark ribbon sticks out of it. */
    val inProgress: Boolean,
    /** What the paper label under it says. */
    val label: String = id,
    /**
     * The series it stands with: neighbours with the same series get a brass sign with its name
     * on the wall above them. Null for a comic on its own.
     */
    val series: String? = null,
    /** Its back cover is drawn instead of taken from the comic (the reader asked for it). */
    val drawnBack: Boolean = false,
)

class ComicDraw(
    val id: String,
    val model: FloatArray,
    /** Width, height and thickness in meters. */
    val size: Vec3,
    /** Pages, for the lines of the sheets on the page edges. */
    val pages: Int,
    val binding: Binding,
    /** The bookmark of a comic being read: its triangles in the world, see [Ribbon.mesh]. */
    val ribbon: FloatArray?,
    val held: Boolean,
    /** Not among what is being searched for: it stays in the shade. */
    val faded: Boolean = false,
    /** A glint running over the cover, 0..1; negative for none. */
    val shine: Float = -1f,
    /** Its back cover is drawn instead of taken from the comic. */
    val drawnBack: Boolean = false,
)

/** The paper label saying [text], under a comic. */
class LabelDraw(val text: String, val model: FloatArray)

/** A brass sign with the name of a series, on the wall above [span] comics of it. */
class SignDraw(val text: String, val span: Int, val model: FloatArray)

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
    val signs: List<SignDraw>,
    /** How much everything but the comic in hand is darkened, 0..1. */
    val dim: Float,
    /** How much the comics that do not match a search are darkened, 0..1. */
    val searchDim: Float,
    /** Comics in the collection, for the name plate. */
    val count: Int,
    /** Seconds since the shelf lights were switched on. */
    val lightsTime: Float,
    /** From one shelf to the next, metres. */
    val rowHeight: Float,
    /** The box of new comics, when there is one. */
    val unbox: UnboxDraw? = null,
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
    }

    var listener: Listener? = null

    private var items: List<ShelfItem> = emptyList()

    /** The box of new comics and their unwrapping. */
    val unboxing = Unboxing(object : Unboxing.Host {
        override val aspect get() = viewWidth / viewHeight
        override fun pointInFront(sx: Float, sy: Float, depth: Float) = this@ShelfController.pointInFront(sx, sy, depth)
        override fun eyeRay(x: Float, y: Float) = this@ShelfController.eyeRay(x, y)
        override fun handPose(size: Vec3) = this@ShelfController.handPose(size, ribbon = false, yaw = 0f, pitch = 0f)
        override fun sizeOf(item: ShelfItem) = this@ShelfController.sizeOf(item)
        override val shelving get() = placing != null || (heldId != null && heldId == quietHeld)
    })

    /** Whether the box is up in front of the reader and takes the touches (read from the UI thread). */
    @Volatile
    var boxTakesTouches = false
        private set
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

    /** How much of the screen's height the comic in hand fills, when there is room for it. */
    private var handFill = 0.6f

    /**
     * Room the UI takes from the edges of the view (left, top, right, bottom, pixels) while a
     * comic is in hand: its card, the status bar. The comic in hand keeps out of it; [shownRoom]
     * follows [room] smoothly, so it moves aside instead of jumping.
     */
    private val room = FloatArray(4)
    private val shownRoom = FloatArray(4)

    /** The UI takes [left], [top], [right] and [bottom] pixels from the edges of the view. */
    fun setHandRoom(left: Float, top: Float, right: Float, bottom: Float) {
        room[0] = max(left, 0f)
        room[1] = max(top, 0f)
        room[2] = max(right, 0f)
        room[3] = max(bottom, 0f)
        // Nothing in hand: nothing to see moving.
        if (heldId == null) room.copyInto(shownRoom)
    }

    val isHolding get() = heldId != null

    // Shelf lights: they switch on one after another when the library opens.
    private var lightsTime = LIGHTS_DONE

    fun switchLightsOn() {
        lightsTime = 0f
    }

    /**
     * How far the shelves are scrolled, in shelves (so it holds whatever the size of the
     * comics); set it to come back to the same place later.
     */
    var scroll: Float
        get() = cameraY / rowHeight
        set(value) {
            // Kept until the view size and the comics are known, the limits depend on both.
            pendingScroll = value
            applyPendingScroll()
        }

    fun setItems(newItems: List<ShelfItem>) {
        val before = items
        // Where the comics on screen stand now, so those that change places can walk there.
        val oldIndex = HashMap<String, Int>(before.size * 2)
        before.forEachIndexed { index, item -> oldIndex[item.id] = index }
        val visible = visibleRows()
        val from = HashMap<String, Vec3>()
        if (sized && before.isNotEmpty()) {
            before.forEachIndexed { index, item ->
                if (index / columns in visible) from[item.id] = restingPose(index, item, sizeOf(item)).first
            }
        }
        items = newItems
        if (sized && before.isNotEmpty()) {
            newItems.forEachIndexed { index, item ->
                val old = oldIndex[item.id] ?: return@forEachIndexed
                val start = from[item.id]
                if (old != index && start != null && (old / columns in visible || index / columns in visible)) {
                    moves[item.id] = SlotMove(start, old / columns != index / columns, index > old)
                } else if (old != index) {
                    moves.remove(item.id)
                }
            }
            // Comics just out of the box gleam once in their places (the one in hand has gleamed already).
            val boxed = unboxing.boxedIds
            val inHand = unboxing.inHandId
            newItems.forEach { item ->
                if (item.id !in oldIndex && item.id in boxed && item.id != inHand) appearing[item.id] = -0.1f * appearing.size
            }
        }
        moves.keys.retainAll(newItems.map { it.id }.toSet())
        // The comic unwrapped in hand is the shelves' now: it goes to its place from the hands.
        unboxing.inHandId?.let { id ->
            if (newItems.any { it.id == id } && unboxing.handOver(id)) adopt(id)
        }
        val reading = newItems.filter { it.inProgress }.map { it.id }.toSet()
        ribbons.keys.retainAll(reading)
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
        cameraY = value * rowHeight
        scrollVelocity = 0f
        clampScroll(hard = true)
    }

    /** The comics in the box of new ones, the next to come out first. */
    fun setBox(boxed: List<ShelfItem>) {
        // Coming back to the library (the lights are on already) the box is simply there; it
        // drops in when the room is entered, or when new comics come.
        unboxing.setContents(boxed, quietly = !boxSeen && lightsTime >= LIGHTS_DONE)
        boxSeen = true
        boxTakesTouches = unboxing.active
    }

    private var boxSeen = false

    // A comic changing places: from where it stood, [t] 0..1 of the way; [forward] to a later place.
    private class SlotMove(val from: Vec3, val acrossRows: Boolean, val forward: Boolean) {
        var t = 0f
    }

    private val moves = HashMap<String, SlotMove>()

    // Comics just put on the shelves, seconds into their glint (negative: still waiting for it).
    private val appearing = HashMap<String, Float>()

    // A comic just unwrapped: the shelves scroll to its place, then it goes there.
    private var placing: String? = null
    private var placingTime = 0f

    // The held comic that is only passing through the hands: the room does not darken for it.
    private var quietHeld: String? = null

    private fun adopt(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        if (heldId != null && heldId != id) {
            hold.snapTo(0f, 0f)
            heldId = null
        }
        heldId = id
        yaw = 0f
        pitch = 0f
        yawVelocity = 0f
        rotating = false
        hold.snapTo(1f, 0f)
        // Held without the card of a comic in hand: it is on its way to the shelf.
        holding = true
        quietHeld = id
        placing = id
        placingTime = 0f
        focusId = null
        scrollVelocity = 0f
    }

    private fun visibleRows(): IntRange {
        if (!sized) return IntRange.EMPTY
        val halfH = visibleHalfHeight
        val topRow = max(0, floor(-(cameraY + halfH * 1.3f) / rowHeight).toInt())
        val bottomRow = ceil(-(cameraY - halfH * 1.3f) / rowHeight).toInt() + 1
        return (topRow - 1)..bottomRow
    }

    /** How large the comics are on the shelves; the shelves follow them, the same room above. */
    var size = ShelfSize.NORMAL
        set(value) {
            if (value == field) return
            val before = rowHeight
            field = value
            // The same shelf stays on screen; the bookmarks start again, at rest.
            cameraY *= rowHeight / before
            ribbons.clear()
            clampScroll(hard = true)
        }

    /**
     * From one shelf to the next: the comics standing on it and above them just the room the
     * bookmark curling out of a comic and a series sign need.
     */
    val rowHeight: Float
        get() = COMIC_HEIGHT * size.scale * cos(LEAN) + HEADROOM + PLANK_THICKNESS

    private val slotWidth get() = SLOT_WIDTH

    fun setViewSize(width: Int, height: Int) {
        sized = true
        viewWidth = max(width, 1).toFloat()
        viewHeight = max(height, 1).toFloat()
        val aspect = viewWidth / viewHeight
        val tanHalf = tan(FOV / 2f)
        // At least that much of the bookcase in height; as many columns as fit across, never
        // fewer than two.
        val minDistance = MIN_VISIBLE_HEIGHT / (2f * tanHalf)
        val visibleWidth = 2f * minDistance * tanHalf * aspect
        columns = floor((visibleWidth - 0.06f) / slotWidth).toInt().coerceIn(2, 8)
        val shelfWidth = columns * slotWidth + 0.04f
        distance = max(minDistance, shelfWidth / 2f / (tanHalf * aspect)) + PLANK_DEPTH
        handFill = if (aspect > 1f) 0.66f else 0.52f
        // Turned round: everything is laid out anew, no need to slide.
        room.copyInto(shownRoom)
        clampScroll(hard = true)
        applyPendingScroll()
    }

    // region Geometry

    private val visibleHalfHeight get() = (distance - PLANK_DEPTH * 0.5f) * tan(FOV / 2f)
    private val visibleHalfWidth get() = visibleHalfHeight * viewWidth / viewHeight

    private val rows get() = max(1, ceil(items.size / columns.toFloat()).toInt())

    private fun rowBase(row: Int) = -row * rowHeight

    private val corniceBottom get() = rowHeight - PLANK_THICKNESS
    private val scrollTop get() = corniceBottom + CORNICE_HEIGHT + 0.10f - visibleHalfHeight
    private val scrollBottom get() = min(scrollTop, rowBase(rows - 1) - 0.08f + visibleHalfHeight)

    private fun sizeOf(item: ShelfItem): Vec3 {
        var height = COMIC_HEIGHT * size.scale
        var width = height * item.aspect.coerceIn(0.5f, 1.0f)
        // A wide comic is a smaller one, not one squeezed against its neighbours.
        val room = slotWidth - MIN_GAP
        if (width > room) {
            height *= room / width
            width = room
        }
        return Vec3(width, height, ComicShape.thickness(item.pageCount))
    }


    private fun slotX(column: Int) = (column - (columns - 1) / 2f) * slotWidth

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

    private fun handPose(size: Vec3, ribbon: Boolean, yaw: Float = this.yaw, pitch: Float = this.pitch): Pair<Vec3, Quat> {
        val place = handPlace(size, ribbon)
        val depth = size.y / (2f * tan(FOV / 2f) * place.z)
        val position = pointInFront(place.x, place.y, depth)
        // Face the eye (the camera looks slightly down), then the reader's own turning.
        val face = Quat.axisAngle(Vec3(1f, 0f, 0f), 0.05f)
        val rotation = Quat.axisAngle(Vec3(0f, 1f, 0f), yaw) * Quat.axisAngle(Vec3(1f, 0f, 0f), pitch) * face
        return position to rotation
    }

    /**
     * Where the centre of the comic in hand is on screen (x, y in 0..1) and what part of the
     * screen's height it fills (z): in the middle of the room the UI leaves it (between the status
     * bar and its card), the bookmark of a comic being read and all, as large as set for the
     * shape of the screen or, if there is not room enough, a little smaller.
     */
    internal fun handPlace(size: Vec3, ribbon: Boolean): Vec3 {
        val left = shownRoom[0] / viewWidth
        val top = shownRoom[1] / viewHeight
        val right = 1f - shownRoom[2] / viewWidth
        val bottom = 1f - shownRoom[3] / viewHeight
        val marginY = HAND_MARGIN
        val marginX = HAND_MARGIN * viewHeight / viewWidth
        // Its width on screen for each part of the height it fills.
        val across = size.x / size.y * viewHeight / viewWidth
        // The bookmark rising out of its top, in parts of its height.
        val above = if (ribbon) RIBBON_RISE / size.y else 0f
        val fill = min(handFill, min((bottom - top - 2f * marginY) / (1f + above), (right - left - 2f * marginX) / across))
            .coerceAtLeast(MIN_HAND_FILL)
        // The comic and its bookmark together are in the middle.
        return Vec3((left + right) / 2f, (top + bottom) / 2f + above * fill / 2f, fill)
    }

    /** The line of sight through the screen point (x, y) in pixels: its near and far ends. */
    private fun eyeRay(x: Float, y: Float): Pair<Vec3, Vec3>? {
        val inverse = Mat4.invert(Mat4.multiply(projection(), viewMatrix())) ?: return null
        val nx = x / viewWidth * 2f - 1f
        val ny = 1f - y / viewHeight * 2f
        return Mat4.transformPoint(inverse, Vec3(nx, ny, -1f)) to Mat4.transformPoint(inverse, Vec3(nx, ny, 1f))
    }

    /** A comic on its shelf, or on its way there from where it stood before. */
    private fun restingPose(index: Int, item: ShelfItem, size: Vec3): Pair<Vec3, Quat> {
        val pose = shelfPose(index, size)
        val move = moves[item.id] ?: return pose
        if (move.acrossRows) {
            // To another shelf: off the end of its own, in from the other end of the next one,
            // never across the others.
            val out = (visibleHalfWidth + slotWidth * 0.7f) * if (move.forward) 1f else -1f
            return if (move.t < 0.5f) {
                val u = move.t * 2f
                val e = u * u
                Vec3(move.from.x + (out - move.from.x) * e, move.from.y, move.from.z + 0.012f * u) to pose.second
            } else {
                val u = (move.t - 0.5f) * 2f
                val e = 1f - (1f - u) * (1f - u)
                val target = pose.first
                Vec3(-out + (target.x + out) * e, target.y, target.z + 0.012f * (1f - u)) to pose.second
            }
        }
        val e = move.t * move.t * (3f - 2f * move.t)
        // Along the shelf it slides, lifted a hair.
        return (Vec3.lerp(move.from, pose.first, e) + Vec3(0f, 0.004f, 0.015f) * sin(PI.toFloat() * e)) to pose.second
    }

    private fun easedHold(): Float {
        val t = hold.x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun poseOf(index: Int, item: ShelfItem, size: Vec3): Pair<Vec3, Quat> {
        val shelf = restingPose(index, item, size)
        if (item.id != heldId) return shelf
        val hand = handPose(size, item.inProgress)
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
        val topRow = max(0, floor(-(cameraY + halfH * 1.3f) / rowHeight).toInt())
        val bottomRow = ceil(-(cameraY - halfH * 1.3f) / rowHeight).toInt() + 1
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
        val signs = ArrayList<SignDraw>()
        var held: ComicDraw? = null
        val found = highlight
        items.forEachIndexed { index, item ->
            val row = index / columns
            val isHeld = item.id == heldId
            if (!isHeld && (row < topRow - 1 || row > bottomRow)) return@forEachIndexed
            // A series gets a sign above the first of it in every row it stands on.
            val series = item.series
            val previous = items.getOrNull(index - 1)
            if (series != null && (index % columns == 0 || previous?.series != series)) {
                var span = 1
                while (span < columns - index % columns && items.getOrNull(index + span)?.series == series) span++
                signs += SignDraw(series, span, signModel(row, index % columns, span))
            }
            val size = sizeOf(item)
            val (position, rotation) = poseOf(index, item, size)
            val model = Mat4.compose(position, rotation, size)
            val ribbon = if (item.inProgress) ribbons.getOrPut(item.id) { Ribbon() }.mesh(position, rotation, size) else null
            val faded = found != null && item.id !in found
            val shine = appearing[item.id]?.let { (it / APPEAR_SHINE).coerceAtMost(1f) } ?: -1f
            val draw = ComicDraw(item.id, model, size, item.pageCount, ComicShape.binding(item.pageCount), ribbon, isHeld, faded, shine, item.drawnBack)
            if (isHeld) held = draw else comics += draw
            labels += LabelDraw(
                item.label,
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
        val unbox = unboxing.draw()

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
            signs = signs,
            dim = max(if (heldId != null && heldId != quietHeld) 0.5f * easedHold() else 0f, unbox?.dim ?: 0f),
            searchDim = searchDim * searchDim * (3f - 2f * searchDim) * 0.72f,
            count = items.size,
            lightsTime = lightsTime,
            rowHeight = rowHeight,
            unbox = unbox,
        )
    }

    /** A brass sign on the wall above [span] comics of a row, starting at [column]. */
    private fun signModel(row: Int, column: Int, span: Int): FloatArray {
        val width = min(span * slotWidth - 0.05f, SIGN_MAX_WIDTH)
        val x = (slotX(column) + slotX(column + span - 1)) / 2f
        return Mat4.compose(
            Vec3(x, rowBase(row) + signY, 0.0012f),
            Quat.IDENTITY,
            Vec3(width, SIGN_HEIGHT, 1f),
        )
    }

    /** Height of the series signs above a shelf: just over the tops of the comics, under the next shelf. */
    private val signY: Float
        get() = min(COMIC_HEIGHT * size.scale * cos(LEAN) + SIGN_ABOVE, rowHeight - PLANK_THICKNESS - SIGN_HEIGHT / 2f - 0.008f)

    /** Width / height of the sign above [span] comics, for its engraving. */
    fun signAspect(span: Int) = min(span * slotWidth - 0.05f, SIGN_MAX_WIDTH) / SIGN_HEIGHT

    // endregion

    // region Search

    /** Comics matching a search; the others stand in the shade. Null: no search. */
    private var highlight: Set<String>? = null
    private var searchDim = 0f

    fun highlight(ids: Set<String>?) {
        if (ids != null) highlight = ids
        highlightWanted = ids != null
    }

    private var highlightWanted = false

    // A comic picked from the search: the shelves scroll to it, then it is taken off the shelf.
    private var focusId: String? = null

    /** Scrolls to the comic [id] and takes it into the hands. */
    fun focus(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        focusId = id
        dragging = false
        scrollVelocity = 0f
    }

    private fun focusY(index: Int): Float =
        (rowBase(index / columns) + COMIC_HEIGHT * 0.5f + 0.03f).coerceIn(scrollBottom, scrollTop)

    // endregion

    // region Input

    /** Finger moved by (dx, dy) pixels: scrolls the shelves or turns the comic in hand. */
    fun drag(dx: Float, dy: Float) {
        focusId = null
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

    /** Turns the comic in hand round to show its back; one showing it already stays as it is. */
    fun showBack() {
        if (heldId == null || rotating) return
        val turns = round(yaw / PI.toFloat())
        // An odd number of half turns faces the back.
        if (abs(turns % 2f) == 1f) return
        // Spun just hard enough to coast to the back, where it settles as after a flick.
        val target = (turns + 1f) * PI.toFloat()
        yawVelocity = (target - yaw) * ROTATE_FRICTION + SETTLE_SPEED
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

    // The box up in front of the reader takes the finger: to cut its tape, to tear the paper.

    fun boxDown(x: Float, y: Float) = unboxing.down(x, y)

    fun boxMove(x: Float, y: Float) = unboxing.move(x, y)

    fun boxUp() = unboxing.up()

    fun boxTap(x: Float, y: Float) = unboxing.tap(x, y)

    /** Brings the box of new comics up in front of the reader. */
    fun openBox() {
        if (heldId != null && holding) putBack()
        unboxing.bringUp()
        boxTakesTouches = unboxing.active
    }

    /** Puts the box back down (the comic in hand goes back into it). */
    fun putAwayBox() = unboxing.putAway()

    /** The comic in hand (not one on its way back to the shelf). */
    val inHandId get() = if (holding && placing == null) heldId else null

    /** A tap at (x, y) pixels. Picks a comic off the shelf, or puts the one in hand back. */
    fun tap(x: Float, y: Float): String? {
        if (heldId == null && unboxing.tapPeek(x, y)) {
            boxTakesTouches = unboxing.active
            return null
        }
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
        quietHeld = null
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
        if (heldId == null) return
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
        // Only what can be on screen: the comic in hand and the shelves in view.
        val rows = visibleRows()
        items.forEachIndexed { index, item ->
            if (item.id != heldId && index / columns !in rows) return@forEachIndexed
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

    // The bookmarks of the comics being read, which swing when their comic moves.
    private val ribbons = HashMap<String, Ribbon>()

    /** Moves the bookmarks of the comics on screen (or in the hands) along with them. */
    private fun stepRibbons(dt: Float): Boolean {
        val halfH = visibleHalfHeight
        val topRow = max(0, floor(-(cameraY + halfH * 1.3f) / rowHeight).toInt())
        val bottomRow = ceil(-(cameraY - halfH * 1.3f) / rowHeight).toInt() + 1
        var moving = false
        items.forEachIndexed { index, item ->
            if (!item.inProgress) return@forEachIndexed
            val row = index / columns
            if (item.id != heldId && (row < topRow - 1 || row > bottomRow)) return@forEachIndexed
            val size = sizeOf(item)
            val (position, rotation) = poseOf(index, item, size)
            if (ribbons.getOrPut(item.id) { Ribbon() }.step(position, rotation, size, dt)) moving = true
        }
        return moving
    }

    /** Advances animations. Returns true while another frame is needed. */
    fun step(dt: Float): Boolean {
        var animating = false

        if (unboxing.step(dt)) animating = true
        boxTakesTouches = unboxing.active

        if (moves.isNotEmpty()) {
            val iterator = moves.values.iterator()
            while (iterator.hasNext()) {
                val move = iterator.next()
                move.t += dt / if (move.acrossRows) 0.7f else 0.5f
                if (move.t >= 1f) iterator.remove()
            }
            animating = true
        }
        if (appearing.isNotEmpty()) {
            for (key in appearing.keys.toList()) {
                val t = appearing.getValue(key) + dt
                if (t >= APPEAR_SHINE) appearing.remove(key) else appearing[key] = t
            }
            animating = true
        }

        placing?.let { id ->
            val index = items.indexOfFirst { it.id == id }
            if (index < 0 || heldId != id) {
                placing = null
                return@let
            }
            animating = true
            placingTime += dt
            val target = focusY(index)
            cameraY += (target - cameraY) * (1f - exp(-5f * dt))
            // Near enough: it leaves the hands for its place while the shelves settle.
            if (holding && (abs(target - cameraY) < 0.08f || placingTime > 1.4f)) holding = false
            if (!holding && abs(target - cameraY) < 0.002f) {
                cameraY = target
                placing = null
            }
        }

        val dimTarget = if (highlightWanted) 1f else 0f
        if (searchDim != dimTarget) {
            searchDim = if (dimTarget > searchDim) min(1f, searchDim + dt / SEARCH_FADE) else max(0f, searchDim - dt / SEARCH_FADE)
            if (searchDim == 0f) highlight = null
            animating = true
        }

        focusId?.let { id ->
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) {
                focusId = null
                return@let
            }
            animating = true
            // Whatever is in hand goes back first.
            if (heldId != null && heldId != id) {
                if (holding) putBack()
                return@let
            }
            val target = focusY(index)
            cameraY += (target - cameraY) * (1f - exp(-FOCUS_RATE * dt))
            if (abs(target - cameraY) < 0.004f) {
                cameraY = target
                focusId = null
                take(id)
            }
        }

        if (lightsTime < LIGHTS_DONE) {
            lightsTime = min(LIGHTS_DONE, lightsTime + dt)
            animating = true
        }

        // The card of the comic in hand came or changed: the comic makes way for it.
        for (k in room.indices) {
            if (shownRoom[k] == room[k]) continue
            shownRoom[k] += (room[k] - shownRoom[k]) * (1f - exp(-ROOM_RATE * dt))
            if (abs(room[k] - shownRoom[k]) < 0.5f) shownRoom[k] = room[k]
            animating = true
        }

        val target = if (holding) 1f else 0f
        hold.step(target, 0f, HOLD_OMEGA, dt)
        if (heldId != null) {
            when {
                holding -> if (!hold.isAt(1f, 0f, 0.0005f, 0.005f)) animating = true else hold.snapTo(1f, 0f)
                hold.x < 0.002f && abs(hold.vx) < 0.01f -> {
                    heldId = null
                    quietHeld = null
                    hold.snapTo(0f, 0f)
                }
                else -> animating = true
            }
        }

        if (heldId != null && !rotating) {
            // Coast, then settle facing the front or the back; the tilt eases back to level.
            yaw += yawVelocity * dt
            yawVelocity *= exp(-ROTATE_FRICTION * dt)
            if (abs(yawVelocity) < SETTLE_SPEED) {
                val rest = round(yaw / PI.toFloat()) * PI.toFloat()
                yaw += (rest - yaw) * (1f - exp(-5f * dt))
                if (abs(rest - yaw) < 0.0005f) yaw = rest
            }
            pitch *= exp(-4f * dt)
            if (abs(yawVelocity) > 0.01f || abs(pitch) > 0.0005f) animating = true
            if (abs(round(yaw / PI.toFloat()) * PI.toFloat() - yaw) > 0.0005f) animating = true
        }

        if (!dragging && focusId == null) {
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
        if (stepRibbons(dt)) animating = true
        return animating
    }

    private fun clampScroll(hard: Boolean) {
        if (hard) cameraY = cameraY.coerceIn(scrollBottom, scrollTop)
    }

    companion object {
        const val FOV = 0.63f
        const val COMIC_HEIGHT = 0.258f
        const val SLOT_WIDTH = 0.23f

        /** The least room between two comics standing side by side. */
        private const val MIN_GAP = 0.018f

        /** Room above the comics, under the next shelf: the bookmark rises ~5 cm, the sign is 3 cm. */
        const val HEADROOM = 0.062f

        /** The least height of the bookcase on screen, which in landscape sets how far the camera is. */
        private const val MIN_VISIBLE_HEIGHT = 0.64f
        const val PLANK_THICKNESS = 0.034f
        const val PLANK_DEPTH = 0.15f
        const val BOTTOM_Z = 0.065f
        const val LEAN = 0.19f
        const val CORNICE_HEIGHT = 0.075f
        const val CORNICE_OVERHANG = 0.012f
        const val PLAQUE_WIDTH = 0.26f
        const val PLAQUE_HEIGHT = 0.042f
        const val LIGHTS_DONE = 3f
        const val LABEL_WIDTH = 0.16f
        const val LABEL_HEIGHT = 0.025f
        const val SIGN_HEIGHT = 0.03f
        private const val SIGN_ABOVE = 0.03f
        const val SIGN_MAX_WIDTH = 0.42f
        private const val SEARCH_FADE = 0.25f
        private const val FOCUS_RATE = 7f
        private const val HOLD_OMEGA = 9f

        /** Air between the comic in hand and the edges of the room it has, in parts of the screen's height. */
        private const val HAND_MARGIN = 0.025f

        /**
         * How far the bookmark of a comic being read seems to rise out of its top in the hands,
         * lying at rest (meters; it curls a little towards the eye, so it looks a bit taller).
         */
        private const val RIBBON_RISE = 0.058f

        /** However little room there is, the comic in hand is not made smaller than that. */
        private const val MIN_HAND_FILL = 0.3f
        private const val ROOM_RATE = 9f
        private const val ROTATE_PER_PIXEL = 0.0075f
        private const val MAX_PITCH = 0.8f
        private const val ROTATE_FRICTION = 2.2f

        /** Turning slower than this (radians a second), the comic in hand settles facing front or back. */
        private const val SETTLE_SPEED = 0.6f
        private const val SCROLL_FRICTION = 3.2f
        private const val PICK_SLACK = 0.004f
        private const val APPEAR_SHINE = 0.8f
    }
}
