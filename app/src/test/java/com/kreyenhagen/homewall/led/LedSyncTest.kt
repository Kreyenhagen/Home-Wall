package com.kreyenhagen.homewall.led

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A controller that records what it's sent, and can hold a send "in flight" until released. */
class RecordingController : LedController {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Simulated)
    override val session = MutableStateFlow<Int?>(null)

    val frames = mutableListOf<LedFrame>()
    val palettes = mutableListOf<Map<Int, Rgb>>()

    /** If set, the next sendFrame suspends until this completes. */
    var gate: CompletableDeferred<Unit>? = null
    var failNext = false

    override suspend fun sendPalette(slots: Map<Int, Rgb>) {
        palettes += slots
    }

    override suspend fun sendFrame(frame: LedFrame) {
        if (failNext) {
            failNext = false
            throw IllegalStateException("write failed")
        }
        frames += frame
        val g = gate
        gate = null
        g?.await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LedSyncTest {

    private val controller = RecordingController()
    private val palette = MutableStateFlow(Palette.defaults)

    private fun frame(vararg leds: Int, slot: Int = Palette.SLOT_MIDDLE) = LedFrame(leds.map { LedEntry(it, slot) })

    private fun TestScope.sync(): LedSync = LedSync(controller, palette, backgroundScope).also { runCurrent() }

    @Test
    fun aFastBurstSendsOnlyTheLatestAfterTheInFlightFrame() = runTest {
        val sync = sync()
        controller.session.value = 1
        val inFlight = CompletableDeferred<Unit>()
        controller.gate = inFlight

        sync.showRoute(frame(1))
        runCurrent()
        assertEquals(listOf(frame(1)), controller.frames)

        // Swiping fast: three more routes while the first write is still waiting for its ack.
        sync.showRoute(frame(2))
        runCurrent()
        sync.showRoute(frame(3))
        runCurrent()
        sync.showRoute(frame(4))
        runCurrent()
        assertEquals(listOf(frame(1)), controller.frames)

        inFlight.complete(Unit)
        runCurrent()
        assertEquals(listOf(frame(1), frame(4)), controller.frames)
        assertEquals(frame(4), sync.lastSent.value)
    }

    @Test
    fun aNewSessionResendsTheCurrentFrame() = runTest {
        val sync = sync()
        controller.session.value = 1
        sync.show(frame(7))
        runCurrent()

        controller.session.value = null // dropped
        runCurrent()
        controller.session.value = 2 // reconnected
        runCurrent()
        assertEquals(listOf(frame(7), frame(7)), controller.frames)
    }

    @Test
    fun anIdenticalFrameIsNotResent() = runTest {
        val sync = sync()
        controller.session.value = 1
        sync.show(frame(5, 6))
        runCurrent()
        sync.show(frame(5, 6)) // equal, but a different instance
        runCurrent()
        sync.showLastRoute()
        runCurrent()
        assertEquals(listOf(frame(5, 6)), controller.frames)
    }

    @Test
    fun aFrameAndBackAgainIsSentBothTimes() = runTest {
        val sync = sync()
        controller.session.value = 1
        sync.show(frame(1))
        runCurrent()
        sync.show(frame(2))
        runCurrent()
        sync.show(frame(1))
        runCurrent()
        assertEquals(listOf(frame(1), frame(2), frame(1)), controller.frames)
    }

    @Test
    fun nothingIsSentWhileDisconnectedOrBeforeAnyFrameIsSet() = runTest {
        val sync = sync()
        controller.session.value = 1
        runCurrent()
        assertEquals(emptyList<LedFrame>(), controller.frames) // null desired frame: leave the wall alone
        controller.session.value = null
        sync.show(frame(3))
        runCurrent()
        assertEquals(emptyList<LedFrame>(), controller.frames)
        assertNull(sync.lastSent.value)

        controller.session.value = 2
        runCurrent()
        assertEquals(listOf(frame(3)), controller.frames)
    }

    @Test
    fun showLastRouteBringsBackTheLastRouteAndLightsOffClearsIt() = runTest {
        val sync = sync()
        controller.session.value = 1

        sync.showLastRoute() // no route yet: nothing to show
        runCurrent()
        assertEquals(emptyList<LedFrame>(), controller.frames)

        sync.showRoute(frame(10))
        runCurrent()
        sync.show(frame(20)) // e.g. the editor's draft
        runCurrent()
        sync.showLastRoute() // back on Route List
        runCurrent()
        assertEquals(listOf(frame(10), frame(20), frame(10)), controller.frames)

        sync.lightsOff()
        runCurrent()
        sync.show(frame(30))
        runCurrent()
        sync.showLastRoute()
        runCurrent()
        assertEquals(listOf(frame(10), frame(20), frame(10), LedFrame.OFF, frame(30), LedFrame.OFF), controller.frames)
    }

    @Test
    fun aFailedSendIsRetriedOnTheNextSession() = runTest {
        val sync = sync()
        controller.session.value = 1
        controller.failNext = true
        sync.show(frame(4))
        runCurrent()
        assertEquals(emptyList<LedFrame>(), controller.frames)
        assertNull(sync.lastSent.value)

        controller.session.value = 2
        runCurrent()
        assertEquals(listOf(frame(4)), controller.frames)
        assertEquals(frame(4), sync.lastSent.value)
    }

    @Test
    fun brightnessChangesResendThePaletteWhileConnected() = runTest {
        sync()
        assertEquals(emptyList<Map<Int, Rgb>>(), controller.palettes) // the controller sends the first one on connect

        palette.value = Palette.scaled(20)
        runCurrent()
        assertEquals(emptyList<Map<Int, Rgb>>(), controller.palettes) // not connected

        controller.session.value = 1
        palette.value = Palette.scaled(80)
        runCurrent()
        assertEquals(listOf(Palette.scaled(80)), controller.palettes)
    }
}
