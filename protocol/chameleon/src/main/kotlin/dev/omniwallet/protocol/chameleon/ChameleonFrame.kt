package dev.omniwallet.protocol.chameleon

/**
 * Chameleon Ultra command framing.
 *
 * Layout confirmed from the vendor client (`software/script/chameleon_com.py`,
 * `make_data_frame_bytes`, struct format `!BBHHHB{len}sB` -- `!` is network
 * byte order, so every multi-byte field is big-endian):
 *
 * ```
 * offset  size  field
 *   0      1    SOF = 0x11
 *   1      1    LRC1  -- over bytes [0, 1)          (i.e. the SOF alone)
 *   2      2    command        (uint16 BE)
 *   4      2    status         (uint16 BE)
 *   6      2    payload length (uint16 BE)
 *   8      1    LRC2  -- over bytes [0, 8)          (the header)
 *   9      N    payload
 *   9+N    1    LRC3  -- over bytes [0, 9+N)        (everything preceding)
 * ```
 *
 * Note this is a different shape entirely from the Flipper's protobuf-over-BLE
 * serial, which is the point: the [dev.omniwallet.core.domain.EmulatorDevice]
 * abstraction has to hold for both.
 */
object ChameleonFrame {

    const val SOF: Byte = 0x11
    const val HEADER_SIZE = 9
    const val OVERHEAD = HEADER_SIZE + 1
    const val MAX_PAYLOAD = 4096

    /**
     * Longitudinal redundancy check, as `lrc_calc` in the vendor client:
     * the two's complement of the running byte sum. The useful property is that
     * the sum of a span *including* its LRC is always 0 mod 256.
     */
    fun lrc(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var sum = 0
        for (i in from until until) {
            sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        }
        return (0x100 - sum) and 0xFF
    }

    fun encode(command: Int, payload: ByteArray = ByteArray(0), status: Int = 0): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "payload ${payload.size} exceeds max $MAX_PAYLOAD"
        }
        val frame = ByteArray(OVERHEAD + payload.size)
        frame[0] = SOF
        frame[1] = lrc(frame, 0, 1).toByte()
        frame[2] = (command ushr 8).toByte()
        frame[3] = command.toByte()
        frame[4] = (status ushr 8).toByte()
        frame[5] = status.toByte()
        frame[6] = (payload.size ushr 8).toByte()
        frame[7] = payload.size.toByte()
        frame[8] = lrc(frame, 0, 8).toByte()
        payload.copyInto(frame, HEADER_SIZE)
        frame[HEADER_SIZE + payload.size] = lrc(frame, 0, HEADER_SIZE + payload.size).toByte()
        return frame
    }
}

/** A decoded response frame. */
data class ChameleonResponse(
    val command: Int,
    val status: Int,
    val payload: ByteArray,
) {
    val isOk: Boolean get() = status == ChameleonStatus.SUCCESS

    // ByteArray needs structural equality spelled out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChameleonResponse) return false
        return command == other.command &&
            status == other.status &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        (command * 31 + status) * 31 + payload.contentHashCode()
}

/** Why a byte stream could not be decoded. */
sealed class ChameleonDecodeError(val detail: String) {
    data object BadSof : ChameleonDecodeError("first byte is not 0x11")
    data object BadHeaderLrc : ChameleonDecodeError("header LRC mismatch")
    data object BadPayloadLrc : ChameleonDecodeError("payload LRC mismatch")
    data class PayloadTooLong(val length: Int) :
        ChameleonDecodeError("declared payload length $length exceeds maximum")
}
