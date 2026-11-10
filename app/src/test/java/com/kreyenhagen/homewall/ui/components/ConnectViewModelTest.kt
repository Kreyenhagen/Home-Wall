package com.kreyenhagen.homewall.ui.components

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.ble.BleConnector
import com.kreyenhagen.homewall.ble.FoundDevice
import com.kreyenhagen.homewall.ble.ScanState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SavedDevice
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.ui.components.ConnectStep.CONNECT_SAVED
import com.kreyenhagen.homewall.ui.components.ConnectStep.ENABLE_BLUETOOTH
import com.kreyenhagen.homewall.ui.components.ConnectStep.MENU
import com.kreyenhagen.homewall.ui.components.ConnectStep.PERMISSIONS
import com.kreyenhagen.homewall.ui.components.ConnectStep.PICKER
import com.kreyenhagen.homewall.ui.components.ConnectStep.SIMULATED_NOTE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private class FakeBle : BleConnector {
        override val scan = MutableStateFlow(ScanState())
        val calls = mutableListOf<String>()
        override fun startScan() {
            calls += "scan"
        }
        override fun stopScan() {
            calls += "stopScan"
        }
        override fun connect(address: String, name: String) {
            calls += "connect $address"
        }
        override fun disconnect() {
            calls += "disconnect"
        }
    }

    private val ble = FakeBle()
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val settings = MutableStateFlow(AppSettings())

    private fun TestScope.connectVm(): ConnectViewModel {
        val vm = ConnectViewModel(connection, settings, ble, saveDevice = { settings.value = settings.value.copy(savedDevice = it) })
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun whatATapDoes() {
        val d = ConnectionState.Disconnected
        assertEquals(SIMULATED_NOTE, ConnectViewModel.nextStep(ConnectionState.Simulated, simulated = true, hasSavedDevice = true, permitted = true, bluetoothOn = true))
        assertEquals(MENU, ConnectViewModel.nextStep(ConnectionState.Connected("HomeWall", 550), false, true, true, true))
        assertEquals(MENU, ConnectViewModel.nextStep(ConnectionState.Connecting, false, true, true, true))
        assertEquals(PERMISSIONS, ConnectViewModel.nextStep(d, false, true, permitted = false, bluetoothOn = true))
        assertEquals(ENABLE_BLUETOOTH, ConnectViewModel.nextStep(d, false, true, permitted = true, bluetoothOn = false))
        assertEquals(CONNECT_SAVED, ConnectViewModel.nextStep(d, false, hasSavedDevice = true, permitted = true, bluetoothOn = true))
        assertEquals(PICKER, ConnectViewModel.nextStep(d, false, hasSavedDevice = false, permitted = true, bluetoothOn = true))
        assertEquals(CONNECT_SAVED, ConnectViewModel.nextStep(ConnectionState.Error("x"), false, true, true, true))
    }

    @Test
    fun pickingABoardConnectsAndSavesIt() = runTest {
        val vm = connectVm()
        vm.openPicker()
        assertTrue(vm.uiState.value.pickerOpen)
        assertEquals(listOf("scan"), ble.calls)

        vm.pick(FoundDevice("AA:BB", "HomeWall", -60))
        assertFalse(vm.uiState.value.pickerOpen)
        assertEquals("connect AA:BB", ble.calls.last())
        assertEquals(SavedDevice("AA:BB", "HomeWall"), settings.value.savedDevice)
    }

    @Test
    fun connectSavedUsesTheSavedBoard() = runTest {
        settings.value = AppSettings(savedDevice = SavedDevice("11:22", "HomeWall"))
        val vm = connectVm()
        vm.connectSaved()
        assertEquals(listOf("connect 11:22"), ble.calls)
    }

    @Test
    fun forgetDisconnectsAndClearsTheSavedBoard() = runTest {
        settings.value = AppSettings(savedDevice = SavedDevice("11:22", "HomeWall"))
        val vm = connectVm()
        vm.forgetDevice()
        assertEquals(listOf("disconnect"), ble.calls)
        assertNull(settings.value.savedDevice)
    }

    @Test
    fun aNewErrorShowsWithRetryButAnOldOneDoesNot() = runTest {
        connection.value = ConnectionState.Error("old")
        val vm = connectVm()
        assertNull(vm.uiState.value.message)

        connection.value = ConnectionState.Connecting
        connection.value = ConnectionState.Error("Not a Home Wall controller")
        val message = vm.uiState.value.message
        assertEquals("Not a Home Wall controller", message?.message?.text)
        assertEquals(ConnectMessage.Action.RETRY, message?.action)

        vm.messageShown(message?.message?.id ?: -1)
        assertNull(vm.uiState.value.message)
    }
}
