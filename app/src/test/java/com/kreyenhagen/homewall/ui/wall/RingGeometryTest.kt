package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.geometry.Offset
import com.kreyenhagen.homewall.data.layout.RingShape
import org.junit.Assert.assertEquals
import org.junit.Test

class RingGeometryTest {

    private val cx = 40f
    private val cy = 60f
    private val oval = RingShape(4f, 2f, 0f)

    private fun drag(shape: RingShape, handle: RingHandle, x: Float, y: Float) =
        RingGeometry.dragged(shape, cx, cy, handle, WallPoint(cx + x, cy + y))

    @Test
    fun widthAndHeightFollowTheirOwnAxisOnly() {
        assertEquals(RingShape(6f, 2f, 0f), drag(oval, RingHandle.WIDTH, 6f, 1.5f))
        assertEquals(RingShape(6f, 2f, 0f), drag(oval, RingHandle.WIDTH, -6f, 0f)) // past the center: still a width
        assertEquals(RingShape(4f, 5f, 0f), drag(oval, RingHandle.HEIGHT, 2f, 5f))
    }

    @Test
    fun theCenterHandleSitsOnTheCenterAndMovesRatherThanReshapes() {
        val center = Offset(500f, 800f)
        assertEquals(center, RingGeometry.handlePositions(center, 40f, 20f, 30f, 40f)[RingHandle.CENTER])
        assertEquals(oval, drag(oval, RingHandle.CENTER, 7f, -3f))
    }

    @Test
    fun sizesStayInRange() {
        assertEquals(0.5f, drag(oval, RingHandle.WIDTH, 0f, 0f).radiusXIn)
        assertEquals(18f, drag(oval, RingHandle.HEIGHT, 0f, 50f).radiusYIn)
    }

    @Test
    fun rotatingPointsTheHeightAtTheHandleClockwise() {
        assertEquals(0f, drag(oval, RingHandle.ROTATE, 0f, 5f).angleDeg) // straight up
        assertEquals(90f, drag(oval, RingHandle.ROTATE, 5f, 0f).angleDeg) // to the right
        assertEquals(180f, drag(oval, RingHandle.ROTATE, 0f, -5f).angleDeg)
        assertEquals(270f, drag(oval, RingHandle.ROTATE, -5f, 0f).angleDeg)
        assertEquals(45f, drag(oval, RingHandle.ROTATE, 3f, 3f).angleDeg)
        assertEquals(oval, drag(oval, RingHandle.ROTATE, 0f, 0f)) // on the center: no direction, no change
    }

    @Test
    fun aRotatedRingIsSizedAlongItsOwnAxes() {
        // Turned 90° clockwise, the width runs down the wall and the height to the right.
        val turned = oval.copy(angleDeg = 90f)
        assertEquals(RingShape(6f, 2f, 90f), drag(turned, RingHandle.WIDTH, 0f, -6f))
        assertEquals(RingShape(4f, 3f, 90f), drag(turned, RingHandle.HEIGHT, 3f, 0f))
    }

    /** Each handle, dragged nowhere, leaves the ring as it is: the drawing and the math agree. */
    @Test
    fun handlesSitWhereDraggingThemExpects() {
        val scale = 10f // px per inch
        val center = Offset(500f, 800f)
        fun toWall(p: Offset) = WallPoint(cx + (p.x - center.x) / scale, cy - (p.y - center.y) / scale)

        for (angle in listOf(0f, 30f, 90f, 135f, 250f)) {
            val shape = RingShape(4f, 2f, angle)
            val spots = RingGeometry.handlePositions(center, shape.radiusXIn * scale, shape.radiusYIn * scale, angle, rotateGapPx = 40f)
            for ((handle, spot) in spots) {
                val after = RingGeometry.dragged(shape, cx, cy, handle, toWall(spot))
                assertEquals("$handle at $angle°", shape.radiusXIn, after.radiusXIn, 0.051f)
                assertEquals("$handle at $angle°", shape.radiusYIn, after.radiusYIn, 0.051f)
                assertEquals("$handle at $angle°", angle, after.angleDeg, 0.5f)
            }
        }
    }
}
