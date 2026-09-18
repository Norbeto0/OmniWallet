package dev.omniwallet.core.domain

/** Stable identity for a credential in the user's own library. */
@JvmInline
value class CredentialId(val value: String)

/**
 * Where a credential's bytes actually live.
 *
 * Emulation-start semantics differ per device -- the Flipper launches an app
 * with a file argument, the Chameleon writes a slot and enables it -- so the
 * location carries only enough for the owning backend to act, and the UI above
 * never inspects it.
 */
sealed interface CredentialLocation {

    /** A file on the Flipper's own filesystem, e.g. `/ext/nfc/office.nfc`. */
    data class FlipperFile(val path: String) : CredentialLocation {
        /** Directory part, used to infer the protocol. */
        val directory: String get() = path.substringBeforeLast('/', "")

        /** Filename without the extension -- the natural display name. */
        val baseName: String get() = path.substringAfterLast('/').substringBeforeLast('.')
    }

    /**
     * A dump held by the app and destined for a Chameleon slot.
     * Placeholder until M5; present now so the abstraction is shaped by two
     * devices rather than one.
     */
    data class ChameleonSlot(val slot: Int) : CredentialLocation
}

/**
 * An entry in the user's library.
 *
 * Treat this like a password manager record: it is a map of the user's physical
 * access credentials.
 */
data class Credential(
    val id: CredentialId,
    val displayName: String,
    val protocol: Protocol,
    val location: CredentialLocation,
    val favourite: Boolean = false,
    val lastUsedAtMillis: Long? = null,
)

/**
 * A credential as a device reports it during discovery -- found on the hardware
 * but not yet registered in the user's library.
 */
data class RemoteCredential(
    val displayName: String,
    val protocol: Protocol,
    val location: CredentialLocation,
    val sizeBytes: Long? = null,
)

/** A live emulation session, returned by [EmulatorDevice.startEmulation]. */
data class EmulationHandle(
    val credentialId: CredentialId,
    val deviceId: String,
    val startedAtMillis: Long,
)
