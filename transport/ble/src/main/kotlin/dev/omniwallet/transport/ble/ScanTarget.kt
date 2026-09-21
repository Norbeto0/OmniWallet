package dev.omniwallet.transport.ble

import dev.omniwallet.core.domain.DeviceKind
import java.util.UUID

/**
 * One advertisement pattern worth scanning for.
 *
 * A plain UUID is not always enough. The Flipper advertises a 16-bit service
 * UUID of `0x3080 | hw_color` (`targets/f7/ble_glue/profiles/serial_profile.c`),
 * so the value differs per device colour and no single UUID matches every
 * Flipper. [mask] covers that: the low nibble is wildcarded and all colours
 * match one filter.
 */
data class ScanTarget(
    val kind: DeviceKind,
    /** The UUID to match, with wildcard positions zeroed. */
    val serviceUuid: UUID,
    /** Null matches [serviceUuid] exactly; otherwise only the set bits matter. */
    val mask: UUID? = null,
    /**
     * Optional substrings the advertised name must contain, case-insensitively.
     *
     * Needed because some services are not distinctive. The Chameleon speaks
     * over the standard Nordic UART Service, which many unrelated devices also
     * advertise -- a real scan turned up a device called "Camera" that the app
     * cheerfully labelled a Chameleon Ultra. When the service alone cannot
     * identify hardware, saying "unrecognised" beats guessing.
     */
    val nameHints: List<String> = emptyList(),
) {
    /** Whether an advertised UUID matches this target, ignoring the name. */
    fun matches(advertised: UUID): Boolean {
        val m = mask ?: return advertised == serviceUuid
        return (advertised.mostSignificantBits and m.mostSignificantBits) ==
            (serviceUuid.mostSignificantBits and m.mostSignificantBits) &&
            (advertised.leastSignificantBits and m.leastSignificantBits) ==
            (serviceUuid.leastSignificantBits and m.leastSignificantBits)
    }

    /**
     * Whether an advertisement identifies this device.
     *
     * With no [nameHints] the UUID is enough. With them, the name must also
     * look right -- and an advertisement with no name at all cannot satisfy
     * that, so it stays unidentified rather than being assumed.
     */
    fun identifies(advertised: UUID, name: String?): Boolean {
        if (!matches(advertised)) return false
        if (nameHints.isEmpty()) return true
        val candidate = name ?: return false
        return nameHints.any { candidate.contains(it, ignoreCase = true) }
    }

    companion object {
        /** Expand a 16-bit Bluetooth SIG short UUID to its 128-bit form. */
        fun shortUuid(value: Int): UUID {
            require(value in 0..0xFFFF) { "not a 16-bit UUID: $value" }
            return UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", value))
        }

        /** Matches the low [bits] of a 16-bit UUID as wildcards. */
        fun shortUuidMask(wildcardBits: Int): UUID {
            require(wildcardBits in 0..16) { "wildcardBits out of range: $wildcardBits" }
            val keep = (0xFFFFFFFFL and (0xFFFFFFFFL shl wildcardBits))
            return UUID.fromString(
                String.format("%08x-ffff-ffff-ffff-ffffffffffff", keep and 0xFFFFFFFFL),
            )
        }
    }
}
