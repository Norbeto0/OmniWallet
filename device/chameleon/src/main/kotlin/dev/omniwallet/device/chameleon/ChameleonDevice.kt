package dev.omniwallet.device.chameleon

import android.bluetooth.BluetoothDevice
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.Credential
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.DeviceException
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.EmulationHandle
import dev.omniwallet.core.domain.EmulatorDevice
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.RemoteCredential
import dev.omniwallet.protocol.chameleon.CHAMELEON_SLOT_COUNT
import dev.omniwallet.transport.ble.ScanTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * A Chameleon Ultra, as an [EmulatorDevice].
 *
 * **Stub until M5.** It exists now, unfinished, on purpose: the whole point of
 * [EmulatorDevice] is that it fits more than one device, and an interface
 * designed against a single backend invariably grows that backend's shape. The
 * Chameleon is a genuinely different shape, which is what makes it useful here:
 *
 *  | | Flipper Zero | Chameleon Ultra |
 *  |---|---|---|
 *  | transport | custom serial service, credit-based flow control | standard Nordic UART Service |
 *  | framing | varint-delimited protobuf | 0x11-prefixed frames with three LRCs |
 *  | emulation | start an app with a file path argument | select a slot, write the tag, enable it |
 *  | storage | files on the device's own filesystem | eight fixed slots |
 *
 * Nothing in [EmulatorDevice] presumes files, apps, or slots, and this class is
 * how that claim stays honest.
 *
 * What is already confirmed from the vendor firmware and client: the transport
 * is standard NUS ([NORDIC_UART_SERVICE]), and the frame codec and command
 * codes in `:protocol:chameleon` are implemented and unit-tested. What remains
 * for M5 is the slot workflow -- select, write a dump, set the UID, enable.
 */
class ChameleonDevice(
    private val bluetoothDevice: BluetoothDevice,
    /**
     * From the advertisement, not `BluetoothDevice.getName`, which needs
     * BLUETOOTH_CONNECT and throws without it.
     */
    override val displayName: String = bluetoothDevice.address,
) : EmulatorDevice {

    companion object {
        /** Confirmed from firmware `ble_main.c`, which uses `BLE_NUS_DEF`. */
        val NORDIC_UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_CHARACTERISTIC: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_CHARACTERISTIC: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        const val SLOT_COUNT = CHAMELEON_SLOT_COUNT

        /**
         * Discovery pattern.
         *
         * Unlike the Flipper, the Chameleon advertises the same 128-bit
         * service it serves, so no mask is needed -- but that service is the
         * *standard* Nordic UART Service, which is not distinctive at all. A
         * real scan matched a device called "Camera" on it. The name hints
         * narrow that: a NUS device whose name does not look like a Chameleon
         * is reported as unrecognised instead.
         *
         * Still unverified against hardware -- no Chameleon has been connected
         * yet, so the hints are informed by the vendor naming rather than
         * observed.
         */
        val SCAN_TARGET: ScanTarget = ScanTarget(
            kind = DeviceKind.CHAMELEON_ULTRA,
            serviceUuid = NORDIC_UART_SERVICE,
            nameHints = listOf("Chameleon", "CU-", "ChameleonUltra"),
        )
    }

    override val id: String = bluetoothDevice.address

    override val kind: DeviceKind = DeviceKind.CHAMELEON_ULTRA

    /**
     * The Chameleon covers exactly the cases a phone cannot: Mifare Classic and
     * arbitrary UIDs over NFC, plus 125 kHz LF. It has no sub-GHz, iButton or
     * IR radio, so those stay with the Flipper.
     */
    override val capabilities: Set<Protocol> = setOf(Protocol.NFC, Protocol.RFID_125K)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect() {
        throw DeviceException.Unsupported(
            "Chameleon Ultra support arrives in M5; the frame codec is implemented and tested, " +
                "the slot workflow is not",
        )
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun listCredentials(): List<RemoteCredential> = emptyList()

    override fun canEmulate(credential: Credential): Boolean =
        credential.location is CredentialLocation.ChameleonSlot &&
            credential.protocol in capabilities

    override suspend fun startEmulation(credential: Credential): EmulationHandle =
        throw DeviceException.Unsupported("Chameleon Ultra emulation arrives in M5")

    override suspend fun stopEmulation(handle: EmulationHandle) =
        throw DeviceException.Unsupported("Chameleon Ultra emulation arrives in M5")
}
