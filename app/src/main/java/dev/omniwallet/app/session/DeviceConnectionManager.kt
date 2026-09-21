package dev.omniwallet.app.session

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.EmulatorDevice
import dev.omniwallet.device.flipper.FlipperDevice
import dev.omniwallet.transport.ble.BlePermissions
import dev.omniwallet.transport.ble.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the connected device for the whole app.
 *
 * Previously the diagnostics ViewModel constructed and held the
 * [FlipperDevice]. That was reasonable when diagnostics was the only screen,
 * and wrong the moment three screens needed to share one connection: a
 * ViewModel dies with its screen, and a BLE link that dies when you swipe to
 * another tab is not a link anyone can use.
 */
@Singleton
class DeviceConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _device = MutableStateFlow<FlipperDevice?>(null)
    val device: StateFlow<FlipperDevice?> = _device.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private var stateJob: Job? = null

    val isReady: Boolean get() = _connectionState.value is ConnectionState.Ready

    /** The connected device, or null if nothing is ready to take commands. */
    fun readyDevice(): EmulatorDevice? =
        _device.value?.takeIf { it.connectionState.value is ConnectionState.Ready }

    @SuppressLint("MissingPermission") // gated on BlePermissions by callers
    suspend fun connect(discovered: DiscoveredDevice) {
        disconnect()

        val adapter = BlePermissions.adapter(context) ?: return
        val remote = adapter.getRemoteDevice(discovered.address)
        val flipper = FlipperDevice(context, remote, scope, discovered.displayName)

        _device.value = flipper
        _connectedName.value = discovered.displayName
        stateJob = scope.launch {
            flipper.connectionState.collect { _connectionState.value = it }
        }

        flipper.connect()
    }

    suspend fun disconnect() {
        val current = _device.value ?: return
        runCatching { current.disconnect() }
        stateJob?.cancel()
        stateJob = null
        _device.value = null
        _connectedName.value = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
