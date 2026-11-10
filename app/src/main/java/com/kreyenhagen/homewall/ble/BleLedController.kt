package com.kreyenhagen.homewall.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.led.LedController
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.LedProtocol
import com.kreyenhagen.homewall.led.Rgb
import com.kreyenhagen.homewall.util.LogBuffer
import com.kreyenhagen.homewall.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real controller (§10): talks to the ESP32 over Bluetooth LE with the platform APIs.
 *
 * The connection lives as long as the app (it's created in AppContainer), so it survives screen
 * changes and rotation. After a drop I didn't ask for, it reconnects with backoff while the app is
 * in the foreground, and as soon as the app comes back if it dropped in the background.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is behind problem(), which checks permissions first.
class BleLedController(
    private val context: Context,
    private val log: LogBuffer,
    private val palette: Flow<Map<Int, Rgb>>,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) : LedController, BleConnector {

    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val link = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val scanning = MutableStateFlow(false)

    override val state: StateFlow<ConnectionState> =
        combine(link, scanning) { l, s ->
            if (s && (l == ConnectionState.Disconnected || l is ConnectionState.Error)) ConnectionState.Scanning else l
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    private val _session = MutableStateFlow<Int?>(null)
    override val session: StateFlow<Int?> = _session.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    override val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private var connectionJob: Job? = null
    private var scanTimeout: Job? = null

    @Volatile
    private var activeLink: GattLink? = null

    @Volatile
    private var ledCtrl: BluetoothGattCharacteristic? = null

    /** Keeps each message's packets together and [seq] consistent. */
    private val sendMutex = Mutex()
    private var seq = 0
    private var sessionCounter = 0

    // ---- Connecting ----

    @Synchronized
    override fun connect(address: String, name: String) {
        stopScan()
        val previous = connectionJob
        connectionJob = scope.launch {
            previous?.cancelAndJoin()
            runConnection(address, name)
        }
    }

    @Synchronized
    override fun disconnect() {
        val previous = connectionJob
        connectionJob = null
        if (previous?.isActive == true) {
            log("Disconnecting (you asked)")
            previous.cancel()
        }
        // An error from the last attempt no longer applies once I've chosen to disconnect.
        link.update { if (it is ConnectionState.Error) ConnectionState.Disconnected else it }
    }

    private sealed interface Outcome {
        /** Was connected, then lost the connection. */
        data object Dropped : Outcome

        /** Couldn't connect; worth retrying. */
        data class Failed(val message: String) : Outcome

        /** Retrying won't help (wrong device or protocol). */
        data class Fatal(val message: String) : Outcome
    }

    private suspend fun runConnection(address: String, name: String) {
        var failures = 0
        var everConnected = false
        try {
            while (true) {
                foreground.first { it }
                val problem = problem()
                if (problem != null) {
                    log(problem)
                    link.value = ConnectionState.Error(problem)
                    if (!everConnected) return
                    waitBeforeRetry(ReconnectBackoff.delayMs(Int.MAX_VALUE))
                    continue
                }

                link.value = ConnectionState.Connecting
                when (val outcome = connectOnce(address, name)) {
                    Outcome.Dropped -> {
                        everConnected = true
                        failures = 0
                    }
                    is Outcome.Failed -> {
                        if (!everConnected) {
                            link.value = ConnectionState.Error(outcome.message)
                            return
                        }
                        failures++
                    }
                    is Outcome.Fatal -> {
                        link.value = ConnectionState.Error(outcome.message)
                        return
                    }
                }
                link.value = ConnectionState.Connecting
                val wait = ReconnectBackoff.delayMs(failures)
                log("Reconnecting in ${wait / 1000} s")
                waitBeforeRetry(wait)
            }
        } finally {
            activeLink?.close("ended")
            activeLink = null
            _session.value = null
            if (link.value !is ConnectionState.Error) link.value = ConnectionState.Disconnected
        }
    }

    /** Waits [ms], or less if the app comes back to the foreground in the meantime. */
    private suspend fun waitBeforeRetry(ms: Long) {
        withTimeoutOrNull(ms) {
            foreground.first { !it }
            foreground.first { it }
        }
    }

    private suspend fun connectOnce(address: String, name: String): Outcome {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return Outcome.Fatal("Saved device address $address isn't valid")

        var lastError = "Couldn't connect"
        for (attempt in 1..CONNECT_ATTEMPTS) {
            val gattLink = GattLink()
            activeLink = gattLink
            try {
                log("Connecting to $name ($address)" + if (attempt > 1) ", attempt $attempt" else "")
                gattLink.connect(context, device)
            } catch (e: GattException) {
                gattLink.close("failed")
                activeLink = null
                lastError = e.message ?: lastError
                log("Connect failed: $lastError")
                // An immediate GATT error (usually status 133) is worth a quick retry; a timeout isn't.
                if (e.status != null && attempt < CONNECT_ATTEMPTS) {
                    delay(RETRY_PAUSE_MS)
                    continue
                }
                return Outcome.Failed("Couldn't connect to $name. Is it powered on and nearby?")
            }
            return try {
                runSession(gattLink, name)
            } finally {
                _session.value = null
                ledCtrl = null
                gattLink.close("ended")
                activeLink = null
            }
        }
        return Outcome.Failed(lastError)
    }

    /** §10.3 steps 2–6, then waits until the connection ends. */
    private suspend fun runSession(gattLink: GattLink, name: String): Outcome {
        try {
            gattLink.requestHighPriority()
            val mtu = gattLink.requestMtu(LedProtocol.PREFERRED_MTU)
            gattLink.discoverServices()

            val service = gattLink.gatt?.getService(LedProtocol.SERVICE_UUID)
            val control = service?.getCharacteristic(LedProtocol.LED_CTRL_UUID)
            val infoCharacteristic = service?.getCharacteristic(LedProtocol.INFO_UUID)
            if (control == null || infoCharacteristic == null) return Outcome.Fatal("Not a Home Wall controller")

            val infoBytes = gattLink.read(infoCharacteristic)
            val info = LedProtocol.parseInfo(infoBytes) ?: return Outcome.Fatal("Not a Home Wall controller")
            log("INFO ${infoBytes.toHex()}: protocol ${info.protocolVersion}, ${info.ledCount} LEDs, ${info.paletteSlots} palette slots")
            if (info.protocolVersion != LedProtocol.VERSION) {
                return Outcome.Fatal(
                    "The controller uses protocol v${info.protocolVersion}, but this app needs v${LedProtocol.VERSION}. Update the firmware or the app.",
                )
            }

            ledCtrl = control
            sendMutex.withLock { writePackets(gattLink, control, LedProtocol.encodePalette(palette.first(), seq, mtu)) }
            link.value = ConnectionState.Connected(name, info.ledCount)
            _session.value = ++sessionCounter
            log("Connected to $name (MTU $mtu)")

            val status = gattLink.disconnected.await()
            log("Connection lost (status $status)")
            return Outcome.Dropped
        } catch (e: GattException) {
            log("Connection failed: ${e.message}")
            return if (_session.value != null) Outcome.Dropped else Outcome.Failed(e.message ?: "Connection failed")
        }
    }

    /** Null if Bluetooth can be used right now; otherwise what's in the way. */
    private fun problem(): String? = when {
        !BlePermissions.granted(context) -> "Bluetooth permission is off. Allow Nearby devices for Home Wall."
        adapter == null -> "This device has no Bluetooth"
        adapter?.isEnabled != true -> "Bluetooth is off"
        else -> null
    }

    // ---- Sending (§10.4: write with response, never larger than min(MTU − 3, 512)) ----

    override suspend fun sendPalette(slots: Map<Int, Rgb>) = send("palette") { seq, mtu -> LedProtocol.encodePalette(slots, seq, mtu) }

    override suspend fun sendFrame(frame: LedFrame) = send("frame") { seq, mtu -> LedProtocol.encodeFrame(frame, seq, mtu) }

    private suspend fun send(what: String, encode: (seq: Int, mtu: Int) -> List<ByteArray>) {
        sendMutex.withLock {
            val gattLink = activeLink
            val control = ledCtrl
            if (gattLink == null || control == null || _session.value == null) {
                log("Dropped $what: not connected")
                return
            }
            try {
                writePackets(gattLink, control, encode(seq, gattLink.mtu))
            } catch (e: GattException) {
                // Treat it as a broken connection: the reconnect brings a new session, which resends.
                log("Write failed (${e.message}); reconnecting")
                gattLink.close("failed")
                throw e
            }
        }
    }

    private suspend fun writePackets(gattLink: GattLink, control: BluetoothGattCharacteristic, packets: List<ByteArray>) {
        for (packet in packets) {
            gattLink.write(control, packet)
            seq = (seq + 1) and 0xFF
            log("TX ${packet.toHex()}")
        }
    }

    // ---- Scanning (§10.2) ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onFound(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onFound)

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed (error $errorCode)")
            scanning.value = false
            _scan.update { it.copy(scanning = false, error = "Scan failed (error $errorCode). Try again in a moment.") }
        }
    }

    private fun onFound(result: ScanResult) {
        val address = result.device.address
        val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unnamed board"
        val found = FoundDevice(address, name, result.rssi)
        _scan.update { s ->
            val i = s.devices.indexOfFirst { it.address == address }
            s.copy(devices = if (i < 0) s.devices + found else s.devices.toMutableList().also { it[i] = found })
        }
    }

    override fun startScan() {
        stopScan()
        val problem = problem()
        val scanner = adapter?.bluetoothLeScanner
        if (problem != null || scanner == null) {
            _scan.value = ScanState(error = problem ?: "Bluetooth is off")
            return
        }
        _scan.value = ScanState(scanning = true)
        scanning.value = true
        log("Scanning for ${SCAN_DURATION_MS / 1000} s")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(LedProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            scanning.value = false
            _scan.value = ScanState(error = "Bluetooth permission is off")
            return
        }
        scanTimeout = scope.launch(Dispatchers.Main) {
            delay(SCAN_DURATION_MS)
            scanTimeout = null
            stopScan()
        }
    }

    override fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        if (!scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Permission revoked mid-scan: the scan is gone either way.
        }
        scanning.value = false
        _scan.update { it.copy(scanning = false) }
        log("Scan stopped: found ${_scan.value.devices.size}")
    }

    private fun log(message: String) = log.log(TAG, message)

    private companion object {
        const val TAG = "BLE"
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_PAUSE_MS = 500L
        const val SCAN_DURATION_MS = 10_000L
    }
}
