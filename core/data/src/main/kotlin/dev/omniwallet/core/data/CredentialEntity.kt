package dev.omniwallet.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.StoredCredential

/**
 * One library entry, as stored.
 *
 * Columns are split by ownership on purpose: everything from `discoveredName`
 * down to `lastSeenAtMillis` is the device's and gets overwritten on each scan,
 * while `customName`, `favourite`, `hidden` and `lastUsedAtMillis` are the
 * user's and are never touched by discovery.
 *
 * Location is flattened into a type/value pair rather than serialised, so it
 * stays queryable and legible in a database dump.
 *
 * Note on scope: this table holds names, paths and preferences -- not card
 * contents. Encryption at rest arrives in M4, and the schema is arranged so
 * swapping in SQLCipher's open helper is the only change required.
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "device_kind") val deviceKind: String,
    val protocol: String,
    @ColumnInfo(name = "location_type") val locationType: String,
    @ColumnInfo(name = "location_value") val locationValue: String,

    // Device-owned.
    @ColumnInfo(name = "discovered_name") val discoveredName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAtMillis: Long,
    val present: Boolean,

    // User-owned.
    @ColumnInfo(name = "custom_name") val customName: String?,
    val favourite: Boolean,
    val hidden: Boolean,
    @ColumnInfo(name = "last_used_at") val lastUsedAtMillis: Long?,
) {
    companion object {
        const val LOCATION_FLIPPER_FILE = "flipper_file"
        const val LOCATION_CHAMELEON_SLOT = "chameleon_slot"
    }
}

fun CredentialEntity.toDomain(): StoredCredential = StoredCredential(
    id = CredentialId(id),
    deviceKind = DeviceKind.valueOf(deviceKind),
    protocol = Protocol.valueOf(protocol),
    location = when (locationType) {
        CredentialEntity.LOCATION_CHAMELEON_SLOT ->
            CredentialLocation.ChameleonSlot(locationValue.toInt())
        else -> CredentialLocation.FlipperFile(locationValue)
    },
    discoveredName = discoveredName,
    sizeBytes = sizeBytes,
    lastSeenAtMillis = lastSeenAtMillis,
    present = present,
    customName = customName,
    favourite = favourite,
    hidden = hidden,
    lastUsedAtMillis = lastUsedAtMillis,
)

fun StoredCredential.toEntity(): CredentialEntity = CredentialEntity(
    id = id.value,
    deviceKind = deviceKind.name,
    protocol = protocol.name,
    locationType = when (location) {
        is CredentialLocation.ChameleonSlot -> CredentialEntity.LOCATION_CHAMELEON_SLOT
        is CredentialLocation.FlipperFile -> CredentialEntity.LOCATION_FLIPPER_FILE
    },
    locationValue = when (val l = location) {
        is CredentialLocation.ChameleonSlot -> l.slot.toString()
        is CredentialLocation.FlipperFile -> l.path
    },
    discoveredName = discoveredName,
    sizeBytes = sizeBytes,
    lastSeenAtMillis = lastSeenAtMillis,
    present = present,
    customName = customName,
    favourite = favourite,
    hidden = hidden,
    lastUsedAtMillis = lastUsedAtMillis,
)
