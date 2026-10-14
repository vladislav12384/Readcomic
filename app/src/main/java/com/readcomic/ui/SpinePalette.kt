package com.readcomic.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Colours for a printed spine, picked from the cover. Publishers carry the cover's background
 * round onto the spine when there is one, and print the title in a colour of the cover that
 * stands out on it (often the logo's).
 */
object SpinePalette {
    class Colors(val background: Int, val ink: Int)

    /** A group of similar colours on the cover: their average and how much of the picture they cover. */
    class Cluster(val color: Int, val share: Float)

    /**
     * @param pixels the cover as ARGB, [width] pixels wide
     * @param edgeFrom first column of the strip that meets the spine
     * @param edgeTo column after its last
     */
    fun pick(pixels: IntArray, width: Int, edgeFrom: Int, edgeTo: Int): Colors {
        val whole = clusters(pixels, width, 0, width)
        val strip = clusters(pixels, width, edgeFrom, edgeTo)
        if (whole.isEmpty()) return Colors(DEFAULT_BACKGROUND, LIGHT_INK)
        // A plain edge is the cover's background: it goes on round the spine. A busy edge (part of
        // the picture) would look random there, so the cover's main colour is used instead.
        val background = strip.firstOrNull()?.takeIf { it.share >= PLAIN_EDGE }?.color
            ?: whole.maxBy { it.share * (0.5f + saturation(it.color)) }.color
        val ink = whole
            .filter { it.share >= 0.015f && contrast(it.color, background) >= 3f }
            .maxByOrNull { contrast(it.color, background) * (0.75f + 0.5f * saturation(it.color)) }
            ?.color
            ?: if (luminance(background) < 0.4f) LIGHT_INK else DARK_INK
        return Colors(background, ink)
    }

    /** The colours of the columns [from] until [to], largest groups first. */
    fun clusters(pixels: IntArray, width: Int, from: Int, to: Int): List<Cluster> {
        if (width <= 0 || pixels.isEmpty()) return emptyList()
        val height = pixels.size / width
        val count = IntArray(4096)
        val red = LongArray(4096)
        val green = LongArray(4096)
        val blue = LongArray(4096)
        var total = 0
        for (y in 0 until height) {
            for (x in max(0, from) until min(width, to)) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val bin = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
                count[bin]++
                red[bin] += r.toLong()
                green[bin] += g.toLong()
                blue[bin] += b.toLong()
                total++
            }
        }
        if (total == 0) return emptyList()
        // Neighbouring bins join the biggest group close enough to them.
        class Group(var r: Long, var g: Long, var b: Long, var n: Int) {
            fun color() = rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        val groups = ArrayList<Group>()
        for (bin in (0 until 4096).filter { count[it] > 0 }.sortedByDescending { count[it] }) {
            val n = count[bin]
            val c = rgb((red[bin] / n).toInt(), (green[bin] / n).toInt(), (blue[bin] / n).toInt())
            val home = groups.firstOrNull { distance(it.color(), c) < MERGE_DISTANCE }
            if (home != null) {
                home.r += red[bin]
                home.g += green[bin]
                home.b += blue[bin]
                home.n += n
            } else if (groups.size < MAX_GROUPS) {
                groups += Group(red[bin], green[bin], blue[bin], n)
            }
        }
        return groups.sortedByDescending { it.n }.map { Cluster(it.color(), it.n.toFloat() / total) }
    }

    /** Darker and a little greyer: the colour of the cloth on a hardcover's spine. */
    fun cloth(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val grey = (r * 0.3f + g * 0.59f + b * 0.11f)
        fun mix(c: Int) = ((c + (grey - c) * 0.2f) * 0.72f).toInt().coerceIn(0, 255)
        return rgb(mix(r), mix(g), mix(b))
    }

    /** WCAG contrast ratio of two colours, 1 (same) to 21 (black on white). */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** Relative luminance (linear light), 0 to 1. */
    fun luminance(color: Int): Float {
        fun channel(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        return 0.2126f * channel((color shr 16) and 0xFF) + 0.7152f * channel((color shr 8) and 0xFF) + 0.0722f * channel(color and 0xFF)
    }

    fun saturation(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        return if (hi == 0) 0f else (hi - lo).toFloat() / hi
    }

    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun distance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private const val MERGE_DISTANCE = 42f
    private const val MAX_GROUPS = 12

    /** Share of the edge one colour must cover for the edge to count as plain background. */
    private const val PLAIN_EDGE = 0.45f
    private val DEFAULT_BACKGROUND = rgb(60, 50, 44)
    val LIGHT_INK = rgb(240, 234, 222)
    val DARK_INK = rgb(28, 22, 18)
}
