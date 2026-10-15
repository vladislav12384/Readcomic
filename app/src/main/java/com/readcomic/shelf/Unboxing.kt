package com.readcomic.shelf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** What the box of new comics is doing, for the buttons and hints over it. */
enum class UnboxPhase {
    /** No new comics. */
    NONE,

    /** The box peeks in at the bottom of the screen. */
    PEEK,

    /** Up in front of the reader, still taped shut. */
    SEALED,

    /** A comic in hand, its wrapping waiting to be torn off. */
    TEARING,

    /** Something is on its way: the box opening, a comic coming out or going to its shelf. */
    BUSY,
}

data class UnboxState(val phase: UnboxPhase, val count: Int, val progress: Float = 0f)

/** Sounds and touches of unpacking, for the speaker and the vibration motor. */
enum class UnboxCue { BOX_LANDS, BOX_SHAKES, TAPE_DONE, FLAP, POKE, TEAR_TICK, RELEASE, SHINE }

/**
 * The box of new comics and their unwrapping, as the reader goes through it:
 *
 * 1. the box peeks in at the bottom of the screen (dropping in with a bounce when comics arrive);
 * 2. a tap brings it up in front of the reader, a finger along the tape cuts it, the flaps spring
 *    open over the stack of comics in their paper wrapping;
 * 3. the top one rises out into the reader's hands, where the paper is torn off with a finger,
 *    strip by strip;
 * 4. once most of it is torn the rest falls away in two halves, the cover gleams, and the comic
 *    is handed over to the shelves ([Listener.onUnwrapped]), which put it in its place; the next
 *    one comes out of the box, and the empty box closes and goes.
 *
 * Not thread-safe: lives on the render thread with the [ShelfController] that owns it.
 */
class Unboxing(private val host: Host) {

    interface Host {
        val aspect: Float

        /** Point straight in front of the camera at [depth], through the screen point (sx, sy) in 0..1. */
        fun pointInFront(sx: Float, sy: Float, depth: Float): Vec3

        /** The line of sight through the screen point (x, y) in pixels: near and far ends. */
        fun eyeRay(x: Float, y: Float): Pair<Vec3, Vec3>?

        /** Where a comic of [size] is held in front of the reader, facing them. */
        fun handPose(size: Vec3): Pair<Vec3, Quat>

        fun sizeOf(item: ShelfItem): Vec3

        /** Whether a comic handed over is still on its way to its place on the shelves. */
        val shelving: Boolean
    }

    interface Listener {
        fun onUnwrapped(id: String)
        fun onStateChanged(state: UnboxState)
        fun onCue(cue: UnboxCue, strength: Float)
    }

    var listener: Listener? = null

    // The comics in the box, the next one to come out first.
    private var contents: List<ShelfItem> = emptyList()

    /** Every comic that has been in the box, for the shelves to make the newcomers gleam. */
    val boxedIds = HashSet<String>()

    // Tears are kept for the comics put back into the box half unwrapped.
    private val tears = HashMap<String, WrapTear>()
    private val seeds = HashMap<String, Int>()

    private enum class Stage { ABSENT, ARRIVING, PEEK, RISING, SEALED, CUTTING, OPENING, LIFTING, TEARING, RELEASING, WAITING, PLACING, RETURNING, CLOSING }

    private var stage = Stage.ABSENT
    private var stageTime = 0f
    private var time = 0f

    /** Whether the box takes the touches (it is up in front of the reader). */
    val active: Boolean
        get() = stage != Stage.ABSENT && stage != Stage.PEEK && stage != Stage.ARRIVING && stage != Stage.CLOSING

    /** Whether there is a box on screen at all. */
    val present: Boolean get() = stage != Stage.ABSENT

    // region Box pose

    private enum class Spot { ABOVE, PEEK, PRESENT, LOW, HIDDEN, BELOW }

    private var spot = Spot.ABOVE
    private val boxPosition = Spring3()

    // Placed straight in its spot on the next frame (once the size of the screen is known).
    private var snapPending = false
    private var boxRotation = Quat.IDENTITY

    // Peeking in at the corner the box is drawn smaller, so that it stays in front of the shelves.
    private var boxScale = PEEK_SCALE
    private var placed = false

    private val flaps = FloatArray(4)
    private val flapSpeed = FloatArray(4)
    private val flapTarget = FloatArray(4)
    private var opened = false
    private var cutLow = 0f
    private var cutHigh = 0f
    private var cutting = false
    private var autoCut = -1f
    private var autoFrom = 0f to 0f

    private fun spotScale(spot: Spot) = if (spot == Spot.PRESENT || spot == Spot.LOW || spot == Spot.HIDDEN) 1f else PEEK_SCALE

