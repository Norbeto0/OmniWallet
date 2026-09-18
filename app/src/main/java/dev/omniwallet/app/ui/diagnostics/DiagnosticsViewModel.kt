package dev.omniwallet.app.ui.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.Credential
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.EmulationHandle
import dev.omniwallet.core.domain.RemoteCredential
import dev.omniwallet.device.flipper.FlipperDevice
import dev.omniwallet.transport.ble.BlePermissions
import dev.omniwallet.transport.ble.BleScanner
import dev.omniwallet.transport.ble.DiscoveredDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DiagnosticsUiState(
    val readiness: BlePermissions.Readiness =
        BlePermissions.Readiness(emptyList(), bluetoothEnabled = false),
    val scanning: Boolean = false,
    val filteredScan: Boolean = true,
    val devices: List<DiscoveredDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectedTo: String? = null,
    val mtu: Int = 0,
    val credit: Int = 0,
    val rpcActive: Boolean = false,
    val pingMillis: Long? = null,
    val deviceInfo: Map<String, String> = emptyMap(),
    val credentials: List<RemoteCredential> = emptyList(),
    val emulating: EmulationHandle? = null,
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the diagnostics screen.
 *
 * This screen is the project's substitute for a debugger on real hardware:
 * development happens where there is no Bluetooth radio, no emulator and no
 * Flipper, so every state transition, GATT status and bond change has to be
 * visible and exportable. A failure the user can send back as a log is worth
 * far more than one they have to describe.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: BleScanner,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var device: FlipperDevice? = null

    init {
        refreshReadiness()
        log("diagnostics ready")
    }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        // Several coroutines log concurrently, so this must be atomic rather
        // than a read-modify-write of _state.value.
        _state.update { it.copy(log = (it.log + "$stamp  $message").takeLast(1000)) }
    }

    fun refreshReadiness() {
        _state.update { it.copy(readiness = BlePermissions.readiness(context)) }
    }

    fun setFilteredScan(filtered: Boolean) {
        _state.update { it.copy(filteredScan = filtered) }
        if (_state.value.scanning) {
            stopScan()
            startScan()
        }
    }

    fun startScan() {
        refreshReadiness()
        val readiness = _state.value.readiness
        if (!readiness.canScan) {
            log(
                "cannot scan: " + buildString {
                    if (!readiness.bluetoothEnabled) append("Bluetooth is off. ")
                    if (readiness.missingPermissions.isNotEmpty()) {
                        append("missing ${readiness.missingPermissions.joinToString()}")
                    }
                },
            )
            return
        }

        scanJob?.cancel()
        _state.update { it.copy(scanning = true, devices = emptyList()) }
        log("scan started (${if (_state.value.filteredScan) "filtered by service UUID" else "unfiltered"})")

        scanJob = viewModelScope.launch {
            runCatching {
                scanner.scan(filtered = _state.value.filteredScan).collect { found ->
                    // Compute the new list inside update() so a concurrent
                    // change cannot be clobbered. update's block may be retried,
                    // so it must stay free of side effects -- the log line is
                    // emitted afterwards, driven by whether the size changed.
                    var isNew = false
                    _state.update { current ->
                        val index = current.devices.indexOfFirst { it.address == found.address }
                        isNew = index < 0
                        current.copy(
                            devices = if (index >= 0) {
                                current.devices.toMutableList().apply { this[index] = found }
                            } else {
                                current.devices + found
                            },
                        )
                    }
                    if (isNew) {
                        log(
                            "found ${found.displayName} (${found.address}) " +
                                "rssi=${found.rssi} services=${found.advertisedServices}",
                        )
                    }
                }
            }.onFailure {
                log("scan failed: ${it.message}")
                _state.update { state -> state.copy(scanning = false, error = it.message) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
        log("scan stopped")
    }

    @SuppressLint("MissingPermission")
    fun connect(discovered: DiscoveredDevice) {
        stopScan()
        val adapter = BlePermissions.adapter(context) ?: run {
            log("no Bluetooth adapter")
            return
        }

        // Tear down any previous device first; otherwise its collectors and BLE
        // manager outlive it for the whole ViewModel lifetime.
        device?.let { previous ->
            viewModelScope.launch { runCatching { previous.disconnect() } }
        }

        val remote = adapter.getRemoteDevice(discovered.address)

        val flipper = FlipperDevice(context, remote, viewModelScope, discovered.displayName)
        device = flipper
        _state.update { it.copy(connectedTo = discovered.displayName) }

        viewModelScope.launch { flipper.connectionState.collect { onConnectionState(it) } }
        // Mirror the device's own event log, which carries the GATT and bond
        // detail that makes a failed connection diagnosable after the fact.
        viewModelScope.launch {
            var seen = 0
            flipper.events.collect { events ->
                events.drop(seen).forEach { log(it) }
                seen = events.size
            }
        }
        viewModelScope.launch {
            flipper.rpcActive.collect { active -> _state.update { it.copy(rpcActive = active) } }
        }
        viewModelScope.launch {
            flipper.credit.collect { credit -> _state.update { it.copy(credit = credit) } }
        }

        viewModelScope.launch {
            busy {
                log("connecting to ${discovered.displayName} (${discovered.address})")
                runCatching { flipper.connect() }
                    .onSuccess { log("connected; mtu=${flipper.negotiatedMtu}") }
                    .onFailure { log("connect failed: ${it.message}") }
                _state.update { it.copy(mtu = flipper.negotiatedMtu) }
            }
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        log("state -> $state")
        _state.update { it.copy(connectionState = state) }
        if (state is ConnectionState.Failed &&
            state.reason == dev.omniwallet.core.domain.FailureReason.PAIRING_REQUIRED
        ) {
            log(
                "PAIRING REQUIRED: open Android Settings > Bluetooth, forget this Flipper, " +
                    "then pair again. A firmware change regenerates the device keys, which " +
                    "makes the stored pairing invalid.",
            )
        }
    }

    fun disconnect() {
        val flipper = device ?: return
        viewModelScope.launch {
            busy {
                runCatching { flipper.disconnect() }
                    .onFailure { log("disconnect error: ${it.message}") }
            }
            device = null
            _state.update { it.copy(connectedTo = null, deviceInfo = emptyMap(), credentials = emptyList()) }
        }
    }

    fun ping() = withDevice { flipper ->
        val millis = flipper.ping()
        _state.update { it.copy(pingMillis = millis) }
        log("ping round trip ${millis}ms")
    }

    fun loadDeviceInfo() = withDevice { flipper ->
        val info = flipper.deviceInfo()
        _state.update { it.copy(deviceInfo = info) }
        log("device_info: ${info.size} entries; firmware=${info["firmware_origin"] ?: "?"}")
    }

    fun listCredentials() = withDevice { flipper ->
        val found = flipper.listCredentials()
        _state.update { it.copy(credentials = found) }
        log("storage_list: ${found.size} files across the five asset directories")
    }

    fun emulate(remote: RemoteCredential) = withDevice { flipper ->
        val credential = Credential(
            id = CredentialId(remote.location.toString()),
            displayName = remote.displayName,
            protocol = remote.protocol,
            location = remote.location,
        )
        val handle = flipper.startEmulation(credential)
        _state.update { it.copy(emulating = handle) }
        log("emulation started: ${remote.displayName}")
    }

    /** Escape hatch: start an arbitrary app, to check app-name resolution. */
    fun startAppByName(name: String, args: String) = withDevice { flipper ->
        log("app_start name=\"$name\" args=\"$args\"")
        flipper.startAppByName(name, args)
        log("app started")
    }

    fun stopEmulation() = withDevice { flipper ->
        _state.value.emulating?.let { flipper.stopEmulation(it) }
        _state.update { it.copy(emulating = null) }
        log("emulation stopped")
    }

    /** Write the log to a shareable file and return it. */
    fun exportLog(): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "omniwallet-diagnostics.txt")
        val header = buildString {
            appendLine("OmniWallet diagnostics")
            appendLine("captured: ${Date()}")
            appendLine("android: ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("device: ${_state.value.connectedTo ?: "none"}")
            appendLine("state: ${_state.value.connectionState}")
            appendLine("mtu: ${_state.value.mtu}  credit: ${_state.value.credit}  rpcActive: ${_state.value.rpcActive}")
            _state.value.deviceInfo.forEach { (k, v) -> appendLine("  $k = $v") }
            appendLine("---")
        }
        file.writeText(header + _state.value.log.joinToString("\n"))
        return file
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun withDevice(block: suspend (FlipperDevice) -> Unit) {
        val flipper = device ?: run { log("not connected"); return }
        viewModelScope.launch {
            busy { runCatching { block(flipper) }.onFailure { log("error: ${it.message}") } }
        }
    }

    private inline fun busy(block: () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
