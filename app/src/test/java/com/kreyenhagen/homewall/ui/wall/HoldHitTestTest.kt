package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.geometry.Offset
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    @Test
    fun mapLedsCapsTheRadiusSoSparseHoldsDoNotSwallowTaps() {
        // Two holds 40 in apart: half the spacing is 20 in, far too big while mapping.
        assertEquals(20f, HoldHitTest.hitRadiusIn(40f, scalePxPerIn = 10f, minRadiusPx = 48f), 0.001f)
        assertEquals(3f, HoldHitTest.hitRadiusIn(40f, scalePxPerIn = 10f, minRadiusPx = 48f, maxRadiusIn = 3f), 0.001f)
        // Dense holds stay under the cap.
        assertEquals(2f, HoldHitTest.hitRadiusIn(4f, scalePxPerIn = 100f, minRadiusPx = 48f, maxRadiusIn = 3f), 0.001f)
    }
}
