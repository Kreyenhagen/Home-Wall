package com.kreyenhagen.homewall.ui.wall

import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.led.ringColor
import org.junit.Assert.assertEquals
import org.junit.Test

class LedPreviewTest {

    private val layout = layoutOf(
        Hold(12, 10f, 5f, listOf(12)),
        Hold(40, 20f, 30f, listOf(40, 41)),
        Hold(300, 50f, 60f, listOf(300)),
    )

    @Test
    fun oneDotPerLitHoldInTheRoleColor() {
        val frame = LedFrame(
            listOf(
                LedEntry(12, Palette.SLOT_START),
                LedEntry(40, Palette.SLOT_MIDDLE),
                LedEntry(41, Palette.SLOT_MIDDLE), // same hold as 40: still one dot
            ),
        )
        assertEquals(mapOf(12 to HoldRole.START.ringColor, 40 to HoldRole.MIDDLE.ringColor), LedPreview.dots(frame, layout))
    }

    @Test
    fun unmappedAndOffLedsHaveNoDot() {
        val frame = LedFrame(listOf(LedEntry(7, Palette.SLOT_START), LedEntry(300, Palette.SLOT_OFF)))
        assertEquals(emptyMap<Int, Any>(), LedPreview.dots(frame, layout))
    }

    @Test
    fun nothingSentOrNoLayoutMeansNoDots() {
        assertEquals(emptyMap<Int, Any>(), LedPreview.dots(null, layout))
        assertEquals(emptyMap<Int, Any>(), LedPreview.dots(LedFrame(listOf(LedEntry(12, 1))), null))
        assertEquals(emptyMap<Int, Any>(), LedPreview.dots(LedFrame.OFF, layout))
    }
}