    /**
     * Where the box is for each [spot]: how much of the screen's width it takes, where on screen
     * (0..1), how far it is tipped towards the reader and turned. Up in front of the reader it is
     * always further than the comic in hand, so that it never comes between them.
     */
    private fun spotPose(spot: Spot): Pair<Vec3, Quat> {
        val portrait = host.aspect < 1f
        val (fill, sx, sy, tilt, yaw) = if (portrait) {
            when (spot) {
                Spot.PEEK -> Pose5(0.46f, 0.26f, 0.9f, 0.38f, 0.36f)
                Spot.ABOVE -> Pose5(0.46f, 0.26f, -0.45f, 0.38f, 0.36f)
                Spot.BELOW -> Pose5(0.46f, 0.26f, 1.6f, 0.38f, 0.36f)
                Spot.PRESENT -> Pose5(0.8f, 0.5f, 0.6f, 0.95f, 0f)
                Spot.LOW -> Pose5(0.8f, 0.5f, 1.2f, 0.95f, 0f)
                Spot.HIDDEN -> Pose5(0.8f, 0.5f, 1.9f, 0.95f, 0f)
            }
        } else {
            when (spot) {
                Spot.PEEK -> Pose5(0.22f, 0.12f, 0.84f, 0.38f, 0.36f)
                Spot.ABOVE -> Pose5(0.22f, 0.12f, -0.45f, 0.38f, 0.36f)
                Spot.BELOW -> Pose5(0.22f, 0.12f, 1.6f, 0.38f, 0.36f)
                Spot.PRESENT -> Pose5(0.3f, 0.5f, 0.62f, 0.95f, 0f)
                Spot.LOW -> Pose5(0.24f, 0.5f, 1.32f, 0.95f, 0f)
                Spot.HIDDEN -> Pose5(0.24f, 0.5f, 2f, 0.95f, 0f)
            }
        }
        val depth = CardboardBox.WIDTH * spotScale(spot) / (2f * tan(ShelfController.FOV / 2f) * host.aspect * fill)
        val position = host.pointInFront(sx, sy, depth)
        val face = Quat.axisAngle(X, 0.05f)
        val rotation = face * Quat.axisAngle(Y, yaw) * Quat.axisAngle(X, tilt)
        return position to rotation
    }

    private data class Pose5(val fill: Float, val sx: Float, val sy: Float, val tilt: Float, val yaw: Float)

    private fun moveBox(to: Spot, bouncy: Boolean = false) {
        spot = to
        boxPosition.omega = if (bouncy) 12f else 8f
        boxPosition.damping = if (bouncy) 0.42f else 0.9f
    }

    // endregion

    // region The comic in hand

    private var inHand: ShelfItem? = null
    private var tear: WrapTear? = null
    private var liftT = 0f
    private var releaseT = 0f
    private var shineT = -1f
    private val jiggle = Spring3().also {
        it.omega = 26f
        it.damping = 0.35f
    }
    private var handed: String? = null

    // The wrapping of the comic just handed over: its last strips are still in the air.
    private var leftover: WrapTear? = null
    private var leftoverPose = Vec3.ZERO to Quat.IDENTITY
    private var leftoverSize = Vec3.ZERO
    private var leftoverId = ""
    private var lastTouchTime = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tearing = false
    private var tickArea = 0f
    private var lastTick = 0f

    /** How loud the paper is being torn right now, 0..1. */
    @Volatile
    var tearLoudness = 0f
        private set

    /** How fast the tape is being cut, 0..1. */
    @Volatile
    var tapeLoudness = 0f
        private set

    private var tearRate = 0f
    private var tapeRate = 0f

    // endregion

    // region Contents

    /**
     * The comics in the box, the first to come out first. Called when the collection changes;
     * [quietly] when the box was already there before (back from reading): it is simply there.
     */
    fun setContents(items: List<ShelfItem>, quietly: Boolean = false) {
        val before = contents.map { it.id }.toSet() + listOfNotNull(inHand?.id)
        contents = items.filter { it.id != inHand?.id }
        items.forEach { boxedIds += it.id }
        tears.keys.retainAll(items.map { it.id }.toSet() + listOfNotNull(inHand?.id))
        val arrived = items.any { it.id !in before }
        when {
            items.isEmpty() && inHand == null -> if (stage != Stage.ABSENT && stage != Stage.CLOSING) close()
            stage == Stage.ABSENT && items.isNotEmpty() && quietly -> {
                arrive()
                stage = Stage.PEEK
                snapPending = true
            }
            stage == Stage.ABSENT && items.isNotEmpty() -> arrive()
            stage == Stage.CLOSING && items.isNotEmpty() -> arrive()
            arrived && before.isNotEmpty() -> {
                // More comics in the box: it gives a little hop.
                boxPosition.velocity = boxPosition.velocity + Vec3(0f, 0.35f, 0f)
                listener?.onCue(UnboxCue.BOX_SHAKES, 0.6f)
            }
        }
        notifyState()
    }

    private fun arrive() {
        stage = Stage.ARRIVING
        stageTime = 0f
        opened = false
        cutLow = 0f
        cutHigh = 0f
        flaps.fill(0f)
        flapSpeed.fill(0f)
        flapTarget.fill(0f)
        if (!placed || spot == Spot.BELOW) {
            val (position, rotation) = spotPose(Spot.ABOVE)
            boxPosition.snap(position)
            boxRotation = rotation
            boxScale = PEEK_SCALE
            placed = true
        }
        moveBox(Spot.PEEK, bouncy = true)
    }

