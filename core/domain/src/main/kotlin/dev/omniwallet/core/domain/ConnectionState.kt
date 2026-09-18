package dev.omniwallet.core.domain

/**
 * The state of one device's link.
 *
 * Scanning is deliberately absent: it is a property of the transport's search
 * for devices, not of any single device's connection, and conflating the two is
 * what makes BLE state machines rot.
 */
sealed interface ConnectionState {

    /** No link, and none being attempted. */
    data object Disconnected : ConnectionState

    /** Opening the GATT connection. [attempt] starts at 1. */
    data class Connecting(val attempt: Int) : ConnectionState

    /** Connected at the GATT level; discovering services and enabling notifications. */
    data object Discovering : ConnectionState

    /** Services resolved, notifications enabled: the device will accept commands. */
    data object Ready : ConnectionState

    /**
     * The link dropped unintentionally and a retry is scheduled.
     * A user-initiated disconnect must never produce this state.
     */
    data class Reconnecting(
        val attempt: Int,
        val nextRetryInMillis: Long,
    ) : ConnectionState

    /** Terminal for this attempt; [reason] tells the UI what to say. */
    data class Failed(val reason: FailureReason) : ConnectionState
}

/**
 * Why a connection could not be established or was lost.
 *
 * [PAIRING_REQUIRED] is the one the user will actually hit: the Flipper
 * initiates a security request and terminates the link when pairing fails
 * (targets/f7/ble_glue/gap.c). Changing firmware -- e.g. official to Momentum --
 * regenerates the device keys, so a bond Android still holds goes stale and
 * presents as an immediate drop right after connecting. The only fix is for the
 * user to forget the device in Android's Bluetooth settings and pair again, so
 * we must detect it and say so rather than retrying forever.
 */
enum class FailureReason {
    BLUETOOTH_DISABLED,
    PERMISSIONS_MISSING,
    PAIRING_REQUIRED,
    DEVICE_NOT_FOUND,
    LINK_LOST,
    SERVICE_NOT_FOUND,
    TIMEOUT,
    UNKNOWN,
}
