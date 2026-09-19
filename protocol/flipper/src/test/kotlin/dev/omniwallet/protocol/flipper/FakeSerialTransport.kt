package dev.omniwallet.protocol.flipper

import com.flipperdevices.protobuf.Main
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An in-memory stand-in for the BLE link.
 *
 * This is what makes the RPC layer testable at all without hardware: it records
 * exactly what was written (so chunking and pacing can be asserted) and lets a
 * test push replies back, including split across arbitrary chunk boundaries the
 * way real notifications arrive.
 */
class FakeSerialTransport(
    override val maxWriteSize: Int = 20,
) : FlipperSerialTransport {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _creditReports = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    override val creditReports: Flow<Int> = _creditReports.asSharedFlow()

    /** Every chunk written, in order. */
    val writes = mutableListOf<ByteArray>()

    /** Every chunk concatenated, i.e. the raw outbound byte stream. */
    val writtenBytes: ByteArray get() = writes.fold(ByteArray(0)) { a, b -> a + b }

    /**
     * Optional auto-responder. When set, each complete outbound message is
     * decoded and handed over, and whatever it returns is delivered as the
     * reply -- which is what lets a test drive a whole request/response
     * conversation without hand-feeding every frame.
     */
    var responder: (suspend (Main) -> List<Main>)? = null

    private val outbound = FlipperFrameAccumulator()

    override suspend fun write(bytes: ByteArray) {
        writes += bytes
        val respond = responder ?: return
        outbound.feed(bytes).forEach { body ->
            respond(Main.ADAPTER.decode(body)).forEach { reply -> deliver(reply) }
        }
    }

    suspend fun reportCredit(bytes: Int) = _creditReports.emit(bytes)

    /** Deliver a framed reply, optionally split into [chunkSize] pieces. */
    suspend fun deliver(main: Main, chunkSize: Int = Int.MAX_VALUE) {
        val body = main.encode()
        val frame = VarInt.encode(body.size) + body
        var offset = 0
        while (offset < frame.size) {
            val size = minOf(chunkSize, frame.size - offset)
            _incoming.emit(frame.copyOfRange(offset, offset + size))
            offset += size
        }
    }

    /** Decode the request the session wrote, so assertions can inspect it. */
    fun decodeWrittenRequests(): List<Main> =
        FlipperFrameAccumulator().feed(writtenBytes).map { Main.ADAPTER.decode(it) }
}
