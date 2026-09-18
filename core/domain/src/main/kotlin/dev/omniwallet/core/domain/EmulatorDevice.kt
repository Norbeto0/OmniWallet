package dev.omniwallet.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * The spine of the app: one external device that can emit credentials.
 *
 * Everything above this interface -- wallet UI, geofencing, widget, tile -- is
 * backend-agnostic. Everything device-specific lives behind it. In particular
 * emulation-start semantics differ sharply between backends (the Flipper starts
 * an app with a file argument; the Chameleon selects a slot, writes it and
 * enables emulation) and must never leak upward.
 */
interface EmulatorDevice {

    /** Stable id for this device, typically its BLE address. */
    val id: String

    val kind: DeviceKind

    /** Human-readable name as advertised, e.g. "Flipper Unyana". */
    val displayName: String

    val connectionState: StateFlow<ConnectionState>

    /** Protocols this device can emit. Used to route a credential to a backend. */
    val capabilities: Set<Protocol>

    /**
     * Open the link and drive it to [ConnectionState.Ready].
     * Returns once the device is ready; failures surface as [DeviceException].
     */
    suspend fun connect()

    /**
     * Close the link. This is a *user-initiated* disconnect and must suppress
     * auto-reconnect -- otherwise the app fights the user.
     */
    suspend fun disconnect()

    /** Enumerate what this device holds or can emit. */
    suspend fun listCredentials(): List<RemoteCredential>

    /** Whether this backend can emit [credential] at all. */
    fun canEmulate(credential: Credential): Boolean

    suspend fun startEmulation(credential: Credential): EmulationHandle

    suspend fun stopEmulation(handle: EmulationHandle)
}

/** Failures a backend raises. */
sealed class DeviceException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class NotConnected(deviceId: String) :
        DeviceException("Device $deviceId is not connected")

    /** The backend cannot emit this credential (wrong protocol, or not implemented yet). */
    class Unsupported(detail: String) : DeviceException(detail)

    /** The device accepted the command but answered with an error. */
    class RemoteError(val code: String, detail: String) :
        DeviceException("Device returned $code: $detail")

    class Timeout(detail: String) : DeviceException("Timed out: $detail")

    class TransportError(detail: String, cause: Throwable? = null) :
        DeviceException(detail, cause)
}
