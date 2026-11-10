package com.kreyenhagen.homewall.ui.wall

import com.kreyenhagen.homewall.data.layout.Hold
import kotlin.math.hypot
import kotlin.math.max

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

    /** About half the median hold spacing, but never less than [minRadiusPx] on screen. */
    fun hitRadiusIn(medianNearestNeighborIn: Float?, scalePxPerIn: Float, minRadiusPx: Float): Float =
        max((medianNearestNeighborIn ?: 0f) / 2f, minRadiusPx / scalePxPerIn)

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
}
