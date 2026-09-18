package dev.omniwallet.device.flipper

import dev.omniwallet.protocol.flipper.FlipperSerialTransport
import kotlinx.coroutines.flow.Flow

/**
 * Binds the GATT layer to the pure-JVM RPC layer.
 *
 * Exists so [dev.omniwallet.protocol.flipper.FlipperRpcSession] never touches
 * an Android type, which is what keeps the framing and flow-control logic
 * testable against a fake transport rather than a phone.
 */
internal class FlipperTransport(
    private val manager: FlipperBleManager,
) : FlipperSerialTransport {

    override val incoming: Flow<ByteArray> get() = manager.incoming

    override val creditReports: Flow<Int> get() = manager.creditReports

    override val maxWriteSize: Int get() = manager.maxWriteSize

    override suspend fun write(bytes: ByteArray) = manager.writeChunk(bytes)
}
