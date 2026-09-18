package dev.omniwallet.core.domain

/**
 * A radio/wire protocol an external device can emit.
 *
 * The phone never emits any of these itself -- Android HCE cannot present an
 * arbitrary UID or speak Mifare Classic, which is the whole reason the external
 * device exists.
 */
enum class Protocol {
    NFC,
    RFID_125K,
    SUBGHZ,
    IBUTTON,
    INFRARED,
}

/** A supported piece of external hardware. */
enum class DeviceKind(val displayName: String) {
    FLIPPER_ZERO("Flipper Zero"),
    CHAMELEON_ULTRA("Chameleon Ultra"),
}