    private fun close() {
        stage = Stage.CLOSING
        stageTime = 0f
        flapTarget.fill(0f)
        moveBox(Spot.BELOW)
    }

    // endregion

    // region Input

    /** Whether a tap at (x, y) pixels lands on the box peeking in; if so it comes up. */
    fun tapPeek(x: Float, y: Float): Boolean {
        if (stage != Stage.PEEK && stage != Stage.ARRIVING) return false
        if (!hitsBox(x, y)) return false
        bringUp()
        return true
    }

    /** Brings the box up in front of the reader (from the button too). */
    fun bringUp() {
        if (stage != Stage.PEEK && stage != Stage.ARRIVING) return
        if (contents.isEmpty()) return
        stage = Stage.RISING
        stageTime = 0f
        moveBox(Spot.PRESENT)
        notifyState()
    }

    /** Back to peeking: the comic in hand goes back into the box, the tear kept. */
    fun putAway() {
        when (stage) {
            Stage.RISING, Stage.SEALED, Stage.CUTTING, Stage.OPENING -> {
                stage = Stage.PEEK
                cutting = false
                autoCut = -1f
                moveBox(Spot.PEEK)
            }
            Stage.LIFTING, Stage.TEARING -> {
                tear?.lift()
                tearing = false
                stage = Stage.RETURNING
                stageTime = 0f
                moveBox(Spot.PRESENT)
            }
            else -> return
        }
        notifyState()
    }

    fun down(x: Float, y: Float) {
        lastTouchTime = time
        when (stage) {
            Stage.SEALED, Stage.CUTTING -> {
                val point = onBoxTop(x, y) ?: return
                if (abs(point.x) > CUT_REACH) return
                if (autoCut >= 0f) return
                cutting = true
                if (stage == Stage.SEALED) {
                    stage = Stage.CUTTING
                    // A cut begun earlier goes on from where it was.
                    if (cutHigh <= cutLow) {
                        cutLow = point.z
                        cutHigh = point.z
                    }
                }
                lastX = point.z
            }
            Stage.TEARING -> {
                val t = tear ?: return
                val point = onPaper(x, y) ?: return
                tearing = true
                lastX = point.first
                lastY = point.second
                if (t.touch(point.first, point.second)) {
                    listener?.onCue(UnboxCue.POKE, 0.7f)
                    push(0f, 0f, -0.004f)
                    afterTear(t)
                }
            }
            else -> Unit
        }
    }

    fun move(x: Float, y: Float) {
        val dt = max(time - lastTouchTime, 1f / 240f)
        lastTouchTime = time
        when (stage) {
            Stage.CUTTING -> {
                if (!cutting) return
                val point = onBoxTop(x, y) ?: return
                if (abs(point.x) > CUT_REACH * 2f) return
                val z = point.z.coerceIn(-CardboardBox.DEPTH / 2f, CardboardBox.DEPTH / 2f)
                val before = cutHigh - cutLow
                cutLow = min(cutLow, z)
                cutHigh = max(cutHigh, z)
                val speed = abs(z - lastX) / dt
                lastX = z
                if (cutHigh - cutLow > before) {
                    tapeRate = max(tapeRate, (speed / 0.6f).coerceIn(0f, 1f))
                    tickArea += (cutHigh - cutLow - before) * 0.004f
                    if (tickArea > TICK_AREA * 0.3f && time - lastTick > 0.03f) {
                        listener?.onCue(UnboxCue.TEAR_TICK, 0.5f)
                        tickArea = 0f
                        lastTick = time
                    }
                }
                if ((cutHigh - cutLow) / CardboardBox.DEPTH > 0.72f) finishCut()
            }
            Stage.TEARING -> {
                if (!tearing) return
                val t = tear ?: return
                val point = onPaper(x, y) ?: return
                val dx = point.first - lastX
                val dy = point.second - lastY
                val speed = kotlin.math.hypot(dx, dy) / dt
                val area = t.drag(point.first, point.second, speed)
                lastX = point.first
                lastY = point.second
                if (area > 0f) {
                    tearRate = max(tearRate, (area / dt / TEAR_LOUD).coerceIn(0f, 1.4f))
                    tickArea += area
                    if (tickArea > TICK_AREA && time - lastTick > 0.03f) {
                        listener?.onCue(UnboxCue.TEAR_TICK, (tickArea / (TICK_AREA * 3f)).coerceIn(0.3f, 1f))
                        tickArea = 0f
                        lastTick = time
                    }
                    // The comic gives a little under the pull.
                    val norm = max(kotlin.math.hypot(dx, dy), 1e-5f)
                    push(dx / norm * 0.0025f, dy / norm * 0.0025f, -0.0015f)
                    afterTear(t)
                }
            }
            else -> Unit
        }
    }

    fun up() {
        when (stage) {
            Stage.CUTTING -> {
                cutting = false
                if ((cutHigh - cutLow) / CardboardBox.DEPTH > 0.45f) finishCut()
            }
            Stage.TEARING -> {
                tearing = false
                val t = tear ?: return
                t.lift()
                if (t.progress >= RELEASE_AT) release()
            }
            else -> Unit
        }
    }

