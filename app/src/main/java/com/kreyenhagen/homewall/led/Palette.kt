package com.kreyenhagen.homewall.led

/** A plain R, G, B LED color, 0–255 per channel. The app never reorders channels (§9.3). */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB out of range: $r, $g, $b" }
    }
}

/**
 * LED palette slots and their colors (§9.3). The HoldRole → (slot, ring color, LED color)
 * mapping joins this file when routes arrive (M3), so it all stays in one place.
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
