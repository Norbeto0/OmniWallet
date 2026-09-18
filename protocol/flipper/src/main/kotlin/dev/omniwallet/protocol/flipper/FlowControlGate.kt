package dev.omniwallet.protocol.flipper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Paces writes to the Flipper's RX characteristic so its receive buffer cannot
 * overflow.
 *
 * How the firmware actually behaves (`targets/f7/ble_glue/services/serial_service.c`):
 *
 *  - The flow-control characteristic holds a **big-endian uint32**: the number
 *    of bytes the device is currently ready to receive.
 *  - Every write to RX decrements that counter by the write's length. If a
 *    write exceeds what remains, the firmware logs
 *    "Can lead to buffer overflow!" and data is lost.
 *  - When the device's buffer drains, `ble_svc_serial_notify_buffer_is_empty`
 *    resets the counter to the **full buffer size** and notifies.
 *
 * The consequence that is easy to get wrong: a notification carries an
 * **absolute** figure, not a delta. A client that adds the notified value to a
 * running total will steadily overestimate its allowance and overflow the
 * device. Hence [onCreditReported] assigns rather than accumulates.
 */
class FlowControlGate(initialCredit: Int = 0) {

    private val _credit = MutableStateFlow(initialCredit)

    /** Bytes the device can currently accept. Surfaced for the diagnostics screen. */
    val credit: StateFlow<Int> = _credit.asStateFlow()

    /** Largest credit ever reported, i.e. the device's buffer size. */
    @Volatile
    var observedBufferSize: Int = initialCredit
        private set

    // Serialises writers so a check-then-consume pair cannot interleave.
    private val gate = Mutex()

    /**
     * Credit never arrived. Covers both a device that stopped reporting and a
     * chunk that could never fit in its buffer -- either way the session is
     * wedged and the caller needs to hear about it rather than hang.
     */
    class CreditStalledException(
        val requested: Int,
        val available: Int,
        val observedBufferSize: Int,
    ) : Exception(
        "timed out waiting for flow-control credit: needed $requested bytes, " +
            "device offered $available (largest buffer seen: $observedBufferSize)",
    )

    /**
     * Apply a flow-control reading. [bytes] is absolute: the device's own
     * statement of what it can accept right now.
     */
    fun onCreditReported(bytes: Int) {
        if (bytes > observedBufferSize) observedBufferSize = bytes
        _credit.value = bytes
    }

    /** Decode a big-endian uint32 flow-control value and apply it. */
    fun onCreditReported(raw: ByteArray) {
        onCreditReported(decodeCredit(raw))
    }

    /**
     * Suspend until [bytes] of credit are available, then consume them.
     *
     * Deliberately bounded by [stallTimeoutMillis] rather than by a guess at
     * the device's buffer size. The largest credit seen so far is only a lower
     * bound on the real buffer -- a reading taken while the device is partly
     * full understates it -- so refusing a write on that basis would reject
     * perfectly good traffic. A timeout instead catches every way this can
     * wedge: a chunk too large to ever fit, a device that stopped reporting,
     * or a lost notification.
     */
    suspend fun acquire(bytes: Int, stallTimeoutMillis: Long = DEFAULT_STALL_TIMEOUT_MILLIS) {
        require(bytes >= 0) { "negative write size: $bytes" }
        if (bytes == 0) return

        gate.withLock {
            try {
                withTimeout(stallTimeoutMillis) { _credit.first { it >= bytes } }
            } catch (e: TimeoutCancellationException) {
                throw CreditStalledException(bytes, _credit.value, observedBufferSize)
            }
            _credit.value = _credit.value - bytes
        }
    }

    companion object {
        /** Long enough to ride out a busy device, short enough to surface a wedge. */
        const val DEFAULT_STALL_TIMEOUT_MILLIS = 15_000L

        /**
         * The characteristic is written with `REVERSE_BYTES_U32` on a
         * little-endian MCU, so the wire value is big-endian.
         */
        fun decodeCredit(raw: ByteArray): Int {
            require(raw.size >= 4) { "flow control value must be 4 bytes, got ${raw.size}" }
            return ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
        }
    }
}