    /** A tap while the box is up: on the taped box it cuts the tape by itself. */
    fun tap(x: Float, y: Float) {
        if ((stage == Stage.SEALED || stage == Stage.CUTTING) && hitsBox(x, y)) finishCut()
    }

    private fun finishCut() {
        if (stage != Stage.CUTTING && stage != Stage.SEALED) return
        cutting = false
        stage = Stage.CUTTING
        // The rest of the cut runs along by itself: from the front if nothing is cut yet.
        if (cutHigh <= cutLow) {
            cutLow = CardboardBox.DEPTH / 2f
            cutHigh = CardboardBox.DEPTH / 2f
        }
        autoFrom = cutLow to cutHigh
        autoCut = 0f
        notifyState()
    }

    private fun afterTear(t: WrapTear) {
        if (t.progress >= FORCE_RELEASE_AT) {
            t.lift()
            tearing = false
            release()
        }
        notifyState()
    }

    private fun release() {
        if (stage != Stage.TEARING) return
        val item = inHand ?: return
        val t = tear ?: return
        stage = Stage.RELEASING
        stageTime = 0f
        releaseT = 0f
        // What is left of the paper comes apart into its own pieces, which come off one by one.
        val size = host.sizeOf(item)
        val seed = seedOf(item.id)
        val (position, rotation) = comicPose(item, size)
        t.release(position, rotation, time) { x, y -> WrapperShape.frontPoint(x, y, size, t, seed) }
        listener?.onCue(UnboxCue.RELEASE, 1f)
        notifyState()
    }

    private fun push(x: Float, y: Float, z: Float) {
        jiggle.velocity = jiggle.velocity + Vec3(x, y, z) * 6f
    }

    // endregion

    // region Hit tests

    private fun boxModel(): Pair<Vec3, Quat> = boxPosition.position to boxRotation

    private fun localRay(x: Float, y: Float, position: Vec3, rotation: Quat): Pair<Vec3, Vec3>? {
        val (near, far) = host.eyeRay(x, y) ?: return null
        val inverse = Quat(-rotation.x, -rotation.y, -rotation.z, rotation.w)
        return inverse.rotate(near - position) to inverse.rotate(far - position)
    }

    private fun hitsBox(x: Float, y: Float): Boolean {
        val (position, rotation) = boxModel()
        val (a, b) = localRay(x, y, position, rotation) ?: return false
        val open = if (opened) 0.16f else 0.02f
        val s = boxScale
        return rayHitsBox(a, b - a, Vec3(CardboardBox.WIDTH / 2f + 0.02f, CardboardBox.HEIGHT / 2f + open, CardboardBox.DEPTH / 2f + 0.02f) * s)
    }

    /** Where the line of sight through (x, y) meets the top of the closed box, in the box's meters. */
    private fun onBoxTop(x: Float, y: Float): Vec3? {
        val (position, rotation) = boxModel()
        val (a0, b0) = localRay(x, y, position, rotation) ?: return null
        val a = a0 * (1f / boxScale)
        val b = b0 * (1f / boxScale)
        val top = CardboardBox.HEIGHT / 2f + 2f * CardboardBox.WALL
        val d = b - a
        if (abs(d.y) < 1e-6f) return null
        val t = (top - a.y) / d.y
        if (t < 0f || t > 1f) return null
        val p = a + d * t
        if (abs(p.z) > CardboardBox.DEPTH / 2f + 0.03f) return null
        return p
    }

    /** Where the finger at (x, y) is on the paper of the comic in hand, in its meters. */
    private fun onPaper(x: Float, y: Float): Pair<Float, Float>? {
        val item = inHand ?: return null
        val size = host.sizeOf(item)
        val (position, rotation) = comicPose(item, size)
        val (a, b) = localRay(x, y, position, rotation) ?: return null
        val plane = WrapperShape.radius(size.z)
        val d = b - a
        if (abs(d.z) < 1e-6f) return null
        val t = (plane - a.z) / d.z
        val p = a + d * t
        return p.x to p.y
    }

    private fun rayHitsBox(origin: Vec3, direction: Vec3, half: Vec3): Boolean {
        var tMin = 0f
        var tMax = 1f
        val o = floatArrayOf(origin.x, origin.y, origin.z)
        val d = floatArrayOf(direction.x, direction.y, direction.z)
        val h = floatArrayOf(half.x, half.y, half.z)
        for (i in 0..2) {
            if (abs(d[i]) < 1e-9f) {
                if (o[i] < -h[i] || o[i] > h[i]) return false
            } else {
                var t1 = (-h[i] - o[i]) / d[i]
                var t2 = (h[i] - o[i]) / d[i]
                if (t1 > t2) t1 = t2.also { t2 = t1 }
                tMin = max(tMin, t1)
                tMax = min(tMax, t2)
                if (tMin > tMax) return false
            }
        }
        return true
    }

    // endregion

    // region Shelves

    /**
     * The shelves now have the comic [id] (unwrapped): it is theirs from here on. Returns
     * whether it was the one in hand, to be taken over from where it is.
     */
    fun handOver(id: String): Boolean {
        if (inHand?.id != id) return false
        letGo()
        tears.remove(id)
        handed = null
        shineT = -1f
        stage = Stage.PLACING
        stageTime = 0f
        // Out of the way while the comic goes to its place.
        moveBox(Spot.HIDDEN)
        notifyState()
        return true
    }

