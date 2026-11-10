package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.graphics.Color
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.led.previewColor

/**
 * The debug LED preview (§7.3): a dot at each hold whose LEDs are lit in [frame], the last frame
 * actually sent to the controller. LEDs that aren't in a hold have no position and aren't shown.
 */
object LedPreview {
    fun dots(frame: LedFrame?, layout: WallLayout?): Map<Int, Color> {
        if (frame == null || layout == null || frame.entries.isEmpty()) return emptyMap()
        val holdByLed = HashMap<Int, Int>()
        for (hold in layout.holds) for (led in hold.leds) holdByLed[led] = hold.id
        val dots = LinkedHashMap<Int, Color>()
        for (entry in frame.entries) {
            if (entry.slot == Palette.SLOT_OFF) continue
            val holdId = holdByLed[entry.index] ?: continue
            dots.putIfAbsent(holdId, previewColor(entry.slot))
        }
        return dots
    }
}
