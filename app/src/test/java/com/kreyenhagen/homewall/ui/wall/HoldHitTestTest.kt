package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.geometry.Offset
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HoldHitTestTest {

    private val layout = PlaceholderLayout.create()
    private val minRadiusPx = 36f // 24 dp at the tablet's 1.5× density

    @Test
    fun placeholderMedianSpacingIsEightInches() {
        // 22 kicker holds are 7 in from their neighbor, the other 132 are 8 in.
        assertEquals(8f, HoldHitTest.medianNearestNeighborIn(layout.holds) ?: 0f, 1e-4f)
        assertNull(HoldHitTest.medianNearestNeighborIn(layout.holds.take(1)))
    }

    @Test
    fun radiusIsHalfTheSpacingWithAMinimumOnScreen() {
        assertEquals(4f, HoldHitTest.hitRadiusIn(8f, scalePxPerIn = 16f, minRadiusPx = 36f), 1e-4f)
        // Zoomed far out (or sparse holds), the 24 dp minimum wins.
        assertEquals(6f, HoldHitTest.hitRadiusIn(8f, scalePxPerIn = 6f, minRadiusPx = 36f), 1e-4f)
        assertEquals(3f, HoldHitTest.hitRadiusIn(null, scalePxPerIn = 12f, minRadiusPx = 36f), 1e-4f)
    }

    @Test
    fun tapsHitTheRightHoldAtEveryZoomLevel() {
        val spacing = HoldHitTest.medianNearestNeighborIn(layout.holds)
        val base = WallTransform(96f, 114f, viewWidth = 1600f, viewHeight = 2200f)
        val transforms = listOf(
            base,
            base.transformedBy(Offset(800f, 1100f), 2.5f, Offset(-200f, 150f)),
            base.transformedBy(Offset(300f, 1900f), 5f, Offset.Zero),
        )
        for (t in transforms) {
            val radius = HoldHitTest.hitRadiusIn(spacing, t.scale, minRadiusPx)
            for (hold in layout.holds) {
                // A slightly sloppy tap: 1.5 in right and 1 in below the hold's center.
                val screen = t.wallToScreen(hold.x + 1.5f, hold.y - 1f)
                val hit = HoldHitTest.holdAt(layout.holds, t.screenToWall(screen), radius)
                assertEquals("zoom ${t.zoom}, hold ${hold.id}", hold.id, hit?.id)
            }
        }
    }

    @Test
    fun tapsAwayFromHoldsHitNothing() {
        val radius = HoldHitTest.hitRadiusIn(8f, scalePxPerIn = 16.7f, minRadiusPx)
        assertNull(HoldHitTest.holdAt(layout.holds, WallPoint(2f, 60f), radius)) // left of the first column
        assertNull(HoldHitTest.holdAt(layout.holds, WallPoint(50f, 112f), radius)) // above the top row
    }

    @Test
    fun picksTheNearestWhenTwoAreInRange() {
        val holds = listOf(Hold(1, 10f, 10f, listOf(1)), Hold(2, 13f, 10f, listOf(2)))
        assertEquals(2, HoldHitTest.holdAt(holds, WallPoint(12f, 10f), radiusIn = 5f)?.id)
        assertEquals(1, HoldHitTest.holdAt(holds, WallPoint(11f, 10f), radiusIn = 5f)?.id)
    }

    // ---- Tapping inside rings (the Route Editor) ----

    private fun ringAt(holds: List<Hold>, x: Float, y: Float, defaultRingIn: Float = 3.5f, minRadiusIn: Float = 0f) =
        HoldHitTest.ringAt(holds, WallPoint(x, y), defaultRingIn, minRadiusIn)?.id

    @Test
    fun aTapAnywhereInsideAnOvalHitsIt() {
        val oval = listOf(Hold(1, 50f, 50f, listOf(1), ringIn = 6f, ringYIn = 2f))
        assertEquals(1, ringAt(oval, 55f, 50f)) // 5 in out along its 6 in half-width
        assertEquals(1, ringAt(oval, 50f, 48.5f))
        assertNull(ringAt(oval, 50f, 53f)) // above its 2 in half-height
        assertNull(ringAt(oval, 57f, 50f))
    }

    @Test
    fun rotatedOvalsTurnClockwiseAsSeenOnTheWall() {
        val oval = listOf(Hold(1, 50f, 50f, listOf(1), ringIn = 6f, ringYIn = 1.5f, ringAngle = 30f))
        val c = cos(Math.toRadians(30.0)).toFloat()
        val s = sin(Math.toRadians(30.0)).toFloat()
        // Turned clockwise, the width points right and down.
        assertEquals(1, ringAt(oval, 50f + 5f * c, 50f - 5f * s))
        assertNull(ringAt(oval, 50f + 5f * c, 50f + 5f * s))
    }

    @Test
    fun theTouchAreaMatchesTheDrawnRing() {
        // The handles sit on the drawn ring's edge, so just inside a handle hits and just outside misses.
        val hold = Hold(1, 40f, 60f, listOf(1), ringIn = 6f, ringYIn = 2.5f, ringAngle = 30f)
        val t = WallTransform(96f, 114f, viewWidth = 1600f, viewHeight = 2200f)
            .transformedBy(Offset(800f, 1100f), 2.5f, Offset(-120f, 80f))
        val center = t.wallToScreen(hold.x, hold.y)
        val spots = RingGeometry.handlePositions(center, 6f * t.scale, 2.5f * t.scale, 30f, rotateGapPx = 0f)
        for (handle in listOf(RingHandle.WIDTH, RingHandle.HEIGHT)) {
            val edge = spots.getValue(handle) - center
            val inside = t.screenToWall(center + edge * 0.97f)
            val outside = t.screenToWall(center + edge * 1.03f)
            assertEquals("$handle", 1, ringAt(listOf(hold), inside.x, inside.y))
            assertNull("$handle", ringAt(listOf(hold), outside.x, outside.y))
        }
    }

    @Test
    fun holdsWithoutTheirOwnRingUseTheDefaultSize() {
        val holds = listOf(Hold(1, 20f, 20f, listOf(1)))
        assertEquals(1, ringAt(holds, 23.2f, 20f, defaultRingIn = 3.5f))
        assertNull(ringAt(holds, 23.2f, 20f, defaultRingIn = 3f))
    }

    @Test
    fun theSmallerRingWinsWhereRingsOverlap() {
        // A small hold on a big volume, off its center. Nearest-center would give the volume a
        // slice of the small ring; smaller-wins keeps all of it.
        val holds = listOf(
            Hold(1, 50f, 50f, listOf(1), ringIn = 8f),
            Hold(2, 52f, 50f, listOf(2), ringIn = 2.5f),
        )
        assertEquals(2, ringAt(holds, 50.5f, 50f)) // nearer the volume's center, but inside the small ring
        assertEquals(2, ringAt(holds, 54f, 50f))
        assertEquals(1, ringAt(holds, 45f, 50f)) // the rest of the volume still hits it
        assertEquals(1, ringAt(holds, 56f, 50f))
        // Neighbors whose rings just overlap: the sliver goes to the smaller one.
        val neighbors = listOf(
            Hold(3, 10f, 10f, listOf(3), ringIn = 3f),
            Hold(4, 14.5f, 10f, listOf(4), ringIn = 2f),
        )
        assertEquals(4, ringAt(neighbors, 12.8f, 10f))
        assertEquals(3, ringAt(neighbors, 12.4f, 10f))
    }

    @Test
    fun equalRingsGoToTheNearerCenter() {
        val holds = listOf(Hold(1, 10f, 10f, listOf(1)), Hold(2, 15f, 10f, listOf(2)))
        assertEquals(1, ringAt(holds, 12.4f, 10f))
        assertEquals(2, ringAt(holds, 12.6f, 10f))
    }

    @Test
    fun smallRingsGetTheMinimumTouchSize() {
        val dot = listOf(Hold(1, 30f, 30f, listOf(1), ringIn = 0.5f))
        assertEquals(1, ringAt(dot, 31.5f, 30f, minRadiusIn = 2f))
        assertNull(ringAt(dot, 31.5f, 30f, minRadiusIn = 0.5f))
        // A long, thin oval only grows across its thin side.
        val sliver = listOf(Hold(2, 30f, 30f, listOf(2), ringIn = 6f, ringYIn = 0.5f))
        assertEquals(2, ringAt(sliver, 30f, 31.5f, minRadiusIn = 2f))
        assertEquals(2, ringAt(sliver, 35.5f, 30f, minRadiusIn = 2f))
        assertNull(ringAt(sliver, 30f, 32.5f, minRadiusIn = 2f))
    }

    @Test
    fun ringTapsHitTheRightPlaceholderHoldAndMissBetweenThem() {
        for (hold in layout.holds) {
            assertEquals("hold ${hold.id}", hold.id, ringAt(layout.holds, hold.x + 2f, hold.y - 2f))
        }
        assertNull(ringAt(layout.holds, 12f, 23f)) // between four holds 8 in apart
        assertNull(ringAt(layout.holds, 2f, 60f)) // left of the first column
    }

    @Test
    fun mapLedsCapsTheRadiusSoSparseHoldsDoNotSwallowTaps() {
        // Two holds 40 in apart: half the spacing is 20 in, far too big while mapping.
        assertEquals(20f, HoldHitTest.hitRadiusIn(40f, scalePxPerIn = 10f, minRadiusPx = 48f), 0.001f)
        assertEquals(3f, HoldHitTest.hitRadiusIn(40f, scalePxPerIn = 10f, minRadiusPx = 48f, maxRadiusIn = 3f), 0.001f)
        // Dense holds stay under the cap.
        assertEquals(2f, HoldHitTest.hitRadiusIn(4f, scalePxPerIn = 100f, minRadiusPx = 48f, maxRadiusIn = 3f), 0.001f)
    }
}