    private fun letGo() {
        val item = inHand
        if (item != null && tear != null) {
            leftover = tear
            leftoverSize = host.sizeOf(item)
            leftoverId = item.id
            leftoverPose = comicPose(item, leftoverSize)
        }
        inHand = null
        tear = null
    }

    /** Where the comic in hand is (for the shelves to take it from there). */
    fun inHandPose(): Pair<Vec3, Quat>? {
        val item = inHand ?: return null
        return comicPose(item, host.sizeOf(item))
    }

    val inHandId: String? get() = inHand?.id

    // endregion

    // region Animation

    /** Advances the box and the comic by [dt] seconds. Returns whether another frame is needed. */
    fun step(dt: Float): Boolean {
        if (stage == Stage.ABSENT) return false
        time += dt
        stageTime += dt
        var animating = true

        when (stage) {
            Stage.ARRIVING -> if (boxPosition.settled(spotPose(Spot.PEEK).first, 0.0005f)) {
                stage = Stage.PEEK
                notifyState()
            } else if (stageTime > 0.25f && stageTime - dt <= 0.25f) {
                listener?.onCue(UnboxCue.BOX_LANDS, 1f)
            }
            Stage.RISING -> if (stageTime > 0.45f) {
                if (opened) startLift() else stage = Stage.SEALED
                notifyState()
            }
            Stage.CUTTING -> if (autoCut >= 0f) {
                autoCut += dt / AUTO_CUT_TIME
                val e = autoCut.coerceAtMost(1f)
                val half = CardboardBox.DEPTH / 2f
                cutLow = autoFrom.first + (-half - autoFrom.first) * e
                cutHigh = autoFrom.second + (half - autoFrom.second) * e
                tapeRate = max(tapeRate, 0.8f)
                if (autoCut >= 1f) {
                    cutLow = -half
                    cutHigh = half
                    autoCut = -1f
                    opened = true
                    stage = Stage.OPENING
                    stageTime = 0f
                    listener?.onCue(UnboxCue.TAPE_DONE, 1f)
                    notifyState()
                }
            }
            Stage.OPENING -> {
                // The long flaps spring up first, the short ones after them.
                if (flapTarget[2] == 0f) {
                    flapTarget[2] = CardboardBox.LONG_OPEN
                    flapTarget[3] = CardboardBox.LONG_OPEN
                    listener?.onCue(UnboxCue.FLAP, 0.9f)
                }
                if (stageTime > 0.14f && flapTarget[0] == 0f) {
                    flapTarget[0] = CardboardBox.SHORT_OPEN
                    flapTarget[1] = CardboardBox.SHORT_OPEN
                    listener?.onCue(UnboxCue.FLAP, 0.6f)
                }
                if (stageTime > 0.6f) startLift()
            }
            Stage.LIFTING -> {
                liftT = min(1f, liftT + dt / LIFT_TIME)
                if (liftT > 0.35f && spot != Spot.LOW) moveBox(Spot.LOW)
                if (liftT >= 1f) {
                    stage = Stage.TEARING
                    stageTime = 0f
                    notifyState()
                }
            }
            Stage.TEARING -> Unit
            Stage.RELEASING -> {
                releaseT = min(1f, releaseT + dt / RELEASE_TIME)
                if (releaseT > 0.42f && shineT < 0f) {
                    shineT = 0f
                    listener?.onCue(UnboxCue.SHINE, 1f)
                }
                if (shineT >= 0f) shineT = min(1f, shineT + dt / SHINE_TIME)
                if (shineT >= 1f && releaseT >= 1f) {
                    stage = Stage.WAITING
                    stageTime = 0f
                    val id = inHand?.id
                    handed = id
                    if (id != null) listener?.onUnwrapped(id)
                    notifyState()
                }
            }
            Stage.WAITING -> if (stageTime > HAND_OVER_TIMEOUT) {
                // The shelves never took it: let it go, they will have it when they catch up.
                letGo()
                stage = Stage.PLACING
                stageTime = 0f
                moveBox(Spot.HIDDEN)
            }
            // The next one comes out once the last is in its place.
            Stage.PLACING -> if ((stageTime > PLACE_TIME && !host.shelving) || stageTime > PLACE_TIME * 4f) {
                if (contents.isEmpty()) {
                    close()
                } else {
                    stage = Stage.RISING
                    stageTime = 0f
                    moveBox(Spot.PRESENT)
                }
                notifyState()
            }
            Stage.RETURNING -> {
                liftT = max(0f, liftT - dt / RETURN_TIME)
                if (liftT <= 0f) {
                    inHand?.let { item ->
                        tear?.let { tears[item.id] = it }
                        contents = listOf(item) + contents
                    }
                    inHand = null
                    tear = null
                    stage = Stage.PEEK
                    moveBox(Spot.PEEK)
                    notifyState()
                }
            }
            Stage.CLOSING -> if (stageTime > 0.9f) {
                stage = Stage.ABSENT
                placed = false
                inHand = null
                tear = null
                notifyState()
            }
            else -> Unit
        }

        // The box follows its spot.
        val (target, targetRotation) = spotPose(spot)
        if (snapPending) {
            snapPending = false
            boxPosition.snap(target)
            boxRotation = targetRotation
            boxScale = spotScale(spot)
        }
        boxPosition.step(target, dt)
        boxRotation = Quat.slerp(boxRotation, targetRotation, 1f - exp(-9f * dt))
        val scaleTarget = spotScale(spot)
        boxScale += (scaleTarget - boxScale) * (1f - exp(-9f * dt))
        if (abs(scaleTarget - boxScale) < 0.0005f) boxScale = scaleTarget else animating = true
        for (k in 0..3) {
            val omega = 13f
            val steps = max(1, (dt * 240f).toInt() + 1)
            val h = dt / steps
            repeat(steps) {
                flapSpeed[k] += (-omega * omega * (flaps[k] - flapTarget[k]) - 2f * 0.3f * omega * flapSpeed[k]) * h
                flaps[k] += flapSpeed[k] * h
            }
        }
        jiggle.step(Vec3.ZERO, dt)

        // Sounds die away when the finger stops.
        tearRate *= exp(-14f * dt)
        tapeRate *= exp(-12f * dt)
        tearLoudness = tearRate
        tapeLoudness = tapeRate

        val item = inHand
        val t = tear
        if (item != null && t != null) {
            val size = host.sizeOf(item)
            val (position, rotation) = comicPose(item, size)
            t.stepScraps(position, rotation, WrapperShape.radius(size.z), dt, time)
        }
        leftover?.let { rest ->
            if (!rest.stepScraps(leftoverPose.first, leftoverPose.second, 0f, dt, time)) leftover = null
        }

        // The shelves darken behind the box while it is up.
        val dimTarget = when (stage) {
            Stage.RISING, Stage.SEALED, Stage.CUTTING, Stage.OPENING, Stage.LIFTING, Stage.TEARING, Stage.RELEASING, Stage.WAITING -> DIM
            Stage.RETURNING -> DIM * liftT.coerceIn(0.4f, 1f)
            else -> 0f
        }
        dim += (dimTarget - dim) * (1f - exp(-6f * dt))
        if (abs(dimTarget - dim) < 0.002f) dim = dimTarget else animating = true

        if (stage == Stage.PEEK && boxPosition.settled(target, 0.0003f) && flapsSettled() && leftover == null && dim == 0f) animating = false
        if (stage == Stage.SEALED && boxPosition.settled(target, 0.0003f) && !tearingVisible() && dim == dimTarget) animating = false
        if (stage == Stage.TEARING && !tearing && boxPosition.settled(target, 0.0003f) && jiggle.settled(Vec3.ZERO, 0.0001f) &&
            tearRate < 0.01f && (t == null || !t.busy) && leftover == null && abs(dimTarget - dim) < 0.002f
        ) {
            animating = false
        }
        return animating
    }

