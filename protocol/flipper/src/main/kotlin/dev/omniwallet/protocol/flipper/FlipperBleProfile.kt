package dev.omniwallet.protocol.flipper

import java.util.UUID

/**
 * The Flipper Zero's BLE serial profile.
 *
 * Read from firmware, `targets/f7/ble_glue/services/serial_service_uuid.inc`.
 * The ST BLE stack stores 128-bit UUIDs least-significant byte first, so the
 * byte arrays there appear reversed relative to these strings.
 *
 * Verified byte-for-byte identical in official firmware, Unleashed and
 * Momentum -- which is why device discovery filters on [SERVICE] and never on
 * the device name: Momentum lets users rename their Flipper, but no firmware
 * changes this UUID.
 */
object FlipperBleProfile {

    val SERVICE: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")

    /** Device -> phone. Notifications; chunked by the firmware. */
    val TX_CHARACTERISTIC: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")

    /** Phone -> device. Writes, paced by [FLOW_CONTROL_CHARACTERISTIC]. */
    val RX_CHARACTERISTIC: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")

    /** Big-endian uint32: how many bytes the device can currently accept. */
    val FLOW_CONTROL_CHARACTERISTIC: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000")

    /** Reports whether an RPC session is active. */
    val RPC_STATUS_CHARACTERISTIC: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e64fe0000")

    /**
     * Values of [RPC_STATUS_CHARACTERISTIC], from `SerialServiceRpcStatus`.
     *
     * Worth stating plainly: over BLE there is no `start_rpc_session` text
     * command. That is a USB-serial-only handshake. On BLE the firmware brings
     * the RPC session up with the serial service itself, and this
     * characteristic is how we confirm it.
     */
    object RpcStatus {
        const val NOT_ACTIVE = 0
        const val ACTIVE = 1

        /**
         * Decode the characteristic value, reading the **first byte only**.
         *
         * The firmware declares this characteristic `sizeof(uint32_t)`, which
         * invites treating it as a uint32. Hardware disagrees: a live session
         * reads `01-14-6E-0B`, where byte 0 is the status and bytes 1-3 are
         * whatever sat beside the enum in memory, because the fixed four-byte
         * length over-reads a smaller value. Taken as a little-endian uint32
         * that is 0x0B6E1401, and a perfectly healthy session reports itself
         * inactive.
         *
         * Do not widen this on the strength of the declared length. A test
         * pins the observed bytes.
         */
        fun isActive(raw: ByteArray): Boolean =
            raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == ACTIVE
    }
}
