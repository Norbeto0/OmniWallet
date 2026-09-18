package dev.omniwallet.transport.ble

import dev.omniwallet.core.domain.DeviceKind

/** A device seen during a scan. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Service UUIDs present in the advertisement, lowercased. */
    val advertisedServices: List<String>,
    /** Null when nothing in the advertisement identifies it. */
    val kind: DeviceKind?,
) {
    val displayName: String get() = name ?: address
}
