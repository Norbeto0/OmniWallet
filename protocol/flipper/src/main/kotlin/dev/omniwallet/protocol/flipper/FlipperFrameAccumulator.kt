package dev.omniwallet.protocol.flipper

/**
 * Reassembles length-delimited `PB_Main` messages from the BLE TX stream.
 *
 * The firmware splits outgoing data across notifications
 * (`ble_svc_serial_update_tx` chunks at the characteristic value length), and
 * notification boundaries have no relationship to message boundaries. So a
 * single notification may contain part of a message, several whole messages,
 * or any mixture. This buffers and yields only complete message bodies.
 *
 * Not thread-safe; drive it from one reader coroutine.
 */
class FlipperFrameAccumulator(
    private val maxMessageSize: Int = DEFAULT_MAX_MESSAGE_SIZE,
) {

    companion object {
        /**
         * Guard against a corrupt length prefix causing an unbounded buffer.
         * Comfortably larger than any RPC message this app exchanges.
         */
        const val DEFAULT_MAX_MESSAGE_SIZE = 1 shl 20
    }

    class MalformedStreamException(message: String) : Exception(message)

    private var buffer = ByteArray(0)

    val pending: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
    }

    /**
     * Feed received bytes; returns each complete message body, in order.
     * The returned arrays exclude the varint length prefix.
     *
     * @throws MalformedStreamException if the length prefix cannot be trusted,
     *   which means the stream is desynchronised and the link should be reset.
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk

        val out = mutableListOf<ByteArray>()
        while (true) {
            when (val header = VarInt.decode(buffer)) {
                is VarInt.Decoded.Incomplete -> return out

                VarInt.Decoded.Malformed ->
                    throw MalformedStreamException("varint length prefix is malformed")

                is VarInt.Decoded.Value -> {
                    val length = header.value
                    if (length < 0 || length > maxMessageSize) {
                        throw MalformedStreamException(
                            "declared message length $length exceeds maximum $maxMessageSize",
                        )
                    }
                    val total = header.bytesRead + length
                    if (buffer.size < total) return out

                    out += buffer.copyOfRange(header.bytesRead, total)
                    buffer = buffer.copyOfRange(total, buffer.size)
                }
            }
        }
    }
}
