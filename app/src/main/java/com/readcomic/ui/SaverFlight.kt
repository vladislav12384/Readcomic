package com.readcomic.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Where the camera of the screensaver looks: the centre ([x], [y]), its [scale] and [rotation] (degrees). */
data class SaverCamera(val x: Float, val y: Float, val scale: Float, val rotation: Float)

/** A page lying on the screensaver's table: its centre, size and turn (degrees), world units. */
data class SaverTile(val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float)

/**
 * The screensaver's flight over pages strewn on a table: a grid of [cells] places, filled from
 * the middle outwards, and a camera that glides from one detail of a page to the next, pulling
 * back on the way like a crane shot, and lingers on each with a slow push in.
 */
class SaverFlight(private val random: Random, val cells: Int) {
    val columns = max(2, sqrt(cells.toFloat()).toInt())
    private val rows = (cells + columns - 1) / columns

    /** The places in the order they are filled: the middle of the table first. */
    private val order: List<Int> = (0 until columns * rows).sortedBy { i ->
        val dx = i % columns - (columns - 1) / 2f
        val dy = i / columns - (rows - 1) / 2f
        dx * dx + dy * dy * 0.7f + random.nextFloat() * 0.3f
    }

    // A little disorder for every place: where in it the page lies, and how turned.
    private val jitter = List(columns * rows) {
        Triple((random.nextFloat() - 0.5f) * 0.14f, (random.nextFloat() - 0.5f) * 0.1f, (random.nextFloat() - 0.5f) * 7f)
    }

    /** Where the [k]th page that arrived lies, a page of [aspect] (width / height). */
    fun tile(k: Int, aspect: Float): SaverTile {
        val cell = order[k % order.size]
        val (jx, jy, turn) = jitter[cell]
        val column = cell % columns
        val row = cell / columns
        // Fitted into its place, a wide page lying across.
        val a = aspect.coerceIn(0.4f, 2.2f)
        val height = if (a > CELL_W / CELL_H) CELL_W * 0.94f / a else CELL_H * 0.94f
        val width = height * a
        return SaverTile(
            x = (column - (columns - 1) / 2f + jx) * CELL_W * 1.04f,
            y = (row - (rows - 1) / 2f + jy) * CELL_H * 1.03f,
            width = width,
            height = height,
            rotation = turn,
        )
    }

    /** A part of a page to show, the camera filling the screen with it. */
    private class Shot(val tile: Int, val camera: SaverCamera, val width: Float)

    private fun shot(tiles: List<SaverTile>, screenWidth: Float, screenHeight: Float, avoid: Int): Shot {
        val choices = tiles.indices.filter { it != avoid }.ifEmpty { tiles.indices.toList() }
        val index = choices[random.nextInt(choices.size)]
        val t = tiles[index]
        val screenAspect = screenWidth / screenHeight
        // A part of the page as wide as 40-75 % of it, no taller than the page.
        var w = t.width * (0.4f + 0.35f * random.nextFloat())
        w = min(w, t.height * 0.9f * screenAspect)
        val h = w / screenAspect
        // Somewhere inside the page, in its own turned frame.
        val u = (random.nextFloat() - 0.5f) * (t.width - w)
        val v = (random.nextFloat() - 0.5f) * (t.height - h)
        val r = Math.toRadians(t.rotation.toDouble())
        val x = t.x + (u * cos(r) - v * sin(r)).toFloat()
        val y = t.y + (u * sin(r) + v * cos(r)).toFloat()
        return Shot(index, SaverCamera(x, y, screenWidth / w, t.rotation), w)
    }

    private var from: SaverCamera? = null
    private var to: Shot? = null
    private var segmentStart = 0f
    private var glide = 0f
    private var linger = 0f
    private var drift = 0f to 0f

    /**
     * The camera at [time] seconds, over the pages [tiles] that have arrived so far, for a screen
     * of that size.
     */
    fun camera(time: Float, tiles: List<SaverTile>, screenWidth: Float, screenHeight: Float): SaverCamera {
        if (tiles.isEmpty()) return SaverCamera(0f, 0f, screenWidth / (CELL_W * 2.5f), 0f)
        var target = to
        if (target == null) {
            // An opening shot over the table, then down to the first detail.
            from = SaverCamera(0f, 0f, screenWidth / (CELL_W * 2.2f), 0f)
            target = next(tiles, screenWidth, screenHeight, avoid = -1, start = time)
        }
        val camera = cameraIn(time)
        if (time >= segmentStart + glide + linger) {
            from = camera
            next(tiles, screenWidth, screenHeight, avoid = target.tile, start = time)
            return cameraIn(time)
        }
        return camera
    }

    /** The page the camera is on (or flying to). */
    val currentTile: Int get() = to?.tile ?: -1

    /** How far into its stay over the current page the camera is, 0..1; 0 while flying. */
    fun lingering(time: Float): Float = if (to == null || time < segmentStart + glide) 0f else ((time - segmentStart - glide) / linger).coerceIn(0f, 1f)

    private fun next(tiles: List<SaverTile>, screenWidth: Float, screenHeight: Float, avoid: Int, start: Float): Shot {
        val shot = shot(tiles, screenWidth, screenHeight, avoid)
        val a = from!!
        val distance = hypot(shot.camera.x - a.x, shot.camera.y - a.y) / CELL_W
        to = shot
        segmentStart = start
        glide = GLIDE + GLIDE_PER_CELL * min(distance, 2.5f)
        linger = LINGER
        val angle = random.nextFloat() * 2f * PI.toFloat()
        drift = cos(angle) * 0.03f to sin(angle) * 0.03f
        return shot
    }

    private fun cameraIn(time: Float): SaverCamera {
        val a = from!!
        val shot = to!!
        val b = shot.camera
        val t = time - segmentStart
        if (t < glide) {
            val u = (t / glide).coerceIn(0f, 1f)
            // Smootherstep: it sets off and arrives without a jolt.
            val e = u * u * u * (u * (u * 6f - 15f) + 10f)
            val distance = hypot(b.x - a.x, b.y - a.y) / CELL_W
            // Pulls back half way, more the further it flies, and dives in again.
            val pull = 1f + (0.25f + 0.45f * min(distance, 2f)) * sin(PI.toFloat() * u)
            val scale = exp(ln(a.scale) + (ln(b.scale) - ln(a.scale)) * e) / pull
            return SaverCamera(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e, scale, a.rotation + (b.rotation - a.rotation) * e)
        }
        // Over the page: a slow push in and a drift.
        val v = ((t - glide) / linger).coerceIn(0f, 1f)
        val k = v * v * (3f - 2f * v)
        return SaverCamera(b.x + drift.first * shot.width * k, b.y + drift.second * shot.width * k, b.scale * (1f + PUSH * k), b.rotation)
    }

    companion object {
        const val CELL_W = 600f
        const val CELL_H = 900f
        const val GLIDE = 2.6f
        const val GLIDE_PER_CELL = 0.7f
        const val LINGER = 3.2f
        const val PUSH = 0.07f
    }
}
