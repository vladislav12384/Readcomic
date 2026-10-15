package com.readcomic.shelf

/**
 * The box new comics come in: a plain corrugated carton, its four top flaps closed over each
 * other and sealed with a strip of tape along the seam.
 *
 * Meters, around the centre of the box: x across, y up, z towards the reader (the front wall,
 * with the print and the label, faces +z).
 */
object CardboardBox {
    const val WIDTH = 0.31f
    const val HEIGHT = 0.11f
    const val DEPTH = 0.37f
    const val WALL = 0.0045f
    const val TAPE_WIDTH = 0.05f
    private const val TAPE = 0.0006f
    private const val TAB = 0.05f

    /** How far the flaps open: the long ones past upright, the short ones a little less. */
    const val LONG_OPEN = 2.3f
    const val SHORT_OPEN = 2.0f

    const val KIND_CARDBOARD = 0
    const val KIND_TAPE = 1

    /**
     * A piece of the box: a box of [size] at [center], turned by [rotation], in the box's own
     * meters. [printFace] is the face (0 front .. 5 bottom, as on the unit box) carrying the
     * print, -1 none; [innerFace] the one facing into the box, -1 none (for tape: the side of
     * the seam, see [hinged]).
     */
    class Part(val center: Vec3, val rotation: Quat, val size: Vec3, val kind: Int, val printFace: Int = -1, val innerFace: Int = -1)

    /** Flaps in order: front, back, left, right; the two long ones carry the tape. */
    fun parts(flaps: FloatArray): List<Part> {
        val out = ArrayList<Part>(14)
        val w = WIDTH
        val h = HEIGHT
        val d = DEPTH
        val t = WALL
        out += Part(Vec3(0f, -h / 2f + t / 2f, 0f), Quat.IDENTITY, Vec3(w, t, d), KIND_CARDBOARD, innerFace = 4)
        out += Part(Vec3(0f, 0f, d / 2f - t / 2f), Quat.IDENTITY, Vec3(w, h, t), KIND_CARDBOARD, printFace = 0, innerFace = 1)
        out += Part(Vec3(0f, 0f, -d / 2f + t / 2f), Quat.IDENTITY, Vec3(w, h, t), KIND_CARDBOARD, innerFace = 0)
        out += Part(Vec3(-w / 2f + t / 2f, 0f, 0f), Quat.IDENTITY, Vec3(t, h, d - 2f * t), KIND_CARDBOARD, innerFace = 3)
        out += Part(Vec3(w / 2f - t / 2f, 0f, 0f), Quat.IDENTITY, Vec3(t, h, d - 2f * t), KIND_CARDBOARD, innerFace = 2)

        // The short flaps, folded in first, lie under the long ones.
        val short = w / 2f - 0.004f
        val shortWidth = w - 2f * t - 0.006f
        out += hinged(Vec3(0f, h / 2f, d / 2f - t), Quat.axisAngle(X, flaps[0]), Vec3(0f, t / 2f, -short / 2f), Vec3(shortWidth, t, short))
        out += hinged(Vec3(0f, h / 2f, -d / 2f + t), Quat.axisAngle(X, -flaps[1]), Vec3(0f, t / 2f, short / 2f), Vec3(shortWidth, t, short))

        // The long flaps meet along the middle; a half of the tape on each.
        val long = w / 2f - 0.0015f
        val leftHinge = Vec3(-w / 2f, h / 2f + t, 0f)
        val rightHinge = Vec3(w / 2f, h / 2f + t, 0f)
        val left = Quat.axisAngle(Z, flaps[2])
        val right = Quat.axisAngle(Z, -flaps[3])
        out += hinged(leftHinge, left, Vec3(long / 2f, t / 2f, 0f), Vec3(long, t, d))
        out += hinged(rightHinge, right, Vec3(-long / 2f, t / 2f, 0f), Vec3(long, t, d))
        // Each half reaches the middle of the box, over the hair of a gap between the flaps.
        val reach = w / 2f - long
        val half = TAPE_WIDTH / 2f + reach
        out += hinged(leftHinge, left, Vec3(long + reach - half / 2f, t + TAPE / 2f, 0f), Vec3(half, TAPE, d + 0.002f), KIND_TAPE, seam = 3)
        out += hinged(rightHinge, right, Vec3(-long - reach + half / 2f, t + TAPE / 2f, 0f), Vec3(half, TAPE, d + 0.002f), KIND_TAPE, seam = 2)

        // Where the tape runs down over the front and the back.
        out += Part(Vec3(0f, h / 2f + 2f * t - TAB / 2f, d / 2f + TAPE / 2f), Quat.IDENTITY, Vec3(TAPE_WIDTH, TAB, TAPE), KIND_TAPE)
        out += Part(Vec3(0f, h / 2f + 2f * t - TAB / 2f, -d / 2f - TAPE / 2f), Quat.IDENTITY, Vec3(TAPE_WIDTH, TAB, TAPE), KIND_TAPE)
        return out
    }

    /** A piece turning about a hinge; for tape, [seam] is the side of it on the cut (3 its +x, 2 its -x). */
    private fun hinged(hinge: Vec3, rotation: Quat, offset: Vec3, size: Vec3, kind: Int = KIND_CARDBOARD, seam: Int = -1): Part =
        Part(hinge + rotation.rotate(offset), rotation, size, kind, innerFace = if (kind == KIND_CARDBOARD) 5 else seam)

    /** Where the [k]th comic from the bottom lies in the box, [lift] meters above the floor. */
    fun slot(lift: Float): Vec3 = Vec3(0f, -HEIGHT / 2f + WALL + lift, 0.004f)

    /** Inner floor to the top of the walls. */
    const val INSIDE = HEIGHT - WALL

    private val X = Vec3(1f, 0f, 0f)
    private val Z = Vec3(0f, 0f, 1f)
}
