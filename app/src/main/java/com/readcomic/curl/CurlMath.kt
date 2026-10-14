package com.readcomic.curl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** Axis-aligned rectangle in view pixels (y grows downwards). */
data class PageRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

/**
 * Fold of a sheet around a cylinder. [axisX]/[axisY] is any point of the fold line,
 * ([nx], [ny]) the unit normal pointing to the lifted part of the sheet.
 */
data class CurlGeometry(
    val axisX: Float,
    val axisY: Float,
    val nx: Float,
    val ny: Float,
    val radius: Float,
)

/**
 * Geometry of a page that is being turned. The page is attached to the spine at its left
 * edge; the point [grabX]/[grabY] of the page is dragged to the point (fx, fy).
 */
object CurlMath {
    private const val MAX_TILT = (40.0 * PI / 180.0).toFloat()
    private const val MIN_TILT = (12.0 * PI / 180.0).toFloat()

    /** Vertical finger movement only partially rotates the fold. */
    private const val TILT_DAMPING = 0.6f

    /** Curl radius grows with the drag distance so the first touch lifts the edge crisply. */
    fun radiusFor(dragDistance: Float, pageWidth: Float): Float =
        min(pageWidth * 0.15f, pageWidth * 0.05f + 0.1f * dragDistance)

    /**
     * How far from the fold line the dragged point has to be (measured along the page),
     * so that after wrapping around the cylinder it is [distance] away from where it started.
     */
    fun liftedLength(distance: Float, radius: Float): Float {
        val halfTurn = (PI * radius).toFloat()
        if (distance >= halfTurn) return (distance + halfTurn) / 2f
        // Still on the cylinder: solve s - R sin(s / R) = distance (monotonic in s).
        var lo = 0f
        var hi = halfTurn
        repeat(40) {
            val mid = (lo + hi) / 2f
            if (mid - radius * sin(mid / radius) < distance) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    fun compute(grabX: Float, grabY: Float, fx: Float, fy: Float, page: PageRect): CurlGeometry? {
        val vx = grabX - fx
        val vy = grabY - fy
        if (vx <= 0.5f) return null

        // Corners may be tilted a lot, the middle of the edge only slightly, and never "against" the corner.
        val toTop = (grabY - page.top) / page.height
        val toBottom = (page.bottom - grabY) / page.height
        val bottomness = (1f - toBottom / 0.3f).coerceIn(0f, 1f)
        val topness = (1f - toTop / 0.3f).coerceIn(0f, 1f)
        val maxDown = MIN_TILT + (MAX_TILT - MIN_TILT) * bottomness
        val maxUp = MIN_TILT + (MAX_TILT - MIN_TILT) * topness
        var phi = atan2(vy * TILT_DAMPING, vx).coerceIn(-maxUp, maxDown)

        val width = page.width
        fun along(p: Float) = vx * cos(p) + vy * sin(p)

        // Straighten the fold gradually as it approaches the spine, so it never snaps.
        val travel = grabX - page.left
        val approach = along(phi).let { d -> liftedLength(max(d, 0f), radiusFor(d, width)) }
        phi *= ((travel - approach) / (travel * 0.4f)).coerceIn(0f, 1f)
        fun keepsSpine(p: Float): Boolean {
            val d = max(along(p), 0f)
            val lifted = liftedLength(d, radiusFor(d, width))
            val c = cos(p)
            val s = sin(p)
            return (grabX - page.left) * c + (grabY - page.top) * s >= lifted &&
                (grabX - page.left) * c + (grabY - page.bottom) * s >= lifted
        }
        // The spine must stay on the flat side of the fold, otherwise the page would tear off.
        if (!keepsSpine(phi)) {
            if (!keepsSpine(0f)) {
                phi = 0f
            } else {
                val sg = sign(phi)
                var lo = 0f
                var hi = abs(phi)
                repeat(24) {
                    val mid = (lo + hi) / 2f
                    if (keepsSpine(sg * mid)) lo = mid else hi = mid
                }
                phi = sg * lo
            }
        }

        val distance = along(phi)
        if (distance < 0.5f) return null
        val radius = radiusFor(distance, width)
        val lifted = liftedLength(distance, radius)
        val nx = cos(phi)
        val ny = sin(phi)
        return CurlGeometry(grabX - nx * lifted, grabY - ny * lifted, nx, ny, radius)
    }

    /**
     * Where the grabbed point has to be dragged so that the whole sheet is flipped over
     * and lies outside of the screen (to the left of [screenLeft]).
     */
    fun hiddenX(grabX: Float, page: PageRect, screenLeft: Float): Float {
        // Use the largest possible radius: being further away is fine, being too close is not.
        val halfTurn = (PI * page.width * 0.15f).toFloat()
        // Extra room so the animation can be cut a little before it fully settles.
        val limit = min(page.left, screenLeft) - page.width * 0.25f
        // The flipped sheet ends at 2 * axis - left + PI * R; it must be left of the limit.
        val axis = min((limit + page.left - halfTurn) / 2f, page.left - halfTurn) - 2f
        val lifted = grabX - axis
        val distance = 2f * lifted - halfTurn
        return grabX - distance
    }

    /** Where a point of the flat page ends up; the same math as curlDeform() in curl_common.glsl. */
    fun deform(x: Float, y: Float, curl: CurlGeometry): Triple<Float, Float, Float> {
        val s = (x - curl.axisX) * curl.nx + (y - curl.axisY) * curl.ny
        if (s <= 0f) return Triple(x, y, 0f)
        val baseX = x - s * curl.nx
        val baseY = y - s * curl.ny
        val r = curl.radius
        val theta = s / r
        return if (theta < PI) {
            Triple(baseX + curl.nx * r * sin(theta), baseY + curl.ny * r * sin(theta), r * (1f - cos(theta)))
        } else {
            val back = s - (PI * r).toFloat()
            Triple(baseX - curl.nx * back, baseY - curl.ny * back, 2f * r)
        }
    }

    fun distance(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)
}
