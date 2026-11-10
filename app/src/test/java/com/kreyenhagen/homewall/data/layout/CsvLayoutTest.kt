package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvLayoutTest {

    private fun ok(text: String): WallLayout {
        val result = CsvLayout.parse(text, REAL_WALL)
        assertTrue("Expected Ok, got $result", result is CsvLayout.ParseResult.Ok)
        return (result as CsvLayout.ParseResult.Ok).layout
    }

    private fun problems(text: String): List<String> {
        val result = CsvLayout.parse(text, REAL_WALL)
        assertTrue("Expected Failed, got $result", result is CsvLayout.ParseResult.Failed)
        return (result as CsvLayout.ParseResult.Failed).problems
    }

    @Test
    fun theDocExample() {
        val layout = ok("led,x_in,y_in,hold\n0,4,6,\n1,,,\n7,12,22,A\n8,12,22,A\n")
        assertEquals(
            listOf(Hold(0, 4f, 6f, listOf(0)), Hold(7, 12f, 22f, listOf(7, 8))),
            layout.holds,
        )
        assertEquals(listOf(1), layout.hiddenLeds)
        assertEquals(9, layout.ledCount) // highest led + 1
        assertEquals(LedAssignment.Unmapped, layout.assignmentOf(3)) // missing from the file
        assertEquals(REAL_WALL, layout.wall)
        assertEquals(false, layout.placeholder)
    }

    @Test
    fun aHoldIsPositionedAtItsFirstRowAndLaterRowsMayLeaveXyBlank() {
        val layout = ok("led,x_in,y_in,hold\n9,30,40,B\n8,31,41,B\n10,,,B\n")
        assertEquals(listOf(Hold(8, 30f, 40f, listOf(8, 9, 10))), layout.holds) // id = lowest LED (§6.1)
    }

    @Test
    fun headerInAnyOrderAndCaseWithQuotesCrlfAndABom() {
        val text = "${CsvLayout.BYTE_ORDER_MARK}Hold,LED,Y_IN,X_IN\r\n\"A, left\",3,\"10,5\",20\r\n,4,,\r\n\r\n"
        val layout = ok(text)
        assertEquals(listOf(Hold(3, 20f, 10.5f, listOf(3))), layout.holds)
        assertEquals(listOf(4), layout.hiddenLeds)
    }

    @Test
    fun theHoldColumnIsOptional() {
        val layout = ok("led,x_in,y_in\n0,4,6\n1,4,6\n")
        assertEquals(2, layout.holds.size) // no key: each LED is its own hold
    }

    @Test
    fun everyProblemIsListedWithItsLine() {
        val found = problems("led,x_in,y_in,hold\nx,1,1,\n2,1,\n2,1,1,\n3,5,5,\n3,6,6,\n4,,,C\n")
        assertEquals(
            listOf(
                "Line 2: led \"x\" isn't a whole number ≥ 0.",
                "Line 3: LED 2 needs both x_in and y_in as numbers (got \"1\", \"\").",
                "Line 4: LED 2 is listed more than once.",
                "Line 6: LED 3 is listed more than once.",
                "Line 7: LED 4 starts hold \"C\" but has no position.",
            ),
            found,
        )
    }

    @Test
    fun aMissingHeaderOrNoRowsIsAProblem() {
        assertEquals(listOf("The first row must be the header: led,x_in,y_in,hold (hold is optional)."), problems("0,4,6,\n1,,,\n"))
        assertEquals(listOf("The file is empty."), problems("\n\n"))
        assertEquals(listOf("The file has a header but no LED rows."), problems("led,x_in,y_in,hold\n"))
    }

    @Test
    fun positionsOffTheWallAreCaughtByTheValidator() {
        assertEquals(listOf("Hold 0: position (100.0, 6.0) is off the wall (96.0 × 114.0 in)."), problems("led,x_in,y_in\n0,100,6\n"))
    }

    @Test
    fun exportThenImportGivesTheSameLayout() {
        val layout = layoutOf(
            Hold(0, 4f, 6f, listOf(0)),
            Hold(7, 12.25f, 22f, listOf(7, 8)),
            Hold(20, 50f, 100.5f, listOf(20)),
            ledCount = 21,
            hidden = listOf(1, 2, 3),
        )
        val csv = CsvLayout.encode(layout)
        assertEquals(
            "led,x_in,y_in,hold\n0,4,6,0\n1,,,\n2,,,\n3,,,\n7,12.25,22,7\n8,12.25,22,7\n20,50,100.5,20\n",
            csv,
        )
        assertEquals(layout, ok(csv))
    }
}
