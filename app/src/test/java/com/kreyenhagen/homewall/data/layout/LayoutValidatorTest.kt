package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutValidatorTest {

    private fun problems(layout: WallLayout) = LayoutValidator.validate(layout)

    private fun assertProblem(layout: WallLayout, fragment: String) {
        val found = problems(layout)
        assertTrue("Expected a problem containing \"$fragment\", got $found", found.any { fragment in it })
    }

    @Test
    fun validLayoutsHaveNoProblems() {
        assertEquals(emptyList<String>(), problems(PlaceholderLayout.create()))
        assertEquals(emptyList<String>(), problems((LayoutJson.parse(DOC_EXAMPLE_JSON) as LayoutJson.ParseResult.Ok).layout))
    }

    @Test
    fun duplicateHoldIds() = assertProblem(
        layoutOf(Hold(5, 10f, 10f, listOf(5)), Hold(5, 20f, 20f, listOf(6))),
        "Hold 5: duplicate id",
    )

    @Test
    fun ledInTwoHolds() = assertProblem(
        layoutOf(Hold(5, 10f, 10f, listOf(5)), Hold(6, 20f, 20f, listOf(6, 5))),
        "LED 5 is in both hold 5 and hold 6",
    )

    @Test
    fun ledInAHoldAndHidden() = assertProblem(
        layoutOf(Hold(5, 10f, 10f, listOf(5)), hidden = listOf(5)),
        "LED 5 is in both hold 5 and hiddenLeds",
    )

    @Test
    fun unusedLedsFollowTheSameRules() {
        assertProblem(layoutOf(Hold(5, 10f, 10f, listOf(5)), unused = listOf(5)), "LED 5 is in both hold 5 and unusedLeds")
        assertProblem(layoutOf(hidden = listOf(7), unused = listOf(7)), "LED 7 is in both hiddenLeds and unusedLeds")
        assertProblem(layoutOf(unused = listOf(8, 8)), "unusedLeds: LED 8 is listed twice")
        assertProblem(layoutOf(unused = listOf(600)), "unusedLeds: LED 600 is out of range")
        assertEquals(emptyList<String>(), problems(layoutOf(Hold(5, 10f, 10f, listOf(5)), hidden = listOf(6), unused = listOf(7))))
    }

    @Test
    fun outOfRangeIndices() {
        assertProblem(layoutOf(Hold(5, 10f, 10f, listOf(550))), "LED 550 is out of range")
        assertProblem(layoutOf(Hold(5, 10f, 10f, listOf(-1))), "LED -1 is out of range")
        assertProblem(layoutOf(hidden = listOf(600)), "hiddenLeds: LED 600 is out of range")
    }

    @Test
    fun positionsOffTheWall() {
        assertProblem(layoutOf(Hold(5, 97f, 10f, listOf(5))), "off the wall")
        assertProblem(layoutOf(Hold(5, 10f, 114.5f, listOf(5))), "off the wall")
        assertProblem(layoutOf(Hold(5, -0.1f, 10f, listOf(5))), "off the wall")
    }

    @Test
    fun nonPositivePanelSizes() {
        val wall = WallGeometry(96f, listOf(Panel("Kicker", 0f), Panel("Main", -3f)))
        val found = problems(WallLayout(wall = wall, ledCount = 10))
        assertTrue(found.any { "Panel Kicker" in it })
        assertTrue(found.any { "Panel Main" in it })
        assertProblem(WallLayout(wall = WallGeometry(0f, REAL_WALL.panels), ledCount = 10), "Wall width must be positive")
        assertProblem(WallLayout(wall = WallGeometry(96f, emptyList()), ledCount = 10), "at least one panel")
    }

    @Test
    fun otherChecks() {
        assertProblem(layoutOf(Hold(5, 10f, 10f, emptyList())), "Hold 5: has no LEDs")
        assertProblem(layoutOf(Hold(5, 10f, 10f, listOf(5, 5))), "lists LED 5 twice")
        assertProblem(layoutOf(Hold(5, 10f, 10f, listOf(5), ringIn = 0f)), "ringIn must be positive")
        assertProblem(layoutOf(hidden = listOf(3, 3)), "LED 3 is listed twice")
        assertProblem(layoutOf(ledCount = 0), "ledCount must be between")
        assertProblem(layoutOf().copy(schemaVersion = 2), "Unsupported schemaVersion 2")
    }

    @Test
    fun everyProblemIsListed() {
        val layout = layoutOf(
            Hold(5, 10f, 10f, listOf(5)),
            Hold(5, 200f, 10f, listOf(5, 999)),
            hidden = listOf(5),
        )
        // duplicate id, LED 5 in two holds, off the wall, LED 999 out of range, LED 5 also hidden
        assertEquals(5, problems(layout).size)
    }
}
