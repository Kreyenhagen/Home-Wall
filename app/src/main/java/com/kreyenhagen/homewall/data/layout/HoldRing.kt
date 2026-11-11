package com.kreyenhagen.homewall.data.layout

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A hold's ring (§7.3): an oval [radiusXIn] across half its width and [radiusYIn] across half its
 * height, turned [angleDeg] clockwise as seen on the wall. Equal radii make a circle.
 */
data class RingShape(val radiusXIn: Float, val radiusYIn: Float, val angleDeg: Float = 0f) {
    val widthIn: Float get() = radiusXIn * 2f
    val heightIn: Float get() = radiusYIn * 2f
    val isCircle: Boolean get() = abs(radiusXIn - radiusYIn) < 0.001f

    /** Kept to the editor's ranges and rounded: radii to 0.05 in (widths to 0.1 in), the angle to whole degrees in [0, 360). */
    fun tidied(): RingShape = RingShape(
        radiusXIn = tidyRadius(radiusXIn),
        radiusYIn = tidyRadius(radiusYIn),
        angleDeg = tidyAngle(angleDeg),
    )

    companion object {
        /** Ring widths and heights the editor allows, in inches. */
        val SIZE_RANGE_IN = 1f..36f

        fun circle(radiusIn: Float) = RingShape(radiusIn, radiusIn)

        private fun tidyRadius(r: Float): Float {
            val clamped = (if (r.isNaN()) SIZE_RANGE_IN.start / 2f else r).coerceIn(SIZE_RANGE_IN.start / 2f, SIZE_RANGE_IN.endInclusive / 2f)
            return (clamped * 20f).roundToInt() / 20f
        }

        private fun tidyAngle(a: Float): Float {
            if (a.isNaN() || a.isInfinite()) return 0f
            val whole = a.roundToInt() % 360
            return (if (whole < 0) whole + 360 else whole).toFloat()
        }
    }
}

/** The ring this hold draws: its own size if it has one, otherwise a circle of [defaultRadiusIn] (Settings → Ring size). */
fun Hold.ringShape(defaultRadiusIn: Float): RingShape {
    val rx = ringIn ?: defaultRadiusIn
    return RingShape(rx, ringYIn ?: rx, ringAngle ?: 0f)
}

/** True if the hold has its own ring size or shape rather than the default from Settings. */
val Hold.hasOwnRing: Boolean get() = ringIn != null || ringYIn != null || ringAngle != null

/**
 * This hold with [shape] as its own ring, or back to the default if [shape] is null. A circle is
 * stored as just `ringIn`, and an angle of 0 isn't stored, which keeps the JSON tidy.
 */
fun Hold.withRing(shape: RingShape?): Hold {
    if (shape == null) return copy(ringIn = null, ringYIn = null, ringAngle = null)
    val s = shape.tidied()
    return copy(
        ringIn = s.radiusXIn,
        ringYIn = if (s.isCircle) null else s.radiusYIn,
        ringAngle = if (s.isCircle || s.angleDeg == 0f) null else s.angleDeg,
    )
}

/** Wall Setup → Rings (§12.4): gives hold [holdId] its own ring, or the default one if [shape] is null. */
fun WallLayout.withHoldRing(holdId: Int, shape: RingShape?): WallLayout =
    copy(holds = holds.map { if (it.id == holdId) it.withRing(shape) else it })

/**
 * Wall Setup → Rings (§12.4): the hold with [hold]'s id gets [hold]'s ring and position (kept on
 * the wall). The ring's center is the hold's position, so moving one moves the other. Its LEDs
 * don't change.
 */
fun WallLayout.withRingAndPositionOf(hold: Hold): WallLayout =
    copy(holds = holds.map { if (it.id == hold.id) it.copy(ringIn = hold.ringIn, ringYIn = hold.ringYIn, ringAngle = hold.ringAngle) else it })
        .withHoldMoved(hold.id, hold.x, hold.y)
