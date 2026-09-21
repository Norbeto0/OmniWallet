package dev.omniwallet.app.ui.device

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.app.session.AutoConnector
import dev.omniwallet.app.session.DeviceConnectionManager
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.app.ui.settings.KnownDevice
import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.protocol.flipper.FirmwareCompatibility
import dev.omniwallet.transport.ble.BlePermissions
import dev.omniwallet.transport.ble.BleScanner
import dev.omniwallet.transport.ble.DiscoveredDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceUiState(
    val readiness: BlePermissions.Readiness =
        BlePermissions.Readiness(emptyList(), bluetoothEnabled = false),
    val scanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val firmware: FirmwareCompatibility.Report? = null,
    val autoConnect: AutoConnector.Status = AutoConnector.Status.IDLE,
    /** Devices connected to before, most recent first. */
    val knownDevices: List<KnownDevice> = emptyList(),
    val connectedAddress: String? = null,
    /**
     * True once a scan has run in this session.
     *
     * Distinguishes "found nothing" from "has not looked", which are
     * indistinguishable on screen and mean completely different things.
     */
    val scanned: Boolean = false,
) {
    val connected: Boolean get() = connectionState is ConnectionState.Ready
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: BleScanner,
    private val connections: DeviceConnectionManager,
    private val autoConnector: AutoConnector,
    private val settings: SettingsStore,
) : ViewModel() {

    private val local = MutableStateFlow(DeviceUiState())
    private var scanJob: Job? = null

    val state: StateFlow<DeviceUiState> = combine(
        local,
        connections.connectionState,
        connections.connectedName,
        autoConnector.status,
        settings.settings,
    ) { own, connection, name, auto, prefs ->
        own.copy(
            connectionState = connection,
            connectedName = name,
            autoConnect = auto,
            knownDevices = prefs.knownDevices,
            connectedAddress = if (connection is ConnectionState.Ready) {
                prefs.lastDeviceAddress
            } else {
                null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    init {
        refreshReadiness()
    }

    fun refreshReadiness() {
        local.update { it.copy(readiness = BlePermissions.readiness(context)) }
    }

    fun startScan() {
        refreshReadiness()
        if (!local.value.readiness.canScan) return

        scanJob?.cancel()
        local.update { it.copy(scanning = true, scanned = true, devices = emptyList()) }
        scanJob = viewModelScope.launch {
            runCatching {
                scanner.scan(filtered = true).collect { found ->
                    local.update { current ->
                        val index = current.devices.indexOfFirst { it.address == found.address }
                        current.copy(
                            devices = if (index >= 0) {
                                current.devices.toMutableList().apply { this[index] = found }
                            } else {
                                current.devices + found
                            },
                        )
                    }
                }
            }.onFailure {
                // Stopping the scan cancels this coroutine; that is not a failure.
                if (it is CancellationException) throw it
                local.update { s -> s.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        local.update { it.copy(scanning = false) }
    }

    fun connect(device: DiscoveredDevice) {
        stopScan()
        viewModelScope.launch {
            runCatching { connections.connect(device) }
                .onSuccess { autoConnector.remember(device) }
            runCatching {
                connections.readyDevice()?.let { ready ->
                    val info = (ready as? dev.omniwallet.device.flipper.FlipperDevice)?.deviceInfo()
                    // Parsed by the protocol module rather than picked apart
                    // here: which device_info keys exist is a firmware fact,
                    // and assuming one that does not (there is no
                    // `firmware_origin`) is how this was wrong before.
                    local.update {
                        it.copy(firmware = FirmwareCompatibility.inspect(info.orEmpty()))
                    }
                }
            }
        }
    }

    /**
     * Switch to a device the user has used before, without a rescan first.
     *
     * The whole point of the known list: with two Flippers, changing between
     * them should be one tap rather than a scan and a hunt through whatever
     * else is advertising nearby.
     */
    fun connectTo(device: KnownDevice) {
        stopScan()
        autoConnector.connectTo(device.address, device.name)
    }

    fun forget(device: KnownDevice) {
        viewModelScope.launch { settings.removeKnownDevice(device.address) }
    }

    fun disconnect() {
        // Deliberate, so stop auto-connecting -- otherwise the app would undo
        // the action the moment it was taken.
        autoConnector.onUserDisconnected()
        viewModelScope.launch {
            connections.disconnect()
            local.update { it.copy(firmware = null) }
        }
    }
}
