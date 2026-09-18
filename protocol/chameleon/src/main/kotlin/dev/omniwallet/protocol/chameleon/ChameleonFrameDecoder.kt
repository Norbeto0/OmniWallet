package dev.omniwallet.protocol.chameleon

/**
 * Incremental decoder for the response stream.
 *
 * BLE hands us arbitrary notification-sized chunks with no relationship to
 * frame boundaries: one notification may carry half a frame, or three frames
 * and a fragment. So the decoder buffers and yields whole frames only.
 *
 * Not thread-safe; drive it from a single reader coroutine.
 */
class ChameleonFrameDecoder {

    private var buffer = ByteArray(0)
    private val errors = mutableListOf<ChameleonDecodeError>()

    /** Decode errors seen since the last call, oldest first. */
    fun drainErrors(): List<ChameleonDecodeError> = errors.toList().also { errors.clear() }

    /** Bytes currently held pending more input. Exposed for tests and diagnostics. */
    val pending: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
        errors.clear()
    }

    /** Outcome of one pass over the buffer. */
    private sealed interface Step {
        data class Emit(val response: ChameleonResponse) : Step

        /** Buffer is short; wait for more bytes. */
        data object NeedMore : Step

        /**
         * The buffer was advanced past bad data. Distinct from [NeedMore]
         * because we must immediately re-examine what is left -- a corrupt
         * frame is very often followed by a perfectly good one, and treating
         * resync as "wait" would strand it until the next notification.
         */
        data object Resynced : Step
    }

    /** Feed freshly received bytes; returns every frame that completed. */
    fun feed(chunk: ByteArray): List<ChameleonResponse> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk

        val out = mutableListOf<ChameleonResponse>()
        while (true) {
            when (val step = step()) {
                is Step.Emit -> out += step.response
                Step.Resynced -> Unit
                Step.NeedMore -> return out
            }
        }
    }

    /** Drop [count] leading bytes and record [error]. */
    private fun resync(count: Int, error: ChameleonDecodeError): Step {
        errors += error
        buffer = buffer.copyOfRange(count, buffer.size)
        return Step.Resynced
    }

    private fun step(): Step {
        if (buffer.isEmpty()) return Step.NeedMore

        // Realign to a plausible SOF so one stray byte cannot wedge the stream.
        val sof = buffer.indexOfFirst { it == ChameleonFrame.SOF }
        if (sof < 0) return resync(buffer.size, ChameleonDecodeError.BadSof)
        if (sof > 0) return resync(sof, ChameleonDecodeError.BadSof)

        if (buffer.size < ChameleonFrame.HEADER_SIZE) return Step.NeedMore

        if ((buffer[1].toInt() and 0xFF) != ChameleonFrame.lrc(buffer, 0, 1) ||
            (buffer[8].toInt() and 0xFF) != ChameleonFrame.lrc(buffer, 0, 8)
        ) {
            return resync(1, ChameleonDecodeError.BadHeaderLrc)
        }

        val command = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val status = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val length = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)

        if (length > ChameleonFrame.MAX_PAYLOAD) {
            return resync(1, ChameleonDecodeError.PayloadTooLong(length))
        }

        val total = ChameleonFrame.OVERHEAD + length
        if (buffer.size < total) return Step.NeedMore

        if ((buffer[total - 1].toInt() and 0xFF) != ChameleonFrame.lrc(buffer, 0, total - 1)) {
            return resync(1, ChameleonDecodeError.BadPayloadLrc)
        }

        val payload = buffer.copyOfRange(ChameleonFrame.HEADER_SIZE, total - 1)
        buffer = buffer.copyOfRange(total, buffer.size)
        return Step.Emit(ChameleonResponse(command, status, payload))
    }
}
