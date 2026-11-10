package com.kreyenhagen.homewall.led

import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLedControllerTest {

    private val log = LogBuffer(clock = { 0L })
    private val palette = mapOf(
        1 to Rgb(0, 255, 0),
        2 to Rgb(0, 0, 255),
        3 to Rgb(150, 0, 255),
        4 to Rgb(255, 255, 255),
    )

    private fun newFake() = FakeLedController(log, flowOf(palette), packetDelayMs = 20)

    private fun sentPackets() = log.lines.value.map { it.message }.filter { it.startsWith("TX ") }.map { it.removePrefix("TX ") }

    @Test
    fun startsDisconnected() {
        val fake = newFake()
        assertEquals(ConnectionState.Disconnected, fake.state.value)
        assertNull(fake.session.value)
    }

    @Test
    fun connectSendsPaletteThenPublishesSession() = runTest {
        val fake = newFake()
        fake.connect()

        assertEquals(ConnectionState.Simulated, fake.state.value)
        assertEquals(1, fake.session.value)
        assertEquals(listOf("01 00 01 00 FF 00 02 00 00 FF 03 96 00 FF 04 FF FF FF"), sentPackets())
    }

    @Test
    fun framesUseTheRealEncoderAndARunningSeq() = runTest {
        val fake = newFake()
        fake.connect() // palette uses seq 0
        fake.sendFrame(LedFrame(listOf(LedEntry(12, 1))))
        fake.sendFrame(LedFrame.OFF)

        assertEquals(listOf("02 01 03 0C 00 01", "02 02 03"), sentPackets().drop(1))
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun eachPacketTakesTheSimulatedLatency() = runTest {
        val fake = newFake()
        fake.connect()
        val start = testScheduler.currentTime
        fake.sendFrame(LedFrame((0 until 550).map { LedEntry(it, 5) })) // 4 packets at MTU 517

        assertEquals(80L, testScheduler.currentTime - start)
    }

    @Test
    fun reconnectPublishesANewSession() = runTest {
        val fake = newFake()
        fake.connect()
        fake.disconnect()
        assertEquals(ConnectionState.Disconnected, fake.state.value)
        assertNull(fake.session.value)

        fake.connect()
        assertEquals(2, fake.session.value)
    }

    @Test
    fun connectingTwiceKeepsTheSession() = runTest {
        val fake = newFake()
        fake.connect()
        fake.connect()
        assertEquals(1, fake.session.value)
        assertEquals(1, sentPackets().size)
    }

    @Test
    fun framesWhileDisconnectedAreDropped() = runTest {
        val fake = newFake()
        fake.sendFrame(LedFrame.OFF)

        assertTrue(sentPackets().isEmpty())
        assertTrue(log.lines.value.any { it.message.startsWith("Dropped frame") })
    }
}
