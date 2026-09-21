package dev.omniwallet.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.Place
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.StoredCredential

/**
 * One library entry, as stored.
 *
 * Columns are split by ownership on purpose: everything from `discoveredName`
 * down to `lastSeenAtMillis` is the device's and gets overwritten on each scan,
 * while `customName`, `favourite`, `hidden`, `lastUsedAtMillis` and the three
 * `place_*` columns are the user's and are never touched by discovery.
 *
 * Location is flattened into a type/value pair rather than serialised, so it
 * stays queryable and legible in a database dump.
 *
 * Note on scope: this table holds names, paths, preferences and -- since v2 --
 * an optional tagged coordinate. Not card contents; those stay on the device.
 * The coordinate is the most sensitive column here, which is part of why the
 * database is opened through SQLCipher.
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

    // Added in schema v2. All three are null together or set together; the
    // label is what decides, since a place with no name is not one the UI can
    // show and a coordinate alone would be untaggable and unremovable.
    @ColumnInfo(name = "place_label") val placeLabel: String? = null,
    @ColumnInfo(name = "place_lat") val placeLatitude: Double? = null,
    @ColumnInfo(name = "place_lon") val placeLongitude: Double? = null,
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
    // Guarded rather than assumed: a row written by a future version, or one
    // left half-populated by a crash mid-write, becomes "no place" instead of
    // a credential pinned to the Gulf of Guinea at 0,0.
    place = placeLabel?.takeIf { it.isNotBlank() }?.let { label ->
        val lat = placeLatitude
        val lon = placeLongitude
        if (lat == null || lon == null) null else Place(label, lat, lon)
    },
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
    placeLabel = place?.label,
    placeLatitude = place?.latitude,
    placeLongitude = place?.longitude,
)
