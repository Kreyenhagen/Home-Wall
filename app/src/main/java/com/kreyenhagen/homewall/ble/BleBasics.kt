package com.kreyenhagen.homewall.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.StateFlow

/** A board found by a scan (§10.2). */
data class FoundDevice(val address: String, val name: String, val rssi: Int)

data class ScanState(
    val scanning: Boolean = false,
    /** In the order they were found; RSSI is updated as new adverts arrive. */
    val devices: List<FoundDevice> = emptyList(),
    val error: String? = null,
)

/** The Bluetooth controls the Connect button needs (§8.1). [BleLedController] implements it. */
interface BleConnector {
    val scan: StateFlow<ScanState>

    /** Scans for 10 s, filtered to the Home Wall service. */
    fun startScan()

    fun stopScan()

    /** Connects, and keeps reconnecting after a drop until [disconnect] (§10.4). */
    fun connect(address: String, name: String)

    /** A disconnect I asked for: no auto-reconnect. */
    fun disconnect()
}

/** §10.1: what the app needs before it can scan or connect. */
object BlePermissions {
    val REQUIRED: Array<String> = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun granted(context: Context): Boolean =
        REQUIRED.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}

/** §10.4: auto-reconnect waits 1 s, 2 s, 4 s, then every 10 s. */
object ReconnectBackoff {
    private val STEPS_MS = longArrayOf(1_000, 2_000, 4_000)
    private const val AFTER_MS = 10_000L

    /** The wait before the next attempt, after [failures] failed attempts since the drop. */
    fun delayMs(failures: Int): Long = STEPS_MS.getOrElse(failures) { AFTER_MS }
}

class GattException(message: String, val status: Int? = null) : Exception(message)
