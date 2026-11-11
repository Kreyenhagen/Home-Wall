package com.kreyenhagen.homewall.ui.wall

import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.RingShape
import com.kreyenhagen.homewall.data.layout.ringShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Hit testing for taps on the wall (§7.4). Works in wall inches, so it's independent of zoom. */
object HoldHitTest {

    /** The median distance from each hold to its nearest neighbor, in inches; null with fewer than 2 holds. */
    fun medianNearestNeighborIn(holds: List<Hold>): Float? {
        if (holds.size < 2) return null
        val nearest = holds.mapIndexed { i, a ->
            var best = Float.MAX_VALUE
            holds.forEachIndexed { j, b ->
                if (i != j) best = minOf(best, hypot(a.x - b.x, a.y - b.y))
            }
            best
        }.sorted()
        val mid = nearest.size / 2
        return if (nearest.size % 2 == 1) nearest[mid] else (nearest[mid - 1] + nearest[mid]) / 2f
    }

    /**
     * About half the median hold spacing, but never less than [minRadiusPx] on screen, and never
     * more than [maxRadiusIn] (Map LEDs caps it, so a tap that isn't near a hold makes a new one).
     */
    fun hitRadiusIn(
        medianNearestNeighborIn: Float?,
        scalePxPerIn: Float,
        minRadiusPx: Float,
        maxRadiusIn: Float = Float.POSITIVE_INFINITY,
    ): Float =
        minOf(max((medianNearestNeighborIn ?: 0f) / 2f, minRadiusPx / scalePxPerIn), maxRadiusIn)

    /** The hold nearest to [point] within [radiusIn], or null if none is in range. */
    fun holdAt(holds: List<Hold>, point: WallPoint, radiusIn: Float): Hold? {
        var best: Hold? = null
        var bestDistance = radiusIn
        for (hold in holds) {
            val d = hypot(hold.x - point.x, hold.y - point.y)
            if (d <= bestDistance) {
                best = hold
                bestDistance = d
            }
        }
        return best
    }

    /**
     * The hold whose ring contains [point] (the Route Editor, §7.4), whether or not the ring is
     * drawn. Each ring is the hold's own size, or a circle of [defaultRingIn] (Settings → Ring size).
     * A ring under [minRadiusIn] across either axis is that big for tapping, so small rings stay
     * easy to hit when zoomed out. Where rings overlap the smaller ring wins, so a small hold stays
     * fully tappable even inside a big one; equal rings go to the nearer center. Null outside every ring.
     */
    fun ringAt(holds: List<Hold>, point: WallPoint, defaultRingIn: Float, minRadiusIn: Float): Hold? {
        var best: Hold? = null
        var bestArea = Float.POSITIVE_INFINITY
        var bestDistance = Float.POSITIVE_INFINITY
        for (hold in holds) {
            val shape = hold.ringShape(defaultRingIn)
            if (!ringContains(shape, hold.x, hold.y, point, minRadiusIn)) continue
            // Ranked by the ring's own size, not its tapping size, so "smaller wins" matches what's drawn.
            val area = shape.radiusXIn * shape.radiusYIn
            val distance = hypot(hold.x - point.x, hold.y - point.y)
            val sameSize = abs(area - bestArea) < AREA_TIE_IN2
            if ((!sameSize && area < bestArea) || (sameSize && distance < bestDistance)) {
                best = hold
                bestArea = area
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * True if [point] is inside [shape] centered at ([cx], [cy]), with each half-axis at least
     * [minRadiusIn]. All in wall inches (y up), on the same axes as [RingGeometry]: the width runs
     * along (cos a, −sin a) and the height along (sin a, cos a), for a clockwise angle a.
     */
    fun ringContains(shape: RingShape, cx: Float, cy: Float, point: WallPoint, minRadiusIn: Float = 0f): Boolean {
        val dx = point.x - cx
        val dy = point.y - cy
        val a = Math.toRadians(shape.angleDeg.toDouble())
        val sinA = sin(a).toFloat()
        val cosA = cos(a).toFloat()
        val u = (dx * cosA - dy * sinA) / max(shape.radiusXIn, minRadiusIn)
        val v = (dx * sinA + dy * cosA) / max(shape.radiusYIn, minRadiusIn)
        return u * u + v * v <= 1f
    }

    /** Ring areas (half-width × half-height) closer than this, in square inches, count as the same size. */
    private const val AREA_TIE_IN2 = 0.001f
}
