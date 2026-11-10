package com.kreyenhagen.homewall.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.ble.BleConnector
import com.kreyenhagen.homewall.ble.FoundDevice
import com.kreyenhagen.homewall.ble.ScanState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SavedDevice
import com.kreyenhagen.homewall.led.ConnectionState

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a tap on the Connect button should do (§8.1). */
enum class ConnectStep {
    /** The simulated controller is on: just say so. */
    SIMULATED_NOTE,

    /** Connected or connecting: the menu with the device name, Disconnect and Forget device. */
    MENU,
    PERMISSIONS,
    ENABLE_BLUETOOTH,
    CONNECT_SAVED,
    PICKER,
}

/** A snackbar message, optionally with an action. */
data class ConnectMessage(val message: UserMessage, val action: Action? = null) {
    enum class Action { RETRY, APP_SETTINGS }
}

data class ConnectUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val simulated: Boolean = false,
    val savedDevice: SavedDevice? = null,
    val scan: ScanState = ScanState(),
    val pickerOpen: Boolean = false,
    val message: ConnectMessage? = null,
)

/** The Connect button, its menu and the device picker. Only the screen does permission prompts. */
class ConnectViewModel(
    connection: Flow<ConnectionState>,
    settings: Flow<AppSettings>,
    private val ble: BleConnector,
    private val saveDevice: suspend (SavedDevice?) -> Unit,
) : ViewModel() {

    private data class Local(val pickerOpen: Boolean = false, val message: ConnectMessage? = null)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<ConnectUiState> =
        combine(connection, settings, ble.scan, local) { conn, s, scan, l ->
            ConnectUiState(conn, s.simulatedLeds, s.savedDevice, scan, l.pickerOpen, l.message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectUiState())

    init {
        // Errors show as a snackbar with Retry (§8.1). Only new errors, not one left over from before.
        viewModelScope.launch {
            connection.distinctUntilChanged().drop(1).collect { state ->
                if (state is ConnectionState.Error) show(state.message, ConnectMessage.Action.RETRY)
            }
        }
    }

    /** Decides what a tap does, given what only the screen can check. */
    fun stepFor(permitted: Boolean, bluetoothOn: Boolean): ConnectStep {
        val s = uiState.value
        return nextStep(s.connection, s.simulated, s.savedDevice != null, permitted, bluetoothOn)
    }

    fun connectSaved() {
        val device = uiState.value.savedDevice ?: return openPicker()
        ble.connect(device.address, device.name)
    }

    fun openPicker() {
        local.update { it.copy(pickerOpen = true) }
        ble.startScan()
    }

    fun rescan() = ble.startScan()

    fun closePicker() {
        ble.stopScan()
        local.update { it.copy(pickerOpen = false) }
    }

    /** Picking a board connects to it and saves it (§8.1). */
    fun pick(device: FoundDevice) {
        local.update { it.copy(pickerOpen = false) }
        ble.connect(device.address, device.name)
        viewModelScope.launch { saveDevice(SavedDevice(device.address, device.name)) }
    }

    fun disconnect() = ble.disconnect()

    fun forgetDevice() {
        ble.disconnect()
        viewModelScope.launch { saveDevice(null) }
    }

    fun retry() = connectSaved()

    fun showSimulatedNote() = show("Using the simulated LED controller. Turn it off in Settings.")

    fun permissionDenied() =
        show("Home Wall needs the Nearby devices permission to find and connect to the wall.", ConnectMessage.Action.APP_SETTINGS)

    fun bluetoothNotEnabled() = show("Turn on Bluetooth to connect to the wall.")

    fun messageShown(id: Long) = local.update { if (it.message?.message?.id == id) it.copy(message = null) else it }

    private fun show(text: String, action: ConnectMessage.Action? = null) =
        local.update { it.copy(message = ConnectMessage(UserMessage(text), action)) }

    companion object {
        fun nextStep(
            connection: ConnectionState,
            simulated: Boolean,
            hasSavedDevice: Boolean,
            permitted: Boolean,
            bluetoothOn: Boolean,
        ): ConnectStep = when {
            simulated -> ConnectStep.SIMULATED_NOTE
            connection is ConnectionState.Connected || connection == ConnectionState.Connecting -> ConnectStep.MENU
            !permitted -> ConnectStep.PERMISSIONS
            !bluetoothOn -> ConnectStep.ENABLE_BLUETOOTH
            hasSavedDevice -> ConnectStep.CONNECT_SAVED
            else -> ConnectStep.PICKER
        }
    }
}
