package dev.omniwallet.core.domain

/**
 * A library entry: what the device reported, plus what the user made of it.
 *
 * The split is the whole point. [discoveredName] and [sizeBytes] belong to the
 * device and are refreshed on every scan; [customName], [favourite], [hidden],
 * [lastUsedAtMillis] and [place] belong to the user and must survive every scan.
 */
data class StoredCredential(
    val id: CredentialId,
    val deviceKind: DeviceKind,
    val protocol: Protocol,
    val location: CredentialLocation,

    /** The name as the device reports it, e.g. a filename without extension. */
    val discoveredName: String,
    val sizeBytes: Long? = null,

    /** When the device last confirmed this exists. */
    val lastSeenAtMillis: Long,

    /**
     * False once a scan completes without finding it. The entry is kept:
     * a Flipper that is merely switched off has not had its cards deleted, and
     * losing a user's renames to a flat battery would be indefensible.
     */
    val present: Boolean = true,

    val customName: String? = null,
    val favourite: Boolean = false,
    val hidden: Boolean = false,
    val lastUsedAtMillis: Long? = null,

    /**
     * Where the user says this gets used, if they have said. Tagged by hand,
     * never inferred -- see [Place].
     */
    val place: Place? = null,
) {
    /** What to show: the user's name if they set one, else the device's. */
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: discoveredName

    fun toCredential(): Credential = Credential(
        id = id,
        displayName = displayName,
        protocol = protocol,
        location = location,
        favourite = favourite,
        lastUsedAtMillis = lastUsedAtMillis,
    )
}

/**
 * Reconciles a device scan against the stored library.
 *
 * Deliberately a pure function in the domain module rather than SQL in the DAO.
 * This is the only real logic in the data layer, and Room DAO tests need an
 * instrumented device, which the development environment for this project does
 * not have. As a pure function it is exhaustively testable on the JVM -- the
 * same reasoning that put the protocol codecs in plain-JVM modules, and that
 * caught three bugs before they reached hardware.
 */
object CredentialMerge {

    /**
     * Build a stable identity for a credential.
     *
     * Keyed on device kind and location rather than on the name, so renaming a
     * card locally does not orphan its history. Renaming the file *on the
     * device* legitimately produces a new entry: it is a different file, and
     * pretending otherwise would mean guessing.
     */
    fun idFor(deviceKind: DeviceKind, location: CredentialLocation): CredentialId {
        val suffix = when (location) {
            is CredentialLocation.FlipperFile -> location.path
            is CredentialLocation.ChameleonSlot -> "slot/${location.slot}"
        }
        return CredentialId("${deviceKind.name}:$suffix")
    }

    /**
     * Merge a completed scan into the stored library.
     *
     * @param existing everything currently stored for this device.
     * @param discovered what the scan just found. An empty list is a valid
     *   result and must not be mistaken for "delete everything".
     * @param deviceKind which backend reported [discovered].
     * @param nowMillis timestamp for this scan.
     */
    fun merge(
        existing: List<StoredCredential>,
        discovered: List<RemoteCredential>,
        deviceKind: DeviceKind,
        nowMillis: Long,
    ): List<StoredCredential> {
        val byId = existing.associateByTo(LinkedHashMap()) { it.id }

        val seen = mutableSetOf<CredentialId>()
        discovered.forEach { remote ->
            val id = idFor(deviceKind, remote.location)
            seen += id
            val prior = byId[id]
            byId[id] = if (prior == null) {
                StoredCredential(
                    id = id,
                    deviceKind = deviceKind,
                    protocol = remote.protocol,
                    location = remote.location,
                    discoveredName = remote.displayName,
                    sizeBytes = remote.sizeBytes,
                    lastSeenAtMillis = nowMillis,
                    present = true,
                )
            } else {
                // Refresh only what the device owns. Every user-owned field is
                // carried across untouched -- this is the invariant the tests
                // exist to defend.
                prior.copy(
                    protocol = remote.protocol,
                    location = remote.location,
                    discoveredName = remote.displayName,
                    sizeBytes = remote.sizeBytes,
                    lastSeenAtMillis = nowMillis,
                    present = true,
                )
            }
        }

        // Anything not seen is marked absent, never removed.
        byId.keys.forEach { id ->
            if (id !in seen) {
                val entry = byId.getValue(id)
                if (entry.deviceKind == deviceKind && entry.present) {
                    byId[id] = entry.copy(present = false)
                }
            }
        }

        return byId.values.toList()
    }
}
