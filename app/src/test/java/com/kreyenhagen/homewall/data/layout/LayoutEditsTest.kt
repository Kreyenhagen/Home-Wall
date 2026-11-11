package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditsTest {

    private val base = layoutOf(
        Hold(36, 10f, 20f, listOf(36)),
        Hold(37, 30f, 40f, listOf(37, 38)),
        hidden = listOf(1, 2),
    )

    private fun WallLayout.hold(id: Int) = holds.firstOrNull { it.id == id }

    private fun assertValid(layout: WallLayout) = assertEquals(emptyList<String>(), LayoutValidator.validate(layout))

    // ---- Map LEDs (§12.1) ----

    @Test
    fun anUnmappedLedMakesAHoldNamedAfterIt() {
        val layout = base.withLedInNewHold(50, 60.04f, 70.06f)
        assertEquals(Hold(50, 60f, 70.1f, listOf(50)), layout.hold(50)) // positions kept to 0.1 in
        assertValid(layout)
    }

    @Test
    fun remappingALedOutOfAPairKeepsTheOldIdAndUsesTheFallback() {
        // §6.1's example: hold 37 owns [37, 38]; LED 37 moves, so hold 37 keeps only LED 38 and the
        // new hold can't also be 37.
        val layout = base.withLedInNewHold(37, 50f, 50f)
        assertEquals(listOf(38), layout.hold(37)?.leds)
        assertEquals(Hold(550, 50f, 50f, listOf(37)), layout.hold(550))
        assertValid(layout)
    }

    @Test
    fun remappingASingleLedHoldReplacesItAndReusesTheId() {
        val layout = base.withLedInNewHold(36, 12f, 22f)
        assertEquals(Hold(36, 12f, 22f, listOf(36)), layout.hold(36))
        assertEquals(base.holds.size, layout.holds.size)
        assertValid(layout)
    }

    @Test
    fun remappingAHiddenLedTakesItOutOfHiddenLeds() {
        val layout = base.withLedInNewHold(1, 5f, 5f)
        assertEquals(listOf(2), layout.hiddenLeds)
        assertEquals(listOf(1), layout.hold(1)?.leds)
        assertValid(layout)
    }

    @Test
    fun pairingAddsTheLedToTheTappedHold() {
        val layout = base.withLedAddedToHold(35, 36)
        assertEquals(listOf(35, 36), layout.hold(36)?.leds)
        assertValid(layout)
    }

    @Test
    fun pairingMovesTheLedOutOfItsOldHoldAndDeletesItIfEmpty() {
        val moved = base.withLedAddedToHold(38, 36)
        assertEquals(listOf(36, 38), moved.hold(36)?.leds)
        assertEquals(listOf(37), moved.hold(37)?.leds)

        val emptied = base.withLedAddedToHold(36, 37)
        assertNull(emptied.hold(36))
        assertEquals(listOf(36, 37, 38), emptied.hold(37)?.leds)
        assertValid(emptied)
    }

    @Test
    fun pairingWithAHoldThatDoesNotExistChangesNothing() {
        assertEquals(base, base.withLedAddedToHold(40, 999))
    }

    @Test
    fun hidingTakesTheLedOutOfItsHold() {
        val layout = base.withLedHidden(36)
        assertNull(layout.hold(36))
        assertEquals(listOf(1, 2, 36), layout.hiddenLeds)
        assertEquals(listOf(1, 2), base.withLedHidden(2).hiddenLeds) // already hidden: no duplicate
        assertValid(layout)
    }

    @Test
    fun assignmentsAndTheFirstUnmappedLed() {
        assertEquals(LedAssignment.InHold(base.holds[1]), base.assignmentOf(38))
        assertEquals(LedAssignment.Hidden, base.assignmentOf(2))
        assertEquals(LedAssignment.Unmapped, base.assignmentOf(3))
        assertEquals(0, base.firstUnmappedLed())
        assertEquals(3, base.firstUnmappedLed(from = 1))
        assertEquals(39, base.firstUnmappedLed(from = 36))
        assertNull(layoutOf(Hold(0, 1f, 1f, listOf(0)), ledCount = 2, hidden = listOf(1)).firstUnmappedLed())
    }

    // ---- Review (§12.2) ----

    @Test
    fun movingAHoldKeepsItOnTheWall() {
        assertEquals(Hold(36, 41.3f, 50f, listOf(36)), base.withHoldMoved(36, 41.26f, 50f).hold(36))
        assertEquals(Hold(36, 96f, 0f, listOf(36)), base.withHoldMoved(36, 120f, -3f).hold(36))
    }

    @Test
    fun removingAnLedFromAHoldUnmapsIt() {
        val layout = base.withLedRemovedFromHold(37, 37)
        assertEquals(listOf(38), layout.hold(37)?.leds)
        assertEquals(LedAssignment.Unmapped, layout.assignmentOf(37))
        assertEquals(base, base.withLedRemovedFromHold(36, 38)) // not in that hold
    }

    @Test
    fun deletingAHoldUnmapsItsLeds() {
        val layout = base.withoutHold(37)
        assertNull(layout.hold(37))
        assertEquals(LedAssignment.Unmapped, layout.assignmentOf(38))
        assertValid(layout)
    }

    // ---- LED count ----

    @Test
    fun loweringTheLedCountDropsLedsPastIt() {
        val layout = base.withLedCount(38)
        assertEquals(38, layout.ledCount)
        assertEquals(listOf(37), layout.hold(37)?.leds)
        assertEquals("LEDs 37–549 lose their mapping (2 in holds, 0 hidden).", base.ledCountLossNote(37))
        assertNull(base.ledCountLossNote(600))
        assertNull(base.ledCountLossNote(100))

        val tiny = base.withLedCount(2)
        assertEquals(emptyList<Hold>(), tiny.holds)
        assertEquals(listOf(1), tiny.hiddenLeds)
        assertValid(tiny)
    }

    @Test
    fun raisingTheLedCountAddsUnmappedLeds() {
        val layout = base.withLedCount(600)
        assertEquals(base.holds, layout.holds)
        assertEquals(LedAssignment.Unmapped, layout.assignmentOf(599))
    }

    @Test
    fun startingToMapReplacesThePlaceholderWithAnEmptyLayout() {
        val placeholder = PlaceholderLayout.create()
        val layout = placeholder.startedMapping(550)
        assertFalse(layout.placeholder)
        assertEquals(550, layout.ledCount)
        assertTrue(layout.holds.isEmpty())
        assertEquals(KNOWN_HIDDEN_LEDS, layout.hiddenLeds) // the LEDs behind the wall start Hidden
        assertEquals(placeholder.wall, layout.wall)
        assertValid(layout)

        assertEquals((0..29).toList(), placeholder.startedMapping(30).hiddenLeds) // only LEDs that exist
        assertTrue(placeholder.startedMapping(550, hidden = emptyList()).hiddenLeds.isEmpty())
    }

    // ---- Known hidden LEDs (§12.1) ----

    @Test
    fun theKnownHiddenListIsTheWallsLedsBehindIt() {
        assertEquals(75, KNOWN_HIDDEN_LEDS.size)
        assertEquals(KNOWN_HIDDEN_LEDS.sorted().distinct(), KNOWN_HIDDEN_LEDS)
        assertTrue(KNOWN_HIDDEN_LEDS.all { it in 0 until 550 })
        assertEquals(
            "0–40, 45, 54, 59, 64, 69, 74, 79, 84–86, 93, 106, 113, 120, 127, 134, 141, 148, 155, 162, 169, " +
                "200, 231, 262, 293, 325, 356, 371, 388, 420, 452, 484, 485, 517",
            ledRanges(KNOWN_HIDDEN_LEDS),
        )
    }

    @Test
    fun ledRangesShortenRuns() {
        assertEquals("", ledRanges(emptyList()))
        assertEquals("7", ledRanges(listOf(7)))
        assertEquals("1, 2", ledRanges(listOf(2, 1)))
        assertEquals("1–3, 5, 9, 10, 12–14", ledRanges(listOf(14, 1, 2, 3, 5, 9, 10, 12, 13, 13)))
    }

    @Test
    fun theKnownHiddenLedsTheHiddenListCanTake() {
        // base: LED 36 and LEDs 37, 38 are in tiles; LEDs 1, 2 are Hidden. LED 3 is Unused here.
        val layout = base.copy(unusedLeds = listOf(3))
        assertEquals(listOf(0, 1, 2, 4), layout.knownHiddenLedsAvailable(listOf(0, 1, 2, 3, 4, 36, 38, 600)))
    }

    // ---- Unused LEDs, and the Hidden and Unused lists (§12.1) ----

    @Test
    fun unusedTakesTheLedOutOfItsHoldOrTheHiddenList() {
        val layout = base.withLedUnused(36).withLedUnused(2)
        assertNull(layout.hold(36))
        assertEquals(listOf(2, 36), layout.unusedLeds)
        assertEquals(listOf(1), layout.hiddenLeds)
        assertEquals(LedAssignment.Unused, layout.assignmentOf(36))
        assertEquals(0, layout.firstUnmappedLed())
        assertEquals(3, layout.firstUnmappedLed(2)) // Unused LEDs count as mapped
        assertValid(layout)

        val placed = layout.withLedInNewHold(36, 10f, 20f) // and can be placed again
        assertEquals(listOf(2), placed.unusedLeds)
        assertEquals(listOf(36), placed.hold(36)?.leds)
    }

    @Test
    fun aTileCantTakeAnUnusedLed() {
        val layout = base.withLedUnused(40)
        assertEquals("LED 40 is marked Unused. To use it, go to LED 40 and tap this tile.", layout.holdLedsProblem(37, listOf(37, 40)))
    }

    @Test
    fun theHiddenAndUnusedListsCanBeReplaced() {
        val layout = base.withLedUnused(40)
        assertNull(layout.offListProblem(OffList.HIDDEN, listOf(0, 2, 3)))
        val hidden = layout.withOffList(OffList.HIDDEN, listOf(3, 0, 2, 3))
        assertEquals(listOf(0, 2, 3), hidden.hiddenLeds) // LED 1 was taken out: it's unmapped now
        assertEquals(LedAssignment.Unmapped, hidden.assignmentOf(1))
        assertValid(hidden)

        assertNull(layout.offListProblem(OffList.UNUSED, emptyList()))
        assertEquals(emptyList<Int>(), layout.withOffList(OffList.UNUSED, emptyList()).unusedLeds)
        assertEquals(listOf(1, 2), layout.ledsIn(OffList.HIDDEN))
        assertEquals(listOf(40), layout.ledsIn(OffList.UNUSED))
    }

    @Test
    fun theListsRefuseLedsInTilesOrTheOtherList() {
        val layout = base.withLedUnused(40)
        assertEquals("LED 37 is in a tile. To make it Hidden, go to LED 37 and tap Hidden.", layout.offListProblem(OffList.HIDDEN, listOf(1, 37)))
        assertEquals("LED 36 is in a tile. To make it Unused, go to LED 36 and tap Unused.", layout.offListProblem(OffList.UNUSED, listOf(36)))
        assertEquals("LED 40 is in the Unused list. Take it out of that list first.", layout.offListProblem(OffList.HIDDEN, listOf(40)))
        assertEquals("LED 1 is in the Hidden list. Take it out of that list first.", layout.offListProblem(OffList.UNUSED, listOf(1, 40)))
        assertEquals("There's no LED 550. LEDs go from 0 to 549.", layout.offListProblem(OffList.HIDDEN, listOf(550)))
    }

    @Test
    fun ledListsAreReadWithRanges() {
        assertEquals(emptyList<Int>(), parseLedList(" "))
        assertEquals(listOf(0, 1, 2, 3, 45), parseLedList("0–3, 45"))
        assertEquals(listOf(0, 1, 2, 7, 8, 9), parseLedList("9 8 7; 0 - 2, 2"))
        assertEquals(listOf(5, 6), parseLedList("5-6\n"))
        assertEquals(listOf(5, 6, 7), parseLedList("5—7"))
        assertNull(parseLedList("7-5"))
        assertNull(parseLedList("1-2-3"))
        assertNull(parseLedList("abc"))
        assertNull(parseLedList("-5"))
        assertNull(parseLedList("0-999999"))
        assertEquals(KNOWN_HIDDEN_LEDS, parseLedList(ledRanges(KNOWN_HIDDEN_LEDS))) // what the editor shows reads back
    }

    @Test
    fun summaryLine() {
        val summary = MappingSummary.of(base)
        assertEquals("550 LEDs · 2 holds (3 LEDs) · 2 hidden", summary.text)
        assertEquals(545, summary.unmapped)
        val one = MappingSummary(ledCount = 3, holds = 1, ledsInHolds = 1, hidden = 2)
        assertEquals("3 LEDs · 1 hold (1 LED) · 2 hidden", one.text)
        assertEquals(0, one.unmapped)

        val withUnused = MappingSummary.of(base.withLedUnused(40).withLedUnused(41))
        assertEquals("550 LEDs · 2 holds (3 LEDs) · 2 hidden · 2 unused", withUnused.text)
        assertEquals(543, withUnused.unmapped)
    }

    @Test
    fun loweringTheLedCountDropsUnusedLedsPastIt() {
        val layout = base.withLedUnused(40).withLedUnused(500)
        assertEquals("LEDs 400–549 lose their mapping (0 in holds, 0 hidden, 1 unused).", layout.ledCountLossNote(400))
        assertEquals(listOf(40), layout.withLedCount(400).unusedLeds)
    }

    // ---- Map LEDs → Change LEDs (§12.1) ----

    @Test
    fun aTileCanTakeUnmappedLedsOrKeepItsOwn() {
        assertNull(base.holdLedsProblem(37, listOf(38, 37)))
        assertNull(base.holdLedsProblem(37, listOf(40)))
        val layout = base.withHoldLeds(37, listOf(40, 38, 40))
        assertEquals(Hold(37, 30f, 40f, listOf(38, 40)), layout.hold(37)) // id and spot kept; sorted, no repeats
        assertEquals(LedAssignment.Unmapped, layout.assignmentOf(37))
        assertValid(layout)
    }

    @Test
    fun aTileCantTakeAnotherTilesOrAHiddenLed() {
        assertEquals("LED 36 is already in another tile.", base.holdLedsProblem(37, listOf(36)))
        assertEquals("LED 1 is on the Hidden list. To use it, take it out with Edit Hidden list first.", base.holdLedsProblem(37, listOf(1)))
        assertEquals("There's no LED 550. LEDs go from 0 to 549.", base.holdLedsProblem(37, listOf(550)))
        assertEquals("A tile needs at least one LED. Use Delete tile to remove it.", base.holdLedsProblem(37, emptyList()))
        assertEquals("That tile no longer exists.", base.holdLedsProblem(99, listOf(99)))
    }

    @Test
    fun aTileIsLabeledWithItsLeds() {
        assertEquals("36", base.hold(36)?.ledsLabel)
        assertEquals("37, 38", base.hold(37)?.ledsLabel)
    }
}
