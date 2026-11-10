package com.kreyenhagen.homewall.led

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveLedControllerTest {

    private val fake = RecordingController()
    private val ble = RecordingController()
    private val simulated = MutableStateFlow(false)

    @Test
    fun sendsGoToTheSelectedController() = runTest {
        val active = ActiveLedController(fake, ble, simulated, backgroundScope)
        active.sendFrame(LedFrame.OFF)
        simulated.value = true
        active.sendFrame(LedFrame(listOf(LedEntry(1, 1))))
        assertEquals(listOf(LedFrame.OFF), ble.frames)
        assertEquals(listOf(LedFrame(listOf(LedEntry(1, 1)))), fake.frames)
    }

    @Test
    fun switchingControllersAlwaysStartsANewSession() = runTest {
        val active = ActiveLedController(fake, ble, simulated, backgroundScope)
        fake.session.value = 1
        ble.session.value = 1
        runCurrent()
        val bluetoothSession = active.session.value

        simulated.value = true
        runCurrent()
        val simulatedSession = active.session.value
        assertNotEquals(bluetoothSession, simulatedSession)

        ble.session.value = null
        simulated.value = false
        runCurrent()
        assertEquals(null, active.session.value)
    }
}
