package com.kreyenhagen.homewall.led

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerInfoTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun parsesTheDocExample() {
        // §11.2: 01 26 02 10 = protocol 1, 550 LEDs, 16 palette slots.
        assertEquals(ControllerInfo(protocolVersion = 1, ledCount = 550, paletteSlots = 16), LedProtocol.parseInfo(bytes(0x01, 0x26, 0x02, 0x10)))
    }

    @Test
    fun extraBytesAreIgnored() {
        assertEquals(ControllerInfo(1, 0xFFFF, 8), LedProtocol.parseInfo(bytes(0x01, 0xFF, 0xFF, 0x08, 0xAB, 0xCD)))
    }

    @Test
    fun tooShortIsNull() {
        assertNull(LedProtocol.parseInfo(bytes(0x01, 0x26, 0x02)))
        assertNull(LedProtocol.parseInfo(ByteArray(0)))
    }

    @Test
    fun gattUuidsMatchTheDoc() {
        assertEquals("5b1e0001-7c2f-4a8e-9d3b-2f6a1c4e8b70", LedProtocol.SERVICE_UUID.toString())
        assertEquals("5b1e0002-7c2f-4a8e-9d3b-2f6a1c4e8b70", LedProtocol.LED_CTRL_UUID.toString())
        assertEquals("5b1e0003-7c2f-4a8e-9d3b-2f6a1c4e8b70", LedProtocol.INFO_UUID.toString())
    }
}
