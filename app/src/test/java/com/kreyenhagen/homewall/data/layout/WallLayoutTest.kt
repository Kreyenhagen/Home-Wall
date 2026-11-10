package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class WallLayoutTest {

    @Test
    fun geometryEdgesAndPanels() {
        assertEquals(114f, REAL_WALL.heightIn)
        assertEquals(listOf(0f, 15f, 114f), REAL_WALL.edgeYs)
        assertEquals(0, REAL_WALL.panelIndexAt(0f))
        assertEquals(0, REAL_WALL.panelIndexAt(14.9f))
        assertEquals(1, REAL_WALL.panelIndexAt(15f)) // on the fold → the panel above
        assertEquals(1, REAL_WALL.panelIndexAt(114f))
    }

    // ---- New-hold ids (§6.1) ----

    @Test
    fun newHoldTakesItsLowestLedIndex() {
        assertEquals(37, layoutOf().newHoldId(listOf(38, 37)))
    }

    @Test
    fun newHoldFallsBackToLowestUnusedIdFromLedCountWhenTaken() {
        // Hold 37 keeps its id after LED 37 moved away (it now only has LED 38).
        val layout = layoutOf(Hold(37, 10f, 20f, listOf(38)))
        assertEquals(550, layout.newHoldId(listOf(37)))

        val crowded = layoutOf(Hold(37, 10f, 20f, listOf(38)), Hold(550, 30f, 40f, listOf(12)), Hold(551, 50f, 60f, listOf(13)))
        assertEquals(552, crowded.newHoldId(listOf(37)))
    }

    // ---- Panel resizing (§6.1) ----

    private val kickerHold = Hold(1, 48f, 6f, listOf(1)) // 40 % up the kicker
    private val mainHold = Hold(2, 24f, 64.5f, listOf(2)) // halfway up the main panel
    private val topHold = Hold(3, 96f, 114f, listOf(3)) // top-right corner
    private val layout = layoutOf(kickerHold, mainHold, topHold)

    private fun WallLayout.hold(id: Int) = holds.first { it.id == id }

    @Test
    fun tallerKickerScalesKickerHoldsAndShiftsHoldsAbove() {
        val resized = layout.resized(96f, listOf(18f, 99f))
        assertEquals(7.2f, resized.hold(1).y, 1e-4f)
        assertEquals(67.5f, resized.hold(2).y, 1e-4f) // +3 in
        assertEquals(117f, resized.hold(3).y, 1e-4f)
        assertEquals(117f, resized.wall.heightIn, 1e-4f)
    }

    @Test
    fun shorterMainPanelScalesOnlyMainHolds() {
        val resized = layout.resized(96f, listOf(15f, 90f))
        assertEquals(6f, resized.hold(1).y, 1e-4f)
        assertEquals(60f, resized.hold(2).y, 1e-4f)
        assertEquals(105f, resized.hold(3).y, 1e-4f)
    }

    @Test
    fun widthScalesEveryX() {
        val resized = layout.resized(100f, listOf(15f, 99f))
        assertEquals(50f, resized.hold(1).x, 1e-4f)
        assertEquals(25f, resized.hold(2).x, 1e-4f)
        assertEquals(100f, resized.hold(3).x, 1e-4f)
        assertEquals(6f, resized.hold(1).y, 1e-4f)
    }

    @Test
    fun resizingKeepsEverythingElse() {
        val resized = layout.resized(90f, listOf(14f, 100f))
        assertEquals(layout.holds.map { it.id to it.leds }, resized.holds.map { it.id to it.leds })
        assertEquals(listOf("Kicker", "Main"), resized.wall.panels.map { it.name })
        assertEquals(emptyList<String>(), LayoutValidator.validate(resized))
    }
}
