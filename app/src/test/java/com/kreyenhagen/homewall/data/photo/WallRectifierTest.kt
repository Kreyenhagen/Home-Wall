package com.kreyenhagen.homewall.data.photo

import com.kreyenhagen.homewall.data.layout.Panel
import com.kreyenhagen.homewall.data.layout.WallGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallRectifierTest {

    private val wall = WallGeometry(96f, listOf(Panel("Kicker", 15f), Panel("Main", 99f)))

    /** The §6.2 example: a perspective-skewed wall in a portrait photo. */
    private val docAlignment = PhotoAlignment(
        listOf(
            AlignmentEdge(NormPoint(0.12f, 0.95f), NormPoint(0.88f, 0.95f)),
            AlignmentEdge(NormPoint(0.11f, 0.84f), NormPoint(0.89f, 0.84f)),
            AlignmentEdge(NormPoint(0.03f, 0.04f), NormPoint(0.97f, 0.04f)),
        ),
    )

    /** A keystoned one: the main panel leans toward the camera, so its top looks wider. */
    private val skewedAlignment = PhotoAlignment(
        listOf(
            AlignmentEdge(NormPoint(0.20f, 0.92f), NormPoint(0.78f, 0.90f)),
            AlignmentEdge(NormPoint(0.18f, 0.81f), NormPoint(0.80f, 0.80f)),
            AlignmentEdge(NormPoint(0.02f, 0.06f), NormPoint(0.99f, 0.03f)),
        ),
    )

    private fun plan(alignment: PhotoAlignment, geometry: WallGeometry = wall) =
        (WallRectifier.plan(geometry, alignment, 3000, 4000) as WallRectifier.PlanResult.Ok).plan

    @Test
    fun outputIs20PixelsPerInch() {
        val plan = plan(docAlignment)
        assertEquals(1920, plan.width)
        assertEquals(2280, plan.height)
    }

    @Test
    fun eachPanelsQuadCornersMapExactlyToItsBandCorners() {
        for (alignment in listOf(docAlignment, skewedAlignment)) {
            for (panel in plan(alignment).panels) {
                panel.src.zip(panel.dst).forEach { (src, dst) ->
                    val mapped = panel.homography.map(src)
                    assertEquals(dst.x, mapped.x, 1e-6)
                    assertEquals(dst.y, mapped.y, 1e-6)
                }
            }
        }
    }

    @Test
    fun bandsStackKickerBelowMainWithYFlipped() {
        val (kicker, main) = plan(docAlignment).panels
        // Kicker: wall y 0–15 → image rows 2280 (bottom) to 1980.
        assertEquals(listOf(Vec2(0.0, 2280.0), Vec2(1920.0, 2280.0), Vec2(1920.0, 1980.0), Vec2(0.0, 1980.0)), kicker.dst)
        // Main: wall y 15–114 → image rows 1980 to 0.
        assertEquals(listOf(Vec2(0.0, 1980.0), Vec2(1920.0, 1980.0), Vec2(1920.0, 0.0), Vec2(0.0, 0.0)), main.dst)
        // Each band is clipped with 1 px of overlap at the fold, so there's no seam.
        assertEquals(1979f, kicker.clipTop)
        assertEquals(2280f, kicker.clipBottom)
        assertEquals(0f, main.clipTop)
        assertEquals(1981f, main.clipBottom)
    }

    @Test
    fun sourceQuadsComeFromTheAlignmentInPhotoPixels() {
        val (kicker, main) = plan(docAlignment).panels
        assertEquals(Vec2(360.0, 3800.0), kicker.src[0].rounded()) // floor left (0.12, 0.95)
        assertEquals(Vec2(2670.0, 3360.0), kicker.src[2].rounded()) // fold right (0.89, 0.84)
        assertEquals(Vec2(330.0, 3360.0), main.src[0].rounded()) // fold left (0.11, 0.84)
        assertEquals(Vec2(90.0, 160.0), main.src[3].rounded()) // top left (0.03, 0.04)
    }

    @Test
    fun crossedHandlesAreRejected() {
        // Fold left and right swapped, so the kicker's sides cross.
        val crossed = docAlignment.copy(
            edges = docAlignment.edges.toMutableList().also {
                it[1] = AlignmentEdge(left = it[1].right, right = it[1].left)
            },
        )
        val result = WallRectifier.plan(wall, crossed, 3000, 4000)
        assertTrue(result is WallRectifier.PlanResult.Failed)
        assertTrue((result as WallRectifier.PlanResult.Failed).message.contains("Kicker"))
    }

    @Test
    fun wrongEdgeCountIsRejected() {
        val result = WallRectifier.plan(wall, PhotoAlignment(docAlignment.edges.take(2)), 3000, 4000)
        assertTrue(result is WallRectifier.PlanResult.Failed)
    }

    @Test
    fun projectiveMapKeepsDiagonalIntersections() {
        // A perspective map sends the center of a rectangle to where the target quad's diagonals cross.
        val src = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0), Vec2(0.0, 2.0))
        val dst = listOf(Vec2(10.0, 10.0), Vec2(50.0, 12.0), Vec2(45.0, 40.0), Vec2(5.0, 30.0))
        val h = Homography.fromQuads(src, dst) ?: error("degenerate")
        val center = h.map(Vec2(2.0, 1.0))
        val crossing = intersect(dst[0], dst[2], dst[1], dst[3])
        assertEquals(crossing.x, center.x, 1e-9)
        assertEquals(crossing.y, center.y, 1e-9)
    }

    @Test
    fun degenerateQuadsHaveNoTransform() {
        val line = listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0), Vec2(2.0, 2.0), Vec2(3.0, 3.0))
        val square = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))
        assertNull(Homography.fromQuads(line, square))
        assertFalse(isConvexQuad(line))
        assertTrue(isConvexQuad(square))
        assertFalse(isConvexQuad(listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0)))) // bow-tie
    }

    private fun Vec2.rounded() = Vec2(Math.round(x * 1000) / 1000.0, Math.round(y * 1000) / 1000.0)

    private fun intersect(a1: Vec2, a2: Vec2, b1: Vec2, b2: Vec2): Vec2 {
        val d = (a1.x - a2.x) * (b1.y - b2.y) - (a1.y - a2.y) * (b1.x - b2.x)
        val t = ((a1.x - b1.x) * (b1.y - b2.y) - (a1.y - b1.y) * (b1.x - b2.x)) / d
        return Vec2(a1.x + t * (a2.x - a1.x), a1.y + t * (a2.y - a1.y))
    }
}
