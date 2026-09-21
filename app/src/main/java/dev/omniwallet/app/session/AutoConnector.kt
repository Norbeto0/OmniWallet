package dev.omniwallet.app.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.transport.ble.BlePermissions
import dev.omniwallet.transport.ble.BleScanner
import dev.omniwallet.transport.ble.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconnects to the device you used last, without being asked.
 *
 * The app does nothing useful without hardware, so making someone walk through
 * scan-and-tap on every launch is friction for its own sake. On startup and on
 * returning to the foreground this looks for the remembered device and connects
 * if it is in range.
 *
 * Two restraints matter more than the feature itself:
 *
 *  - **A deliberate disconnect is respected.** Tapping Disconnect and then
 *    being silently reconnected would be the app overriding the user, which is
 *    the same mistake as a "stop" button that does not stop. Disconnecting by
 *    hand clears the remembered device.
 *  - **It gives up quickly.** The scan is time-boxed, because a Flipper that is
 *    switched off should cost a few seconds of radio, not a background search
 *    that quietly drains the battery.
 */
@Singleton
class AutoConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: BleScanner,
    private val connections: DeviceConnectionManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    companion object {
        /**
         * Long enough for a Flipper that is awake and nearby to advertise,
         * short enough that a missing device is not a noticeable wait.
         */
        const val SCAN_TIMEOUT_MILLIS = 8_000L
    }

    /** What the UI can say about an attempt in progress. */
    enum class Status { IDLE, SEARCHING, CONNECTING, FAILED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var attempt: Job? = null

    /**
     * Try to reconnect, if there is anything to reconnect to.
     *
     * Safe to call repeatedly: an attempt already running, or a device already
     * connected, short-circuits.
     */
    fun tryReconnect() {
        if (attempt?.isActive == true) return
        if (connections.connectionState.value is ConnectionState.Ready) return

        attempt = scope.launch {
            val current = settings.settings.first()
            val address = current.lastDeviceAddress
            if (!current.autoConnect || address == null) return@launch
            if (!BlePermissions.readiness(context).canScan) return@launch

            _status.value = Status.SEARCHING
            val found = findRememberedDevice(address)

            if (found == null) {
                _status.value = Status.IDLE
                return@launch
            }

            _status.value = Status.CONNECTING
            _status.value = runCatching { connections.connect(found) }
                .fold(onSuccess = { Status.IDLE }, onFailure = { Status.FAILED })
        }
    }

    /**
     * Scan until the remembered address turns up, or the timeout expires.
     *
     * Filtered, so this only wakes for devices the app understands.
     */
    private suspend fun findRememberedDevice(address: String): DiscoveredDevice? =
        withTimeoutOrNull(SCAN_TIMEOUT_MILLIS) {
            runCatching {
                scanner.scan(filtered = true)
                    .firstOrNull { it.address.equals(address, ignoreCase = true) }
            }.getOrNull()
        }

    /** Record a successful connection as the one to come back to. */
    fun remember(device: DiscoveredDevice) {
        scope.launch { settings.rememberDevice(device.address, device.displayName) }
    }

    /**
     * Called when the user disconnects on purpose.
     *
     * Forgetting the device is what stops auto-connect immediately undoing the
     * action they just took.
     */
    fun onUserDisconnected() {
        attempt?.cancel()
        _status.value = Status.IDLE
        scope.launch { settings.forgetDevice() }
    }
}