    private fun tearingVisible() = tearRate > 0.01f || tapeRate > 0.01f

    private fun flapsSettled() = (0..3).all { abs(flaps[it] - flapTarget[it]) < 0.002f && abs(flapSpeed[it]) < 0.01f }

    private fun startLift() {
        val next = contents.firstOrNull()
        if (next == null) {
            close()
            return
        }
        inHand = next
        contents = contents.drop(1)
        handed = null
        tear = tears.remove(next.id) ?: run {
            val size = host.sizeOf(next)
            WrapTear(size.x, size.y, WrapperShape.margin(size.z), seedOf(next.id))
        }
        liftT = 0f
        releaseT = 0f
        shineT = -1f
        stage = Stage.LIFTING
        stageTime = 0f
    }

    private fun seedOf(id: String) = seeds.getOrPut(id) { id.hashCode() }

    // endregion

    // region Poses

    /** The comic coming out of the box into the hands: [liftT] 0 in the box, 1 in hand. */
    private fun comicPose(item: ShelfItem, size: Vec3): Pair<Vec3, Quat> {
        val (hand, handRotation) = host.handPose(size)
        // Breathing a little in the hands, and giving under the finger.
        val sway = Quat.axisAngle(X, 0.012f * sin(time * 1.3f)) * Quat.axisAngle(Y, 0.016f * sin(time * 0.9f + 1f))
        val give = handRotation.rotate(jiggle.position)
        val held = (hand + give) to (handRotation * sway)
        if (liftT >= 1f) return held
        val (inBox, boxRotationOfIt) = stackPose(item, size)
        val e = liftT * liftT * (3f - 2f * liftT)
        val up = boxRotation.rotate(Vec3(0f, 0.17f, 0f))
        val mid = inBox + up
        val u = 1f - e
        val position = inBox * (u * u) + mid * (2f * u * e) + held.first * (e * e)
        val turn = ((e - 0.15f) / 0.85f).coerceIn(0f, 1f)
        return position to Quat.slerp(boxRotationOfIt, held.second, turn * turn * (3f - 2f * turn))
    }

