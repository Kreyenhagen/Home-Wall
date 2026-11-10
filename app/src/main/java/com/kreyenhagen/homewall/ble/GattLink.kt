package com.kreyenhagen.homewall.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import com.kreyenhagen.homewall.led.LedProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One GATT connection as suspend functions (§10.4): one operation at a time, each awaited through
 * its callback with a timeout. Not reused: a new connection gets a new link.
 *
 * Permissions are checked by [BleLedController] before a link is created.
 */
@SuppressLint("MissingPermission")
internal class GattLink : BluetoothGattCallback() {

    private enum class Op { CONNECT, MTU, DISCOVER, READ, WRITE }

    private class Pending(val op: Op, val result: CompletableDeferred<Any> = CompletableDeferred())

    private val mutex = Mutex()

    @Volatile
    private var pending: Pending? = null

    @Volatile
    var gatt: BluetoothGatt? = null
        private set

    /** The negotiated MTU; the default until [requestMtu] succeeds. */
    @Volatile
    var mtu: Int = LedProtocol.DEFAULT_MTU
        private set

    /** Completes with the GATT status when the connection ends, for whatever reason. */
    val disconnected = CompletableDeferred<Int>()

    // Deprecated in API 37 for connectGatt(BluetoothGattConnectionSettings, …), which older Android
    // versions (the tablet included) don't have. This is the §10.3 call; switch once minSdk reaches 37.
    @Suppress("DEPRECATION")
    suspend fun connect(context: Context, device: BluetoothDevice) {
        run(Op.CONNECT, CONNECT_TIMEOUT_MS) {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
    }

    fun requestHighPriority() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    /**
     * Returns the MTU in use. Some stacks refuse a request made right after the connection-priority
     * change, so it's tried twice; if it still fails, the default MTU is used rather than failing.
     */
    suspend fun requestMtu(preferred: Int): Int {
        repeat(2) { attempt ->
            try {
                run(Op.MTU) { gatt?.requestMtu(preferred) == true }
                return mtu
            } catch (e: GattException) {
                if (disconnected.isCompleted) throw e
                if (attempt == 0) delay(MTU_RETRY_DELAY_MS)
            }
        }
        return mtu
    }

    suspend fun discoverServices() {
        run(Op.DISCOVER) { gatt?.discoverServices() == true }
    }

    suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray =
        run(Op.READ) { gatt?.readCharacteristic(characteristic) == true } as ByteArray

    /** Write with response (§10.4); returns once the controller has acknowledged it. */
    suspend fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        run(Op.WRITE) {
            gatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    /** Ends the connection (if any) and releases it. Safe to call more than once. */
    fun close(reason: String = "closed") {
        pending?.result?.completeExceptionally(GattException("Connection $reason"))
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        disconnected.complete(CLOSED_BY_APP)
    }

    private suspend fun run(op: Op, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): Any = mutex.withLock {
        if (disconnected.isCompleted) throw GattException("Not connected")
        val p = Pending(op)
        pending = p
        try {
            if (!start()) throw GattException("Couldn't start ${op.label}")
            withTimeoutOrNull(timeoutMs) { p.result.await() } ?: throw GattException("${op.label.replaceFirstChar { it.uppercase() }} timed out")
        } finally {
            pending = null
        }
    }

    private fun succeed(op: Op, value: Any) {
        pending?.takeIf { it.op == op }?.result?.complete(value)
    }

    private fun fail(op: Op, status: Int) {
        pending?.takeIf { it.op == op }?.result?.completeExceptionally(GattException("${op.label} failed (status $status)", status))
    }

    // ---- Callbacks (binder threads) ----

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        when {
            newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> succeed(Op.CONNECT, Unit)
            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                // Status 133 here is the common "couldn't connect" error; the caller closes and retries.
                pending?.result?.completeExceptionally(GattException("Disconnected (status $status)", status))
                disconnected.complete(status)
            }
        }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            this.mtu = mtu
            succeed(Op.MTU, mtu)
        } else {
            fail(Op.MTU, status)
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) succeed(Op.DISCOVER, Unit) else fail(Op.DISCOVER, status)
    }

    override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) succeed(Op.READ, value) else fail(Op.READ, status)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) succeed(Op.WRITE, Unit) else fail(Op.WRITE, status)
    }

    private val Op.label: String
        get() = when (this) {
            Op.CONNECT -> "connect"
            Op.MTU -> "MTU request"
            Op.DISCOVER -> "service discovery"
            Op.READ -> "read"
            Op.WRITE -> "write"
        }

    companion object {
        const val OP_TIMEOUT_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val MTU_RETRY_DELAY_MS = 200L
        const val CLOSED_BY_APP = -1
    }
}
