package dev.omniwallet.protocol.flipper

/**
 * What the connected Flipper is running, and whether this app knows it.
 *
 * ### The posture, which matters more than the code
 *
 * This **reports**, it does not gate. `docs/PROTOCOL-NOTES.md` records a
 * fork-by-fork diff of Momentum and Unleashed against official 0.25: the
 * serial UUIDs, the flow control, all five app names and
 * `application/storage/system.proto` are byte-identical, and every custom
 * delta is an *added* field in messages this app does not send. Protobuf's
 * unknown-field handling covers the rest.
 *
 * So an unrecognised fork is very likely fine, and refusing to work on one
 * would be the app inventing a problem the protocol does not have. It says
 * what it found and gets out of the way. The one thing worth flagging is a
 * protobuf major version this app was not built against, because that is the
 * single change that could actually break the wire format.
 *
 * Pure and in the protocol module, like everything else here that is easier to
 * be confidently wrong about than to test on hardware.
 */
object FirmwareCompatibility {

    /**
     * The protobuf major version this app's vendored schema came from.
     *
     * Official 0.25 (`1c84fa4`). A device reporting a *higher* major is the
     * only case where "unknown fork" stops being cosmetic.
     */
    const val VENDORED_PROTOBUF_MAJOR = 0

    /**
     * Forks whose deltas have actually been diffed.
     *
     * Matched case-insensitively on `firmware_origin_fork`. Official firmware
     * does not report the key at all, which is itself the signal.
     */
    val KNOWN_FORKS = setOf("momentum", "unleashed", "roguemaster", "xtreme")

    enum class Confidence {
        /** Diffed against the vendored schema, or official firmware. */
        VERIFIED,

        /** Unrecognised, and almost certainly fine. Reported, not blocked. */
        UNTESTED,

        /** A protobuf major this app was not built against. */
        INCOMPATIBLE,
    }

    data class Report(
        val fork: String,
        val version: String?,
        val hardwareName: String?,
        val protobufMajor: Int?,
        val protobufMinor: Int?,
        val confidence: Confidence,
        /** One sentence for the Device screen. */
        val summary: String,
    ) {
        /** What the Device screen shows next to the connection. */
        val label: String get() = listOfNotNull(fork, version).joinToString(" ").trim()
    }

    /**
     * Read a `device_info` map.
     *
     * Key names are from hardware, not from memory: there is **no**
     * `firmware_origin` key. Momentum reports `firmware_origin_fork` and
     * `firmware_origin_git`; that was established from a diagnostics log and is
     * recorded in `docs/PROTOCOL-NOTES.md`.
     */
    fun inspect(deviceInfo: Map<String, String>): Report {
        val fork = deviceInfo["firmware_origin_fork"]?.takeIf { it.isNotBlank() } ?: "Official"
        val version = deviceInfo["firmware_version"]?.takeIf { it.isNotBlank() }
        val hardware = deviceInfo["hardware_name"]?.takeIf { it.isNotBlank() }

        val major = deviceInfo["protobuf_version_major"]?.toIntOrNull()
        val minor = deviceInfo["protobuf_version_minor"]?.toIntOrNull()

        val known = fork.equals("Official", ignoreCase = true) ||
            fork.lowercase() in KNOWN_FORKS

        val confidence = when {
            // Only a major bump can move a field number or change framing.
            // A minor is additive by definition, so it is not a concern.
            major != null && major > VENDORED_PROTOBUF_MAJOR -> Confidence.INCOMPATIBLE
            known -> Confidence.VERIFIED
            else -> Confidence.UNTESTED
        }

        return Report(
            fork = fork,
            version = version,
            hardwareName = hardware,
            protobufMajor = major,
            protobufMinor = minor,
            confidence = confidence,
            summary = summarise(fork, confidence, major),
        )
    }

    private fun summarise(fork: String, confidence: Confidence, major: Int?): String =
        when (confidence) {
            Confidence.VERIFIED -> "Tested against this firmware."

            Confidence.UNTESTED ->
                "$fork has not been tested with OmniWallet. Everything should work: " +
                    "custom firmware only adds to the protocol, and the parts this app " +
                    "uses are identical across the forks. Please report anything that " +
                    "does not."

            Confidence.INCOMPATIBLE ->
                "This firmware speaks protocol version $major, and OmniWallet was built " +
                    "against version $VENDORED_PROTOBUF_MAJOR. Some commands may fail. " +
                    "Nothing here writes to your device, so it is safe to try."
        }
}
