package com.kreyenhagen.homewall.led

import com.kreyenhagen.homewall.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedProtocolTest {

    private val defaultPalette = mapOf(
        1 to Rgb(0, 255, 0),
        2 to Rgb(0, 0, 255),
        3 to Rgb(150, 0, 255),
        4 to Rgb(255, 255, 255),
    )

    // ---- §11.5 golden vectors ----

    @Test
    fun golden1_palette() {
        val packets = LedProtocol.encodePalette(defaultPalette, firstSeq = 0, mtu = 517)
        assertEquals(listOf("01 00 01 00 FF 00 02 00 00 FF 03 96 00 FF 04 FF FF FF"), packets.hex())
    }

    @Test
    fun golden2_routeFrame() {
        val frame = LedFrame(
            listOf(
                LedEntry(12, Palette.SLOT_START),
                LedEntry(40, Palette.SLOT_MIDDLE),
                LedEntry(41, Palette.SLOT_MIDDLE),
                LedEntry(300, Palette.SLOT_MIDDLE),
                LedEntry(415, Palette.SLOT_FINISH),
            ),
        )
        val packets = LedProtocol.encodeFrame(frame, firstSeq = 7, mtu = 517)
        assertEquals(listOf("02 07 03 0C 00 01 28 00 02 29 00 02 2C 01 02 9F 01 03"), packets.hex())
    }

    @Test
    fun golden3_allOff() {
        assertEquals(listOf("02 08 03"), LedProtocol.encodeFrame(LedFrame.OFF, firstSeq = 8, mtu = 517).hex())
    }

    @Test
    fun golden4_all550LedsAtMtu517() {
        val frame = LedFrame((0 until 550).map { LedEntry(it, Palette.SLOT_ALL_HOLDS) })
        val packets = LedProtocol.encodeFrame(frame, firstSeq = 0, mtu = 517)

        assertEquals(listOf(0x01, 0x00, 0x00, 0x02), packets.map { it.flags })
        assertEquals(listOf(169, 169, 169, 43), packets.map { it.frameEntryCount })
        assertEquals(frame.entries, decodeFrameEntries(packets))
    }

    // ---- Sizes and chunking (§11.4) ----

    @Test
    fun maxEntriesPerPacket() {
        assertEquals(169, LedProtocol.maxFrameEntries(517))
        assertEquals(5, LedProtocol.maxFrameEntries(23))
        assertEquals(512, LedProtocol.maxPacketSize(517))
        assertEquals(20, LedProtocol.maxPacketSize(23))
    }

    @Test
    fun chunkingAtZeroOneMaxAndMaxPlusOneEntries() {
        for (mtu in listOf(23, 517)) {
            val max = LedProtocol.maxFrameEntries(mtu)
            val expectedFlags = mapOf(
                0 to listOf(0x03),
                1 to listOf(0x03),
                max to listOf(0x03),
                max + 1 to listOf(0x01, 0x02),
            )
            for ((count, flags) in expectedFlags) {
                // Indices step by 3 so they pass 255 and exercise both bytes of the uint16.
                val frame = LedFrame((0 until count).map { LedEntry(it * 3, it % 6) })
                val packets = LedProtocol.encodeFrame(frame, firstSeq = 0, mtu = mtu)
                val what = "mtu=$mtu count=$count"

                assertEquals(what, flags, packets.map { it.flags })
                assertTrue(what, packets.all { it.size <= LedProtocol.maxPacketSize(mtu) })
                assertTrue(what, packets.all { it[0].toInt() == LedProtocol.OP_FRAME })
                assertEquals(what, packets.indices.toList(), packets.map { it.seq })
                assertEquals(what, frame.entries, decodeFrameEntries(packets))
            }
        }
    }

    @Test
    fun seqWrapsFrom255To0() {
        val frame = LedFrame((0 until 6).map { LedEntry(it, 1) })
        val packets = LedProtocol.encodeFrame(frame, firstSeq = 255, mtu = 23)
        assertEquals(listOf(255, 0), packets.map { it.seq })
    }

    @Test
    fun paletteSkipsSlotZeroAndSortsBySlot() {
        val slots = mapOf(3 to Rgb(1, 2, 3), 0 to Rgb(9, 9, 9), 1 to Rgb(4, 5, 6))
        assertEquals(
            listOf("01 05 01 04 05 06 03 01 02 03"),
            LedProtocol.encodePalette(slots, firstSeq = 5, mtu = 517).hex(),
        )
    }

    @Test
    fun paletteSplitsIntoCompleteMessagesAtDefaultMtu() {
        // 5 slots need 2 + 20 bytes, but MTU 23 allows 20 per packet, so 4 slots fit in the first.
        val packets = LedProtocol.encodePalette(Palette.defaults, firstSeq = 0, mtu = 23)
        assertEquals(
            listOf(
                "01 00 01 00 FF 00 02 00 00 FF 03 96 00 FF 04 FF FF FF",
                "01 01 05 40 40 40",
            ),
            packets.hex(),
        )
        assertTrue(packets.all { it.size <= LedProtocol.maxPacketSize(23) })
    }

    @Test
    fun emptyPaletteSendsNothing() {
        assertEquals(emptyList<String>(), LedProtocol.encodePalette(emptyMap(), firstSeq = 0, mtu = 517).hex())
    }

    // ---- helpers ----

    private fun List<ByteArray>.hex() = map { it.toHex() }

    private val ByteArray.seq get() = this[1].toInt() and 0xFF
    private val ByteArray.flags get() = this[2].toInt() and 0xFF
    private val ByteArray.frameEntryCount get() = (size - 3) / 3

    private fun decodeFrameEntries(packets: List<ByteArray>): List<LedEntry> = packets.flatMap { p ->
        (3 until p.size step 3).map { o ->
            LedEntry(
                index = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8),
                slot = p[o + 2].toInt() and 0xFF,
            )
        }
    }
}
