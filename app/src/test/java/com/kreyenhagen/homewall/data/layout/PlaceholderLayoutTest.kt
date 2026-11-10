package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderLayoutTest {

    private val layout = PlaceholderLayout.create()
    private fun ledAt(x: Float, y: Float) = layout.holds.single { it.x == x && it.y == y }.leds.single()

    @Test
    fun shapeMatchesTheSpec() {
        assertTrue(layout.placeholder)
        assertEquals(154, layout.ledCount)
        assertEquals(154, layout.holds.size)
        assertEquals(96f, layout.wall.widthIn)
        assertEquals(listOf(Panel("Kicker", 15f), Panel("Main", 99f)), layout.wall.panels)
        assertEquals((8..88 step 8).map { it.toFloat() }, layout.holds.map { it.x }.distinct().sorted())
        assertEquals(listOf(4f, 11f) + (19..107 step 8).map { it.toFloat() }, layout.holds.map { it.y }.distinct().sorted())
    }

    @Test
    fun oneLedPerHoldAndIdsMatchLeds() {
        assertTrue(layout.holds.all { it.leds == listOf(it.id) })
        assertEquals((0 until 154).toList(), layout.holds.map { it.id }.sorted())
    }

    @Test
    fun numberedSerpentineFromBottomLeft() {
        assertEquals(0, ledAt(8f, 4f))
        assertEquals(10, ledAt(88f, 4f))
        assertEquals(11, ledAt(88f, 11f)) // second row runs right to left
        assertEquals(21, ledAt(8f, 11f))
        assertEquals(22, ledAt(8f, 19f)) // first main-panel row, left to right again
        assertEquals(153, ledAt(8f, 107f)) // 14th row runs right to left, ends top-left
    }

    @Test
    fun kickerRowsAreOnTheKicker() {
        val kickerHolds = layout.holds.filter { layout.wall.panelIndexAt(it.y) == 0 }
        assertEquals(22, kickerHolds.size)
    }
}
