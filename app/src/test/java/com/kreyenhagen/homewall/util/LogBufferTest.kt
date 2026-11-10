package com.kreyenhagen.homewall.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    @Test
    fun keepsLinesOldestFirst() {
        var now = 0L
        val log = LogBuffer(capacity = 5, clock = { now++ })
        log.log("LED", "a")
        log.log("BLE", "b")

        assertEquals(
            listOf(LogBuffer.Line(0, "LED", "a"), LogBuffer.Line(1, "BLE", "b")),
            log.lines.value,
        )
    }

    @Test
    fun dropsTheOldestLinesPastCapacity() {
        val log = LogBuffer(capacity = 3, clock = { 0L })
        (1..5).forEach { log.log("LED", "line $it") }

        assertEquals(listOf("line 3", "line 4", "line 5"), log.lines.value.map { it.message })
    }

    @Test
    fun defaultCapacityIs200() {
        val log = LogBuffer(clock = { 0L })
        repeat(250) { log.log("LED", "$it") }

        assertEquals(200, log.lines.value.size)
        assertEquals("249", log.lines.value.last().message)
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val log = LogBuffer(clock = { 0L })
        log.log("LED", "x")
        log.clear()
        assertTrue(log.lines.value.isEmpty())
    }

    @Test
    fun hexFormatsBytesUppercaseWithSpaces() {
        assertEquals("02 08 03 FF 00", byteArrayOf(2, 8, 3, -1, 0).toHex())
        assertEquals("", ByteArray(0).toHex())
    }
}
