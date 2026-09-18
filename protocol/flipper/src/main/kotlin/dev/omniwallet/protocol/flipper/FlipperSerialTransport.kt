package dev.omniwallet.protocol.flipper

import kotlinx.coroutines.flow.Flow

/**
 * The byte pipe [FlipperRpcSession] runs over.
 *
 * Kept as an interface, and kept in this pure-JVM module, so the whole RPC
 * layer -- framing, correlation, flow control, `has_next` assembly -- can be
 * tested against a fake without a phone, an emulator or a Flipper. The Android
 * implementation that binds this to GATT lives in `:device:flipper`.
 */
interface FlipperSerialTransport {

    /** Raw bytes from the TX characteristic, in arrival order. */
    val incoming: Flow<ByteArray>

    /**
     * Decoded flow-control readings from the flow-control characteristic.
     * Each value is the device's absolute free-buffer figure, not a delta.
     */
    val creditReports: Flow<Int>

    /** Largest single write the link accepts, i.e. negotiated MTU minus 3. */
    val maxWriteSize: Int

    /** Write one chunk to the RX characteristic. */
    suspend fun write(bytes: ByteArray)
}
