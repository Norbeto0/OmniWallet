package dev.omniwallet.app.ui.device

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.app.session.DeviceConnectionManager
import dev.omniwallet.core.domain.ConnectionState
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
    val firmware: String? = null,
) {
    val connected: Boolean get() = connectionState is ConnectionState.Ready
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: BleScanner,
    private val connections: DeviceConnectionManager,
) : ViewModel() {

    private val local = MutableStateFlow(DeviceUiState())
    private var scanJob: Job? = null

    val state: StateFlow<DeviceUiState> = combine(
        local,
        connections.connectionState,
        connections.connectedName,
    ) { own, connection, name ->
        own.copy(connectionState = connection, connectedName = name)
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
        local.update { it.copy(scanning = true, devices = emptyList()) }
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
            runCatching {
                connections.readyDevice()?.let { ready ->
                    val info = (ready as? dev.omniwallet.device.flipper.FlipperDevice)?.deviceInfo()
                    val fork = info?.get("firmware_origin_fork") ?: "Official"
                    val version = info?.get("firmware_version").orEmpty()
                    local.update { it.copy(firmware = "$fork $version".trim()) }
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connections.disconnect()
            local.update { it.copy(firmware = null) }
        }
    }
}
