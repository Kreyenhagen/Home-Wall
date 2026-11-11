package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.geometry.Offset
import com.kreyenhagen.homewall.data.layout.RingShape
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The handles drawn on the selected ring in Wall Setup → Rings (§12.4). */
enum class RingHandle {
    /**
     * On the ring's center, which is the hold's position: drag it to move the ring and the hold
     * together, so the hold's tile in Map LEDs and Review moves too.
     */
    CENTER,

    /** On the end of the ring's width: drag along it to change the width. */
    WIDTH,

    /** On the top of the ring's height: drag along it to change the height. */
    HEIGHT,

    /** Past the height handle: drag around the ring to rotate it. */
    ROTATE,
}

/**
 * Dragging the handles of one hold's ring (Wall Setup → Rings, §12.4). [onDrag] gets the handle
 * and where it is now, in wall inches; [onDragEnd] fires when the finger lifts, and [onCancel] if
 * the drag was interrupted some other way, such as a second finger starting a pinch.
 */
class RingHandleActions(
    val holdId: Int,
    val onDrag: (RingHandle, WallPoint) -> Unit,
    val onDragEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/** The math behind the ring handles. Angles are degrees clockwise as seen on the wall. */
object RingGeometry {

    /**
     * [shape] after its [handle] was dragged to [point], for a ring centered at ([cx], [cy]). All
     * in wall inches (y up). Width and height follow the handle along their own axis, so the other
     * one doesn't change; rotating points the ring's height at the handle. The [RingHandle.CENTER]
     * handle moves the ring instead of changing its shape, so it leaves [shape] as it is.
     */
    fun dragged(shape: RingShape, cx: Float, cy: Float, handle: RingHandle, point: WallPoint): RingShape {
        val dx = point.x - cx
        val dy = point.y - cy
        val a = Math.toRadians(shape.angleDeg.toDouble())
        val sinA = sin(a).toFloat()
        val cosA = cos(a).toFloat()
        val next = when (handle) {
            RingHandle.CENTER -> return shape
            // The width axis is (cos a, −sin a) and the height axis (sin a, cos a) on the wall.
            RingHandle.WIDTH -> shape.copy(radiusXIn = abs(dx * cosA - dy * sinA))
            RingHandle.HEIGHT -> shape.copy(radiusYIn = abs(dx * sinA + dy * cosA))
            RingHandle.ROTATE -> {
                if (dx == 0f && dy == 0f) return shape
                shape.copy(angleDeg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat())
            }
        }
        return next.tidied()
    }

    /**
     * Where the handles sit on screen (y down) for a ring centered at [center] that is [rxPx] by
     * [ryPx] across half its width and height. The rotate handle is [rotateGapPx] past the height one.
     */
    fun handlePositions(center: Offset, rxPx: Float, ryPx: Float, angleDeg: Float, rotateGapPx: Float): Map<RingHandle, Offset> {
        val a = Math.toRadians(angleDeg.toDouble())
        val sinA = sin(a).toFloat()
        val cosA = cos(a).toFloat()
        // On screen the width axis is (cos a, sin a) and the height axis, pointing up, (sin a, −cos a).
        val width = Offset(cosA, sinA)
        val up = Offset(sinA, -cosA)
        return mapOf(
            RingHandle.CENTER to center,
            RingHandle.WIDTH to center + width * rxPx,
            RingHandle.HEIGHT to center + up * ryPx,
            RingHandle.ROTATE to center + up * (ryPx + rotateGapPx),
        )
    }
}
