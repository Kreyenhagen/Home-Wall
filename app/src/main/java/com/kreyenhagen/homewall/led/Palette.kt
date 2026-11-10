package com.kreyenhagen.homewall.led

import androidx.compose.ui.graphics.Color
import com.kreyenhagen.homewall.domain.HoldRole

/** A plain R, G, B LED color, 0–255 per channel. The app never reorders channels (§9.3). */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB out of range: $r, $g, $b" }
    }
}

/**
 * LED palette slots and their colors (§9.3), and the one place that says what each hold role looks
 * like: its slot, its ring on screen and its LED color. Screen and LED colors are deliberately
 * separate, because LEDs render colors differently from the screen.
 */
object Palette {
    const val SLOT_OFF = 0
    const val SLOT_START = 1
    const val SLOT_MIDDLE = 2
    const val SLOT_FINISH = 3
    const val SLOT_CURSOR = 4
    const val SLOT_ALL_HOLDS = 5

    /** LED colors before brightness. Slot 0 is always black, so it isn't listed. */
    val defaults: Map<Int, Rgb> = mapOf(
        SLOT_START to Rgb(0, 255, 0),
        SLOT_MIDDLE to Rgb(0, 0, 255),
        SLOT_FINISH to Rgb(150, 0, 255),
        SLOT_CURSOR to Rgb(255, 255, 255),
        SLOT_ALL_HOLDS to Rgb(64, 64, 64),
    )

    /** [colors] scaled to [brightnessPercent] (clamped to 0–100), rounded to the nearest step. */
    fun scaled(brightnessPercent: Int, colors: Map<Int, Rgb> = defaults): Map<Int, Rgb> {
        val percent = brightnessPercent.coerceIn(0, 100)
        fun scale(c: Int) = (c * percent + 50) / 100
        return colors.mapValues { (_, c) -> Rgb(scale(c.r), scale(c.g), scale(c.b)) }
    }
}

/** The palette slot that lights this role on the wall. */
val HoldRole.slot: Int
    get() = when (this) {
        HoldRole.START -> Palette.SLOT_START
        HoldRole.MIDDLE -> Palette.SLOT_MIDDLE
        HoldRole.FINISH -> Palette.SLOT_FINISH
    }

/** The ring color on screen. */
val HoldRole.ringColor: Color
    get() = when (this) {
        HoldRole.START -> Color(0xFF00E676)
        HoldRole.MIDDLE -> Color(0xFF2979FF)
        HoldRole.FINISH -> Color(0xFFB040FF)
    }

/** The LED color before brightness. */
val HoldRole.ledColor: Rgb
    get() = Palette.defaults.getValue(slot)

/** The color of a slot's dot in the on-screen LED preview (§7.3): the ring color for roles. */
fun previewColor(slot: Int): Color = when (slot) {
    Palette.SLOT_START -> HoldRole.START.ringColor
    Palette.SLOT_MIDDLE -> HoldRole.MIDDLE.ringColor
    Palette.SLOT_FINISH -> HoldRole.FINISH.ringColor
    Palette.SLOT_ALL_HOLDS -> Color(0xFF9E9E9E)
    else -> Color.White
}
