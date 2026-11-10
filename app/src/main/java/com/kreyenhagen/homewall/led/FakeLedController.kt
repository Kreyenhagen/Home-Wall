package com.kreyenhagen.homewall.led

import com.kreyenhagen.homewall.util.LogBuffer
import com.kreyenhagen.homewall.util.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stand-in for the ESP32, used on the emulator (§10.5). Reports [ConnectionState.Simulated],
 * encodes with the real [LedProtocol] encoder, logs every packet as hex, and waits
 * [packetDelayMs] per packet to mimic a write-with-response round trip.
 */
class FakeLedController(
    private val log: LogBuffer,
    private val palette: Flow<Map<Int, Rgb>>,
    private val packetDelayMs: Long = 20,
) : LedController {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _session = MutableStateFlow<Int?>(null)
    override val session: StateFlow<Int?> = _session.asStateFlow()

    /** One operation at a time, like the real GATT queue (§10.4). */
    private val mutex = Mutex()
    private var seq = 0
    private var sessionCounter = 0

    /** Like a real connect (§10.3): send the palette, then publish a new session. */
    suspend fun connect() {
        mutex.withLock {
            if (_session.value != null) return
            _state.value = ConnectionState.Connecting
            log.log(TAG, "Simulated controller connected (MTU $MTU)")
            transmit(LedProtocol.encodePalette(palette.first(), seq, MTU))
            _state.value = ConnectionState.Simulated
            _session.value = ++sessionCounter
        }
    }

    fun disconnect() {
        if (_state.value == ConnectionState.Disconnected) return
        _session.value = null
        _state.value = ConnectionState.Disconnected
        log.log(TAG, "Simulated controller disconnected")
    }

    override suspend fun sendPalette(slots: Map<Int, Rgb>) {
        mutex.withLock {
            if (isConnected("palette")) transmit(LedProtocol.encodePalette(slots, seq, MTU))
        }
    }

    override suspend fun sendFrame(frame: LedFrame) {
        mutex.withLock {
            if (isConnected("frame")) transmit(LedProtocol.encodeFrame(frame, seq, MTU))
        }
    }

    private fun isConnected(what: String): Boolean {
        val connected = _session.value != null
        if (!connected) log.log(TAG, "Dropped $what: not connected")
        return connected
    }

    private suspend fun transmit(packets: List<ByteArray>) {
        for (packet in packets) {
            delay(packetDelayMs)
            seq = (seq + 1) and 0xFF
            log.log(TAG, "TX ${packet.toHex()}")
        }
    }

    private companion object {
        const val TAG = "LED"
        const val MTU = LedProtocol.PREFERRED_MTU
    }
}