    /** A comic lying in the box: on top of those after it in the box (the next one is on top). */
    private fun stackPose(item: ShelfItem, size: Vec3): Pair<Vec3, Quat> {
        val visible = contents.take(MAX_STACK)
        var height = 0f
        val index = visible.indexOfFirst { it.id == item.id }.let { if (it < 0) -1 else it }
        for (i in visible.size - 1 downTo index + 1) height += host.sizeOf(visible[i]).z + 2f * WrapperShape.GAP + 0.0006f
        height = min(height, CardboardBox.INSIDE - 0.01f)
        val r = WrapperShape.radius(size.z)
        val seed = seedOf(item.id)
        val dx = ((seed and 0xFF) / 255f - 0.5f) * 0.012f
        val dz = (((seed shr 8) and 0xFF) / 255f - 0.5f) * 0.012f
        val yaw = (((seed shr 16) and 0xFF) / 255f - 0.5f) * 0.09f
        val local = CardboardBox.slot(height + r) + Vec3(dx, 0f, dz)
        val position = boxPosition.position + boxRotation.rotate(local * boxScale)
        val rotation = boxRotation * Quat.axisAngle(Y, yaw) * Quat.axisAngle(X, -PI.toFloat() / 2f)
        return position to rotation
    }

    // endregion

    // region Drawing

    /** What to draw of the box and its comics this frame, or null when there is no box. */
    fun draw(): UnboxDraw? {
        if (stage == Stage.ABSENT) return null
        val (boxPos, boxRot) = boxModel()
        val s = boxScale
        val parts = CardboardBox.parts(flaps).map { part ->
            val rotation = boxRot * part.rotation
            val center = boxPos + boxRot.rotate(part.center * s)
            BoxPartDraw(Mat4.compose(center, rotation, part.size * s), part.kind, part.printFace, part.innerFace)
        }
        val comics = ArrayList<WrappedDraw>()
        // The stack in the box: only the top few can be seen.
        for (item in contents.take(MAX_STACK).reversed()) {
            val size = host.sizeOf(item)
            val (position, rotation) = stackPose(item, size)
            val scaled = Vec3(s, s, s)
            comics += WrappedDraw(
                comic = ComicDraw(item.id, Mat4.compose(position, rotation, size * s), size, item.pageCount, ComicShape.binding(item.pageCount), null, held = false),
                drawComic = false,
                wrapper = WrapperDraw(item.id, Mat4.compose(position, rotation, scaled), Mat4.compose(position, rotation, scaled), 1f, tears[item.id], size, seedOf(item.id)),
                shine = -1f,
            )
        }
        val pieces = ArrayList<PiecesDraw>(2)
        var star: StarDraw? = null
        inHand?.let { item ->
            val size = host.sizeOf(item)
            val (position, rotation) = comicPose(item, size)
            val model = Mat4.compose(position, rotation, size)
            val t = tear
            val wrapper = if (stage == Stage.WAITING || releaseT >= 1f) {
                null
            } else {
                val (left, right, alpha) = halves(position, rotation, size)
                WrapperDraw(item.id, left, right, alpha, t, size, seedOf(item.id), released = t?.released == true)
            }
            comics += WrappedDraw(
                comic = ComicDraw(item.id, model, size, item.pageCount, ComicShape.binding(item.pageCount), null, held = true),
                drawComic = true,
                wrapper = wrapper,
                shine = shineT,
            )
            if (t != null) t.pieceMesh(position, rotation, WrapperShape.radius(size.z), time)?.let { (v, i) -> pieces += PiecesDraw(v, i, t, size, item.id) }
            if (shineT in 0f..1f) {
                val corner = position + rotation.rotate(Vec3(size.x * 0.3f, size.y * 0.36f, size.z / 2f + 0.004f))
                star = StarDraw(corner, size.y * 0.42f, shineT)
            }
        }
        leftover?.let { rest ->
            rest.pieceMesh(leftoverPose.first, leftoverPose.second, 0f, time)?.let { (v, i) -> pieces += PiecesDraw(v, i, rest, leftoverSize, leftoverId) }
        }
        return UnboxDraw(
            dim = dim,
            parts = parts,
            count = contents.size,
            tapeCut = if (cutHigh > cutLow) floatArrayOf(cutLow / CardboardBox.DEPTH + 0.5f, cutHigh / CardboardBox.DEPTH + 0.5f) else floatArrayOf(0f, 0f),
            comics = comics,
            pieces = pieces,
            star = star,
            frontId = inHand?.id,
        )
    }

