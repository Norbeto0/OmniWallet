package dev.omniwallet.device.flipper

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import dev.omniwallet.protocol.flipper.FlipperBleProfile
import dev.omniwallet.protocol.flipper.FlowControlGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend

/**
 * GATT plumbing for the Flipper's BLE serial service.
 *
 * Several details here come from the firmware's characteristic table
 * (`targets/f7/ble_glue/services/serial_service.c`) and are not guesses:
 *
 *  - **TX is `CHAR_PROP_INDICATE`, not notify.** Subscribing with notifications
 *    silently yields no data at all, which is indistinguishable from a device
 *    that simply never answers.
 *  - **Flow control is read + notify**, so the initial value is read once on
 *    connect and updates arrive as notifications afterwards.
 *  - **RX and TX carry `ATTR_PERMISSION_AUTHEN_READ/WRITE`.** The link must be
 *    authenticated, so bonding is mandatory rather than optional: the first
 *    access triggers pairing, and until it completes every operation fails with
 *    an authentication error.
 *  - **RX accepts write-without-response**, which is what the credit-based flow
 *    control is designed around, so that is the write type used.
 */
class FlipperBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "FlipperBleManager"

        /** ATT overhead: three bytes of the MTU are the opcode and handle. */
        const val ATT_HEADER_SIZE = 3

        /** Ask for the maximum; the negotiated value is whatever both sides support. */
        const val DESIRED_MTU = 517

        private const val DEFAULT_WRITE_SIZE = 20
    }

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var flowControlCharacteristic: BluetoothGattCharacteristic? = null
    private var rpcStatusCharacteristic: BluetoothGattCharacteristic? = null

    // No replay: the device only speaks in response to a request, and the RPC
    // session subscribes before the first one is sent. Buffer is generous so a
    // burst of indications cannot outrun the reader.
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    /**
     * `replay = 1` is load-bearing. The initial flow-control value is read
     * during [initialize], which completes before the RPC session exists and
     * subscribes. Without replay that first reading would be dropped, the
     * session would start believing it had zero credit, and the very first
     * request would stall until the device happened to notify on its own.
     */
    private val _creditReports = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 64,
    )
    val creditReports: Flow<Int> = _creditReports.asSharedFlow()

    private val _rpcActive = MutableStateFlow(false)
    val rpcActive: StateFlow<Boolean> = _rpcActive.asStateFlow()

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 256)

    /** Verbose GATT log, surfaced on the diagnostics screen. */
    val gattLog: Flow<String> = _log.asSharedFlow()

    /** The MTU actually negotiated. `mtu` itself is protected in BleManager. */
    val negotiatedMtu: Int get() = mtu

    /** Largest payload per write: negotiated MTU minus ATT overhead. */
    val maxWriteSize: Int
        get() = (mtu - ATT_HEADER_SIZE).coerceAtLeast(DEFAULT_WRITE_SIZE)

    override fun getMinLogPriority(): Int = Log.VERBOSE

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
        _log.tryEmit(message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(FlipperBleProfile.SERVICE) ?: return false

        txCharacteristic = service.getCharacteristic(FlipperBleProfile.TX_CHARACTERISTIC)
        rxCharacteristic = service.getCharacteristic(FlipperBleProfile.RX_CHARACTERISTIC)
        flowControlCharacteristic =
            service.getCharacteristic(FlipperBleProfile.FLOW_CONTROL_CHARACTERISTIC)
        rpcStatusCharacteristic =
            service.getCharacteristic(FlipperBleProfile.RPC_STATUS_CHARACTERISTIC)

        // The RPC status characteristic is not required: it is useful
        // confirmation, not a dependency.
        return txCharacteristic != null &&
            rxCharacteristic != null &&
            flowControlCharacteristic != null
    }

    override fun initialize() {
        requestMtu(DESIRED_MTU).enqueue()

        // TX is INDICATE. Using notifications here is the classic silent
        // failure: everything reports success and no data ever arrives.
        setIndicationCallback(txCharacteristic).with { _, data ->
            data.value?.let { _incoming.tryEmit(it) }
        }
        enableIndications(txCharacteristic).enqueue()

        setNotificationCallback(flowControlCharacteristic).with { _, data ->
            data.value?.let { emitCredit(it) }
        }
        enableNotifications(flowControlCharacteristic).enqueue()

        // Seed the credit before any write: the firmware sets this to the full
        // buffer size when the service starts, and without reading it we would
        // begin with a balance of zero and stall on the very first request.
        readCharacteristic(flowControlCharacteristic).with { _, data ->
            data.value?.let { emitCredit(it) }
        }.enqueue()

        rpcStatusCharacteristic?.let { status ->
            setNotificationCallback(status).with { _, data -> applyRpcStatus(data) }
            enableNotifications(status).enqueue()
            readCharacteristic(status).with { _, data -> applyRpcStatus(data) }.enqueue()
        }
    }

    /** Parsing lives in [FlipperBleProfile.RpcStatus]; see the note there. */
    private fun applyRpcStatus(data: Data) {
        val raw = data.value ?: return
        _rpcActive.value = FlipperBleProfile.RpcStatus.isActive(raw)
    }

    private fun emitCredit(raw: ByteArray) {
        if (raw.size < 4) return
        _creditReports.tryEmit(FlowControlGate.decodeCredit(raw))
    }

    override fun onServicesInvalidated() {
        txCharacteristic = null
        rxCharacteristic = null
        flowControlCharacteristic = null
        rpcStatusCharacteristic = null
        _rpcActive.value = false
    }

    /** Write one chunk to RX. Pacing is the caller's job, via the credit gate. */
    suspend fun writeChunk(bytes: ByteArray) {
        val characteristic = rxCharacteristic
            ?: error("RX characteristic unavailable; not connected")
        writeCharacteristic(
            characteristic,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        ).suspend()
    }

    /** Disconnect, swallowing the error from an already-dead link. */
    suspend fun disconnectSafely() {
        runCatching { disconnect().suspend() }
    }

    suspend fun connectTo(device: BluetoothDevice, timeoutMillis: Long) {
        connect(device)
            // autoConnect is slow to establish and unreliable for a first
            // connection; the supervisor owns retries instead.
            .useAutoConnect(false)
            .timeout(timeoutMillis)
            .suspend()
    }
}
