package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallTransformTest {

    // Tablet portrait-ish view: the wall's width is the limiting side.
    private val fit = WallTransform(wallWidthIn = 96f, wallHeightIn = 114f, viewWidth = 1600f, viewHeight = 2200f)

    private val samplePoints = listOf(WallPoint(0f, 0f), WallPoint(96f, 114f), WallPoint(8f, 4f), WallPoint(47.3f, 15f), WallPoint(88f, 107f))

    private fun assertRoundTrips(t: WallTransform) {
        for (p in samplePoints) {
            val back = t.screenToWall(t.wallToScreen(p.x, p.y))
            assertEquals("x of $p", p.x, back.x, 0.01f)
            assertEquals("y of $p", p.y, back.y, 0.01f)
        }
    }

    @Test
    fun fitsLikeContentScaleFitWithYFlipped() {
        assertEquals(1600f / 96f, fit.fitScale, 1e-4f)
        val rect = fit.wallRect
        assertEquals(0f, rect.left, 1e-3f)
        assertEquals(1600f, rect.right, 1e-3f)
        assertEquals((2200f - rect.height) / 2f, rect.top, 1e-3f) // centered vertically
        // Wall y = 0 (the floor) is at the bottom of the rect.
        assertEquals(rect.bottom, fit.wallToScreen(0f, 0f).y, 1e-3f)
        assertEquals(rect.top, fit.wallToScreen(0f, 114f).y, 1e-3f)
    }

    @Test
    fun roundTripsWithoutZoom() = assertRoundTrips(fit)

    @Test
    fun roundTripsWithZoomAndPan() {
        assertRoundTrips(fit.copy(zoom = 3.2f, pan = Offset(-900f, -1400f)))
        assertRoundTrips(fit.copy(zoom = 5f, pan = Offset(-6400f, -8000f)))
        assertRoundTrips(fit.transformedBy(centroid = Offset(400f, 900f), zoomChange = 2.5f, panChange = Offset(30f, -50f)))
    }

    @Test
    fun zoomIsClampedToOneToFive() {
        assertEquals(5f, fit.transformedBy(Offset(800f, 1100f), 20f, Offset.Zero).zoom)
        assertEquals(1f, fit.copy(zoom = 2f).transformedBy(Offset(800f, 1100f), 0.1f, Offset.Zero).zoom)
    }

    @Test
    fun pinchKeepsThePointUnderTheFingersInPlace() {
        val centroid = Offset(600f, 1200f)
        val before = fit.screenToWall(centroid)
        val zoomed = fit.transformedBy(centroid, 2f, Offset.Zero)
        val after = zoomed.screenToWall(centroid)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test
    fun panningCantPushTheWallOutOfView() {
        val zoomed = fit.transformedBy(Offset(800f, 1100f), 3f, Offset.Zero)
        val farRight = zoomed.transformedBy(Offset(800f, 1100f), 1f, Offset(100_000f, 100_000f)).wallRect
        assertEquals(0f, farRight.left, 1e-2f)
        assertEquals(0f, farRight.top, 1e-2f)
        val farLeft = zoomed.transformedBy(Offset(800f, 1100f), 1f, Offset(-100_000f, -100_000f)).wallRect
        assertEquals(1600f, farLeft.right, 1e-2f)
        assertEquals(2200f, farLeft.bottom, 1e-2f)
    }

    @Test
    fun atOneTimesTheWallStaysCentered() {
        val panned = fit.transformedBy(Offset(800f, 1100f), 1f, Offset(300f, 300f))
        assertEquals(Offset.Zero, panned.pan)
    }

    @Test
    fun aSmallerAxisStaysCenteredWhileZoomed() {
        // Landscape: the wall is narrower than the view even at 2×.
        val landscape = WallTransform(96f, 114f, viewWidth = 2560f, viewHeight = 1400f)
        val zoomed = landscape.transformedBy(Offset(1280f, 700f), 2f, Offset(500f, 0f))
        val rect = zoomed.wallRect
        assertTrue(rect.width < 2560f)
        assertEquals(2560f / 2f, rect.center.x, 1e-2f)
    }
}