    /**
     * The wrapping round the comic: on it, or, once the front has come off in pieces, what is
     * left round the edges and behind it slipping back and down out of sight. Returns its model
     * (for both halves) and opacity.
     */
    private fun halves(position: Vec3, rotation: Quat, size: Vec3): Triple<FloatArray, FloatArray, Float> {
        if (releaseT <= 0f) {
            val model = Mat4.compose(position, rotation, ONE)
            return Triple(model, model, 1f)
        }
        val e = releaseT
        val tip = Quat.axisAngle(X, -0.3f * e)
        val local = Vec3(0f, -0.08f * e * e, -0.04f * e)
        val model = Mat4.compose(position + rotation.rotate(local), rotation * tip, ONE)
        val alpha = 1f - (e / 0.35f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        return Triple(model, model, alpha)
    }

    /** How dark the shelves are behind the box (eased in [step]). */
    private var dim = 0f

    // endregion

    private var lastState: UnboxState? = null

    private fun notifyState() {
        val phase = when (stage) {
            Stage.ABSENT, Stage.CLOSING -> if (contents.isEmpty()) UnboxPhase.NONE else UnboxPhase.PEEK
            Stage.ARRIVING, Stage.PEEK -> UnboxPhase.PEEK
            Stage.SEALED, Stage.CUTTING -> if (autoCut >= 0f) UnboxPhase.BUSY else UnboxPhase.SEALED
            Stage.TEARING -> UnboxPhase.TEARING
            else -> UnboxPhase.BUSY
        }
        val count = contents.size + if (inHand != null && handed == null) 1 else 0
        val state = UnboxState(phase, count, tear?.progress ?: 0f)
        // Progress is reported in steps, not every torn millimetre.
        val last = lastState
        if (last != null && last.phase == state.phase && last.count == state.count && abs(last.progress - state.progress) < 0.05f) return
        lastState = state
        listener?.onStateChanged(state)
    }

    companion object {
        private val X = Vec3(1f, 0f, 0f)
        private val Y = Vec3(0f, 1f, 0f)
        private val ONE = Vec3(1f, 1f, 1f)
        private const val MAX_STACK = 6
        private const val DIM = 0.55f
        private const val PEEK_SCALE = 0.6f
        private const val CUT_REACH = 0.06f
        private const val AUTO_CUT_TIME = 0.45f
        private const val LIFT_TIME = 1.05f
        private const val RETURN_TIME = 0.6f
        private const val RELEASE_TIME = 1.1f
        private const val SHINE_TIME = 0.8f
        private const val PLACE_TIME = 0.5f
        private const val HAND_OVER_TIMEOUT = 2.5f

        /** Torn this much, the rest of the wrapping falls away when the finger lifts... */
        const val RELEASE_AT = 0.45f

        /** ...or this much, at once. */
        const val FORCE_RELEASE_AT = 0.72f

        /** Square meters a second of paper torn that make the loudest sound. */
        private const val TEAR_LOUD = 0.012f
        private const val TICK_AREA = 0.00018f
    }
}

/** Everything of the box and its comics to draw in one frame. */
class UnboxDraw(
    /** How dark the shelves are behind it. */
    val dim: Float,
    val parts: List<BoxPartDraw>,
    /** Comics still in the box, for its label. */
    val count: Int,
    /** The cut along the tape, from..to along it in 0..1. */
    val tapeCut: FloatArray,
    val comics: List<WrappedDraw>,
    /** Torn strips and pieces of paper coming off, see [WrapTear.pieceMesh]. */
    val pieces: List<PiecesDraw>,
    val star: StarDraw?,
    /** The comic in hand, whose sharp cover is wanted. */
    val frontId: String?,
)

/**
 * Pieces of the paper of the comic [comicId] (of [size]) torn off: [vertices] in the world and
 * [indices], drawn from the cells of [tear] they took.
 */
class PiecesDraw(val vertices: FloatArray, val indices: ShortArray, val tear: WrapTear, val size: Vec3, val comicId: String)

/** A piece of the box: a unit box scaled into place; see [CardboardBox.Part]. */
class BoxPartDraw(val model: FloatArray, val kind: Int, val printFace: Int, val innerFace: Int)

/** A comic in its wrapping (or out of it): [drawComic] false when the paper hides all of it. */
class WrappedDraw(val comic: ComicDraw, val drawComic: Boolean, val wrapper: WrapperDraw?, val shine: Float)

/**
 * The paper round a comic of [size]: its two halves placed by [left] and [right] (meters, no
 * scale), [alpha] fading them away; [tear] holds what is torn, null for intact paper.
 */
class WrapperDraw(
    val id: String,
    val left: FloatArray,
    val right: FloatArray,
    val alpha: Float,
    val tear: WrapTear?,
    val size: Vec3,
    val seed: Int,
    /** The front has come off in pieces: only the paper round the edges and behind is left. */
    val released: Boolean = false,
)

/** The glint on a freshly unwrapped cover: at [position], [size] meters across, [t] 0..1 through. */
class StarDraw(val position: Vec3, val size: Float, val t: Float)

/** A damped spring in 3D, for things that move to a place and may overshoot it. */
class Spring3 {
    var position = Vec3.ZERO
    var velocity = Vec3.ZERO
    var omega = 8f
    var damping = 0.9f

    fun snap(to: Vec3) {
        position = to
        velocity = Vec3.ZERO
    }

    fun step(target: Vec3, dt: Float) {
        val steps = max(1, (dt * 240f).toInt() + 1)
        val h = dt / steps
        repeat(steps) {
            val accel = (position - target) * (-omega * omega) - velocity * (2f * damping * omega)
            velocity = velocity + accel * h
            position = position + velocity * h
        }
    }

    fun settled(target: Vec3, eps: Float) = (position - target).length() < eps && velocity.length() < eps * 10f
}
