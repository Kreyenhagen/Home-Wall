package com.kreyenhagen.homewall.data.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTransferTest {

    private val mapped = layoutOf(
        Hold(0, 4f, 6f, listOf(0), group = "kicker"),
        Hold(7, 12f, 22f, listOf(7, 8), ringIn = 2.5f),
        Hold(550, 40f, 60f, listOf(9)), // a fallback id (§6.1)
        hidden = listOf(1, 2, 3, 4, 5, 6),
    )

    @Test
    fun anExportedJsonReloadsIdentically() {
        // M6's check: "an exported JSON reloads identically", ids, groups and ring sizes included.
        val text = LayoutTransfer.encode(mapped, LayoutFormat.JSON)
        val read = LayoutTransfer.parse(text, REAL_WALL)
        assertEquals(LayoutTransfer.ReadResult.Ok(LayoutImport(mapped, LayoutFormat.JSON)), read)
        assertEquals(text, LayoutTransfer.encode((read as LayoutTransfer.ReadResult.Ok).import.layout, LayoutFormat.JSON))
    }

    @Test
    fun jsonIsRecognizedByItsContentEvenWithLeadingSpace() {
        val read = LayoutTransfer.parse("${CsvLayout.BYTE_ORDER_MARK}\n  " + LayoutJson.encode(mapped), REAL_WALL)
        assertEquals(LayoutFormat.JSON, (read as LayoutTransfer.ReadResult.Ok).import.format)
    }

    @Test
    fun anythingElseIsReadAsCsvUsingTheCurrentWall() {
        val read = LayoutTransfer.parse("led,x_in,y_in,hold\n0,4,6,\n", REAL_WALL)
        val import = (read as LayoutTransfer.ReadResult.Ok).import
        assertEquals(LayoutFormat.CSV, import.format)
        assertEquals(REAL_WALL, import.layout.wall)
        assertEquals("1 LED · 1 hold (1 LED) · 0 hidden", import.summary.text)
    }

    @Test
    fun problemsComeBackFromEitherFormat() {
        val badJson = LayoutTransfer.parse(DOC_EXAMPLE_JSON.replace("\"leds\": [40]", "\"leds\": [7]"), REAL_WALL)
        assertEquals(LayoutTransfer.ReadResult.Failed(listOf("LED 7 is in both hold 7 and hold 40.")), badJson)
        assertTrue(LayoutTransfer.parse("hello", REAL_WALL) is LayoutTransfer.ReadResult.Failed)
    }
}
