package com.kreyenhagen.homewall.led

import java.util.UUID

/**
 * Encoder for LED protocol v1 (§11), the contract with `firmware/home_wall/home_wall.ino`.
 * Never change one side without the other.
 *
 * Every packet starts with `[opcode][seq]`. Packet `i` of a call uses seq `(firstSeq + i) mod 256`;
 * the caller advances its counter by the number of packets returned.
 */
object LedProtocol {
    const val VERSION = 1

    // GATT (§11.1)
    val SERVICE_UUID: UUID = UUID.fromString("5b1e0001-7c2f-4a8e-9d3b-2f6a1c4e8b70")
    val LED_CTRL_UUID: UUID = UUID.fromString("5b1e0002-7c2f-4a8e-9d3b-2f6a1c4e8b70")
    val INFO_UUID: UUID = UUID.fromString("5b1e0003-7c2f-4a8e-9d3b-2f6a1c4e8b70")

    const val OP_SET_PALETTE = 0x01
    const val OP_FRAME = 0x02

    const val FLAG_BEGIN = 0x01
    const val FLAG_COMMIT = 0x02

    const val DEFAULT_MTU = 23
    const val PREFERRED_MTU = 517

    private const val MAX_ATTRIBUTE_SIZE = 512
    private const val HEADER_SIZE = 2 // opcode, seq
    private const val FRAME_HEADER_SIZE = 3 // opcode, seq, flags
    private const val FRAME_ENTRY_SIZE = 3 // led u16 LE, slot
    private const val PALETTE_ENTRY_SIZE = 4 // slot, r, g, b

    /** Largest packet we may write: `min(MTU − 3, 512)` (§11.4). */
    fun maxPacketSize(mtu: Int): Int = minOf(mtu - 3, MAX_ATTRIBUTE_SIZE)

    /** `(maxPacket − 3) / 3`: 169 at MTU 517, 5 at MTU 23 (§11.4). */
    fun maxFrameEntries(mtu: Int): Int = (maxPacketSize(mtu) - FRAME_HEADER_SIZE) / FRAME_ENTRY_SIZE

    /**
     * SET_PALETTE (§11.3), slots in ascending order. Slot 0 is always black, so it's never sent.
     * Each packet is a complete message, so a palette that doesn't fit one packet is split across
     * several. Returns no packets for an empty palette.
     */
    fun encodePalette(slots: Map<Int, Rgb>, firstSeq: Int, mtu: Int): List<ByteArray> {
        val perPacket = (maxPacketSize(mtu) - HEADER_SIZE) / PALETTE_ENTRY_SIZE
        require(perPacket >= 1) { "MTU $mtu is too small" }
        val entries = slots.entries
            .filter { it.key != Palette.SLOT_OFF }
            .sortedBy { it.key }
        entries.forEach { require(it.key in 0..255) { "Slot ${it.key} doesn't fit in a byte" } }

        return entries.chunked(perPacket).mapIndexed { packet, chunk ->
            val out = ByteArray(HEADER_SIZE + chunk.size * PALETTE_ENTRY_SIZE)
            out[0] = OP_SET_PALETTE.toByte()
            out[1] = seqAt(firstSeq, packet)
            chunk.forEachIndexed { i, (slot, rgb) ->
                val o = HEADER_SIZE + i * PALETTE_ENTRY_SIZE
                out[o] = slot.toByte()
                out[o + 1] = rgb.r.toByte()
                out[o + 2] = rgb.g.toByte()
                out[o + 3] = rgb.b.toByte()
            }
            out
        }
    }

    /**
     * FRAME (§11.3, §11.4). One packet with flags BEGIN|COMMIT if it fits (an empty frame turns
     * everything off); otherwise BEGIN on the first packet, 0 in the middle, COMMIT on the last.
     */
    fun encodeFrame(frame: LedFrame, firstSeq: Int, mtu: Int): List<ByteArray> {
        val perPacket = maxFrameEntries(mtu)
        require(perPacket >= 1) { "MTU $mtu is too small" }
        frame.entries.forEach {
            require(it.index in 0..0xFFFF) { "LED ${it.index} doesn't fit in a uint16" }
            require(it.slot in 0..255) { "Slot ${it.slot} doesn't fit in a byte" }
        }

        val chunks = if (frame.entries.isEmpty()) listOf(emptyList()) else frame.entries.chunked(perPacket)
        return chunks.mapIndexed { packet, chunk ->
            var flags = 0
            if (packet == 0) flags = flags or FLAG_BEGIN
            if (packet == chunks.lastIndex) flags = flags or FLAG_COMMIT

            val out = ByteArray(FRAME_HEADER_SIZE + chunk.size * FRAME_ENTRY_SIZE)
            out[0] = OP_FRAME.toByte()
            out[1] = seqAt(firstSeq, packet)
            out[2] = flags.toByte()
            chunk.forEachIndexed { i, entry ->
                val o = FRAME_HEADER_SIZE + i * FRAME_ENTRY_SIZE
                out[o] = (entry.index and 0xFF).toByte()
                out[o + 1] = (entry.index ushr 8).toByte()
                out[o + 2] = entry.slot.toByte()
            }
            out
        }
    }

    /** INFO (§11.2): version, ledCount (uint16 LE), palette slots, then optional extras. Null if too short. */
    fun parseInfo(bytes: ByteArray): ControllerInfo? {
        if (bytes.size < 4) return null
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        return ControllerInfo(protocolVersion = u8(0), ledCount = u8(1) or (u8(2) shl 8), paletteSlots = u8(3))
    }

    private fun seqAt(firstSeq: Int, offset: Int): Byte = ((firstSeq + offset) and 0xFF).toByte()
}

/** What the controller reports in INFO (§11.2). */
data class ControllerInfo(val protocolVersion: Int, val ledCount: Int, val paletteSlots: Int)
