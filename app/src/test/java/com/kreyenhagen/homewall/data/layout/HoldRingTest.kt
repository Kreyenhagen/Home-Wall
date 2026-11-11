package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldRingTest {

    private val hold = Hold(7, 20f, 30f, listOf(7, 8))

    @Test
    fun aHoldWithoutItsOwnRingUsesTheDefaultCircle() {
        assertFalse(hold.hasOwnRing)
        assertEquals(RingShape(3.5f, 3.5f, 0f), hold.ringShape(3.5f))
        assertTrue(hold.ringShape(3.5f).isCircle)
    }

    @Test
    fun ringInAloneIsACircleAndTheOvalFieldsMakeAnOval() {
        assertEquals(RingShape(2.5f, 2.5f, 0f), hold.copy(ringIn = 2.5f).ringShape(3.5f))
        val oval = hold.copy(ringIn = 4f, ringYIn = 2f, ringAngle = 30f)
        assertTrue(oval.hasOwnRing)
        assertEquals(RingShape(4f, 2f, 30f), oval.ringShape(3.5f))
        assertEquals(8f, oval.ringShape(3.5f).widthIn)
        assertEquals(4f, oval.ringShape(3.5f).heightIn)
        // Only a height, e.g. from a hand-edited file: the width comes from the default.
        assertEquals(RingShape(3.5f, 2f, 0f), hold.copy(ringYIn = 2f).ringShape(3.5f))
    }

    @Test
    fun aCircleIsStoredAsJustRingIn() {
        val circle = hold.withRing(RingShape(3f, 3f, 45f))
        assertEquals(3f, circle.ringIn)
        assertNull(circle.ringYIn)
        assertNull(circle.ringAngle) // rotating a circle changes nothing
    }

    @Test
    fun anOvalStoresItsHeightAndAngleButNotAnAngleOfZero() {
        val oval = hold.withRing(RingShape(4f, 2f, 30f))
        assertEquals(4f, oval.ringIn)
        assertEquals(2f, oval.ringYIn)
        assertEquals(30f, oval.ringAngle)
        assertNull(hold.withRing(RingShape(4f, 2f, 0f)).ringAngle)
        assertNull(hold.withRing(RingShape(4f, 2f, 360f)).ringAngle)
    }

    @Test
    fun nullGoesBackToTheDefaultRing() {
        val back = hold.copy(ringIn = 4f, ringYIn = 2f, ringAngle = 30f).withRing(null)
        assertEquals(hold, back)
        assertFalse(back.hasOwnRing)
    }

    @Test
    fun shapesAreKeptInRangeAndRounded() {
        // Widths 1–36 in, so radii 0.5–18, rounded to 0.05 in.
        assertEquals(RingShape(0.5f, 18f, 0f), RingShape(0.1f, 100f).tidied())
        assertEquals(RingShape(2.45f, 3f, 0f), RingShape(2.4499f, 3.01f).tidied())
        // Whole degrees in [0, 360).
        assertEquals(330f, RingShape(2f, 3f, -30f).tidied().angleDeg)
        assertEquals(5f, RingShape(2f, 3f, 725.2f).tidied().angleDeg)
        assertEquals(0f, RingShape(2f, 3f, Float.NaN).tidied().angleDeg)
    }

    @Test
    fun onlyTheChosenHoldGetsTheRing() {
        val layout = layoutOf(Hold(0, 10f, 5f, listOf(0)), hold, ledCount = 10)
        val changed = layout.withHoldRing(7, RingShape(4f, 2f, 30f))
        assertEquals(layout.holds[0], changed.holds[0])
        assertEquals(RingShape(4f, 2f, 30f), changed.holds[1].ringShape(3.5f))
        assertEquals(layout, changed.withHoldRing(7, null))
        assertEquals(layout, layout.withHoldRing(99, RingShape.circle(2f)))
    }

    @Test
    fun aRingAndItsHoldMoveTogether() {
        val layout = layoutOf(Hold(0, 10f, 5f, listOf(0)), hold, ledCount = 10)
        // Only the ring fields and the position come from the given hold; the LEDs stay as saved.
        val moved = layout.withRingAndPositionOf(hold.copy(x = 25f, y = 33f, leds = listOf(9)).withRing(RingShape(4f, 2f, 30f)))
        assertEquals(Hold(7, 25f, 33f, listOf(7, 8), ringIn = 4f, ringYIn = 2f, ringAngle = 30f), moved.holds[1])
        assertEquals(layout.holds[0], moved.holds[0])
        // Kept on the wall.
        val off = layout.withRingAndPositionOf(hold.copy(x = 200f, y = -3f))
        assertEquals(layout.wall.widthIn, off.holds[1].x)
        assertEquals(0f, off.holds[1].y)
    }

    @Test
    fun ovalRingsRoundTripThroughJson() {
        val layout = layoutOf(hold.withRing(RingShape(4f, 2f, 30f)), Hold(0, 10f, 5f, listOf(0), ringIn = 2.5f), ledCount = 10)
        val text = LayoutJson.encode(layout)
        assertTrue(text.lines().contains("""    {"id":7,"x":20.0,"y":30.0,"leds":[7,8],"ringIn":4.0,"ringYIn":2.0,"ringAngle":30.0},"""))
        assertTrue(text.lines().contains("""    {"id":0,"x":10.0,"y":5.0,"leds":[0],"ringIn":2.5}"""))
        assertEquals(layout, (LayoutJson.parse(text) as LayoutJson.ParseResult.Ok).layout)
    }

    @Test
    fun badRingFieldsAreReported() {
        val problems = LayoutValidator.validate(
            layoutOf(
                Hold(0, 10f, 5f, listOf(0), ringIn = 0f),
                Hold(1, 10f, 9f, listOf(1), ringYIn = -2f),
                Hold(2, 10f, 13f, listOf(2), ringAngle = Float.NaN),
                Hold(3, 10f, 20f, listOf(3), ringIn = 3f, ringYIn = 2f, ringAngle = -45f),
                ledCount = 10,
            ),
        )
        assertEquals(
            listOf(
                "Hold 0: ringIn must be positive (got 0.0).",
                "Hold 1: ringYIn must be positive (got -2.0).",
                "Hold 2: ringAngle must be a number of degrees (got NaN).",
            ),
            problems,
        )
    }
}
