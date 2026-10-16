package com.readcomic.ui

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The arithmetic of a drawn back cover, apart from the drawing itself. */
internal object BackCoverLayout {
    private val NUMBER = Regex("""#\s*(\S{1,4})\s*$""")

    /** Print screens are turned so that the rows of dots do not line up with the picture. */
    const val SCREEN_ANGLE = 45f

    /** "Saga #12" → ("Saga", "12"); a title without a number stays whole. */
    fun splitIssue(title: String): Pair<String, String?> {
        val match = NUMBER.find(title) ?: return title to null
        val name = title.substring(0, match.range.first).trim().trimEnd(',', '-', '–', '—', ':').trim()
        return if (name.isEmpty()) title to null else name to match.groupValues[1]
    }

    /**
     * Radius of a halftone dot in a cell [pitch] wide, for a [tone] from 0 (no ink) to 1 (all
     * ink): the dot covers as much of its cell as the tone says, as in print.
     */
    fun dotRadius(tone: Float, pitch: Float): Float {
        val t = tone.coerceIn(0f, 1f)
        return (pitch * sqrt(t / PI.toFloat())).coerceAtMost(pitch * MAX_DOT)
    }

    /**
     * Centres of the dots of a screen [pitch] apart, turned by [angle] degrees, that cover a
     * [width] x [height] picture (a dot's reach past an edge included), as x, y pairs.
     */
    fun screen(width: Float, height: Float, pitch: Float, angle: Float = SCREEN_ANGLE): FloatArray {
        val a = angle * PI.toFloat() / 180f
        val ux = cos(a) * pitch
        val uy = sin(a) * pitch
        // Enough rows and columns of the turned grid to cover the whole picture from its centre.
        val reach = ceil(sqrt(width * width + height * height) / (2f * pitch)).toInt() + 1
        val cx = width / 2f
        val cy = height / 2f
        val out = ArrayList<Float>((2 * reach + 1) * (2 * reach + 1))
        for (i in -reach..reach) {
            for (j in -reach..reach) {
                val x = cx + i * ux - j * uy
                val y = cy + i * uy + j * ux
                if (x < -pitch || x > width + pitch || y < -pitch || y > height + pitch) continue
                out += x
                out += y
            }
        }
        return out.toFloatArray()
    }

    /**
     * Tones (0 = no ink, 1 = all ink) from [luminance] (0 = black, 1 = white), stretched so the
     * picture uses the whole range of the screen. With a dark ink the dark parts take the most
     * ink; with a light ink on a dark ground ([darkInk] false) the light parts do.
     */
    fun tones(luminance: FloatArray, darkInk: Boolean): FloatArray {
        if (luminance.isEmpty()) return luminance
        val sorted = luminance.sortedArray()
        val low = sorted[(sorted.size * 0.02f).toInt().coerceIn(0, sorted.lastIndex)]
        val high = sorted[(sorted.size * 0.98f).toInt().coerceIn(0, sorted.lastIndex)]
        val span = (high - low).coerceAtLeast(0.08f)
        return FloatArray(luminance.size) { i ->
            val l = ((luminance[i] - low) / span).coerceIn(0f, 1f)
            if (darkInk) 1f - l else l
        }
    }

    /** Twelve digits of a UPC-A code for the comic [seed] (comic codes start with 7), the last a valid check digit. */
    fun upcDigits(seed: String): String {
        var h = 1125899906842597L
        for (c in seed) h = 31 * h + c.code
        val body = StringBuilder("7")
        var x = h
        repeat(10) {
            x = x * 6364136223846793005L + 1442695040888963407L
            body.append(((x ushr 33) % 10).toInt())
        }
        return body.append(upcCheck(body.toString())).toString()
    }

    /** The check digit of the first eleven digits of a UPC-A code. */
    fun upcCheck(digits: String): Int {
        var sum = 0
        for (i in 0 until 11) sum += (digits[i] - '0') * if (i % 2 == 0) 3 else 1
        return (10 - sum % 10) % 10
    }

    /** The 95 modules of a UPC-A code (true = a bar), guards and all. */
    fun upcModules(digits: String): BooleanArray {
        val out = BooleanArray(95)
        var at = 0
        fun put(pattern: String) {
            for (c in pattern) out[at++] = c == '1'
        }
        put("101")
        for (i in 0 until 6) put(UPC_LEFT[digits[i] - '0'])
        put("01010")
        for (i in 6 until 12) put(UPC_LEFT[digits[i] - '0'].map { if (it == '1') '0' else '1' }.joinToString(""))
        put("101")
        return out
    }

    /** Modules of the guards, drawn longer than the rest. */
    fun isGuard(module: Int) = module < 3 || module in 45..49 || module >= 92

    private const val MAX_DOT = 0.71f

    private val UPC_LEFT = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011",
    )
}
