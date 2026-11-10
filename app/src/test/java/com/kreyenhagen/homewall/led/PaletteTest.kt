package com.kreyenhagen.homewall.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaletteTest {

    @Test
    fun fullBrightnessKeepsColors() {
        assertEquals(Palette.defaults, Palette.scaled(100))
    }

    @Test
    fun halfBrightnessRoundsToNearest() {
        val half = Palette.scaled(50)
        assertEquals(Rgb(0, 128, 0), half[Palette.SLOT_START])
        assertEquals(Rgb(75, 0, 128), half[Palette.SLOT_FINISH])
        assertEquals(Rgb(32, 32, 32), half[Palette.SLOT_ALL_HOLDS])
    }

    @Test
    fun brightnessIsClamped() {
        assertEquals(Palette.defaults, Palette.scaled(150))
        assertEquals(Rgb(0, 0, 0), Palette.scaled(-10)[Palette.SLOT_CURSOR])
    }

    @Test
    fun rgbRejectsOutOfRangeChannels() {
        assertThrows(IllegalArgumentException::class.java) { Rgb(256, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Rgb(0, -1, 0) }
    }
}
