package dev.omniwallet.protocol.chameleon

/**
 * Command codes, read from the vendor client's `chameleon_enum.py`.
 *
 * Only the subset this app needs is listed. The full emulation flow lands at
 * M5; these exist now so the codec can be tested against real values.
 */
object ChameleonCommand {
    const val GET_APP_VERSION = 1000
    const val GET_DEVICE_MODE = 1002
    const val SET_ACTIVE_SLOT = 1003
    const val SET_SLOT_TAG_TYPE = 1004
    const val SET_SLOT_ENABLE = 1006
    const val SET_SLOT_TAG_NICK = 1007
    const val GET_SLOT_TAG_NICK = 1008
    const val SLOT_DATA_CONFIG_SAVE = 1009
    const val GET_ACTIVE_SLOT = 1018
    const val GET_SLOT_INFO = 1019
    const val GET_ENABLED_SLOTS = 1023
    const val GET_DEVICE_MODEL = 1033
    const val GET_DEVICE_CAPABILITIES = 1035

    const val MF1_WRITE_EMU_BLOCK_DATA = 4000
    const val HF14A_SET_ANTI_COLL_DATA = 4001
}

object ChameleonStatus {
    const val SUCCESS = 0x68
}

/** The device has eight slots, each holding one LF and one HF tag. */
const val CHAMELEON_SLOT_COUNT = 8
