package com.kreyenhagen.homewall.led

import kotlinx.coroutines.flow.StateFlow

/** §10.4 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val name: String, val ledCount: Int) : ConnectionState
    data object Simulated : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** §10.5. Only LedSync (M5) talks to this. */
interface LedController {
    val state: StateFlow<ConnectionState>

    /** A new value on every successful (re)connect; null when not connected. */
    val session: StateFlow<Int?>

    suspend fun sendPalette(slots: Map<Int, Rgb>)

    /** Encodes and chunks per §11, and suspends until every packet is acked. */
    suspend fun sendFrame(frame: LedFrame)
}
