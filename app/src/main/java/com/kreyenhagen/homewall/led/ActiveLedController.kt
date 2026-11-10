package com.kreyenhagen.homewall.led

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The controller the rest of the app sees: the simulated one while Settings → Simulated LED
 * controller is on (§10.5), otherwise Bluetooth.
 */
class ActiveLedController(
    private val simulatedController: LedController,
    private val bluetoothController: LedController,
    private val simulated: StateFlow<Boolean>,
    scope: CoroutineScope,
) : LedController {

    override val state: StateFlow<ConnectionState> =
        combine(simulated, simulatedController.state, bluetoothController.state) { sim, fake, ble -> if (sim) fake else ble }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /**
     * Unique across both controllers (even numbers for the simulated one, odd for Bluetooth), so
     * switching between them always counts as a new session and LedSync resends the frame.
     */
    override val session: StateFlow<Int?> =
        combine(simulated, simulatedController.session, bluetoothController.session) { sim, fake, ble ->
            if (sim) fake?.let { it * 2 } else ble?.let { it * 2 + 1 }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val active: LedController get() = if (simulated.value) simulatedController else bluetoothController

    override suspend fun sendPalette(slots: Map<Int, Rgb>) = active.sendPalette(slots)

    override suspend fun sendFrame(frame: LedFrame) = active.sendFrame(frame)
}
