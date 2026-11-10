package com.kreyenhagen.homewall.led

import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.domain.HoldRole.FINISH
import com.kreyenhagen.homewall.domain.HoldRole.MIDDLE
import com.kreyenhagen.homewall.domain.HoldRole.START
import com.kreyenhagen.homewall.domain.RouteHold
import com.kreyenhagen.homewall.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteFramesTest {

    /** The holds behind §11.5 vector 2: LEDs 40 and 41 are one paired hold. */
    private val layout = layoutOf(
        Hold(12, 10f, 5f, listOf(12)),
        Hold(40, 20f, 30f, listOf(40, 41)),
        Hold(300, 50f, 60f, listOf(300)),
        Hold(415, 70f, 100f, listOf(415)),
    )

    @Test
    fun pairedLedsAllLightInTheRoleSlot() {
        val frame = RouteFrames.forRoute(listOf(RouteHold(40, MIDDLE)), layout)
        assertEquals(LedFrame(listOf(LedEntry(40, Palette.SLOT_MIDDLE), LedEntry(41, Palette.SLOT_MIDDLE))), frame)
    }

    @Test
    fun theDocRouteEncodesToGoldenVector2() {
        // Listed out of order on purpose: the frame is sorted by LED index.
        val route = listOf(RouteHold(415, FINISH), RouteHold(300, MIDDLE), RouteHold(12, START), RouteHold(40, MIDDLE))
        val packets = LedProtocol.encodeFrame(RouteFrames.forRoute(route, layout), firstSeq = 7, mtu = 517)
        assertEquals(listOf("02 07 03 0C 00 01 28 00 02 29 00 02 2C 01 02 9F 01 03"), packets.map { it.toHex() })
    }

    @Test
    fun holdsMissingFromTheLayoutAreSkipped() {
        val frame = RouteFrames.forRoute(listOf(RouteHold(12, START), RouteHold(99, MIDDLE), RouteHold(415, FINISH)), layout)
        assertEquals(LedFrame(listOf(LedEntry(12, Palette.SLOT_START), LedEntry(415, Palette.SLOT_FINISH))), frame)
    }

    @Test
    fun anEmptyRouteOrDraftIsOff() {
        assertEquals(LedFrame.OFF, RouteFrames.forRoute(emptyList(), layout))
        assertEquals(LedFrame.OFF, RouteFrames.forDraft(emptyMap(), layout))
        assertEquals(LedFrame.OFF, RouteFrames.forRoute(listOf(RouteHold(99, START)), layout))
    }

    @Test
    fun theEditorDraftUsesTheSameMapping() {
        val draft = mapOf(415 to FINISH, 12 to START, 40 to MIDDLE)
        assertEquals(
            RouteFrames.forRoute(listOf(RouteHold(12, START), RouteHold(40, MIDDLE), RouteHold(415, FINISH)), layout),
            RouteFrames.forDraft(draft, layout),
        )
    }

    @Test
    fun frameOfSortsAndDropsDuplicateLeds() {
        val frame = LedFrame.of(listOf(LedEntry(9, 1), LedEntry(3, 2), LedEntry(9, 3)))
        assertEquals(LedFrame(listOf(LedEntry(3, 2), LedEntry(9, 1))), frame)
    }
}
