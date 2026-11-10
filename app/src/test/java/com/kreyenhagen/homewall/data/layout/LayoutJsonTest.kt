package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutJsonTest {

    private fun parseOk(text: String) = (LayoutJson.parse(text) as LayoutJson.ParseResult.Ok).layout

    @Test
    fun parsesTheDocExample() {
        val layout = parseOk(DOC_EXAMPLE_JSON)
        assertEquals(550, layout.ledCount)
        assertEquals(Hold(7, 12f, 22f, listOf(7, 8), group = "main"), layout.holds[1])
        assertEquals(2.5f, layout.holds[2].ringIn)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), layout.hiddenLeds)
    }

    @Test
    fun roundTripsExactly() {
        val layouts = listOf(parseOk(DOC_EXAMPLE_JSON), PlaceholderLayout.create(), PlaceholderLayout.create().resized(95.3f, listOf(14.2f, 100.7f)))
        for (layout in layouts) {
            assertEquals(layout, parseOk(LayoutJson.encode(layout)))
        }
    }

    @Test
    fun writesOneHoldPerLineAndOmitsUnsetOptionals() {
        val text = LayoutJson.encode(parseOk(DOC_EXAMPLE_JSON))
        val lines = text.lines()
        assertTrue(lines.contains("""    {"id":0,"x":4.0,"y":6.0,"leds":[0]},"""))
        assertTrue(lines.contains("""    {"id":40,"x":8.0,"y":26.0,"leds":[40],"group":"aux-a","ringIn":2.5}"""))
        assertTrue(lines.contains("""  "hiddenLeds": [1,2,3,4,5,6]"""))
        assertTrue(lines.contains("""  "schemaVersion": 1,"""))
    }

    @Test
    fun unknownKeysAreAnError() {
        val text = DOC_EXAMPLE_JSON.replace("\"ledCount\": 550,", "\"ledCount\": 550, \"ledcount\": 3,")
        val result = LayoutJson.parse(text)
        assertTrue(result is LayoutJson.ParseResult.Failed)
    }

    @Test
    fun malformedJsonIsAProblemNotACrash() {
        val result = LayoutJson.parse("{ not json")
        assertTrue(result is LayoutJson.ParseResult.Failed)
        assertTrue((result as LayoutJson.ParseResult.Failed).problems.single().startsWith("Not a valid layout file"))
    }

    @Test
    fun validationProblemsAreAllReported() {
        val text = DOC_EXAMPLE_JSON.replace("\"leds\": [40]", "\"leds\": [8]").replace("[1, 2, 3, 4, 5, 6]", "[0, 1]")
        val problems = (LayoutJson.parse(text) as LayoutJson.ParseResult.Failed).problems
        assertEquals(listOf("LED 8 is in both hold 7 and hold 40.", "LED 0 is in both hold 0 and hiddenLeds."), problems)
    }
}
