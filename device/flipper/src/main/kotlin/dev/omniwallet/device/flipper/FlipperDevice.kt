package dev.omniwallet.device.flipper

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.Credential
import dev.omniwallet.core.domain.CredentialLocation
import dev.omniwallet.core.domain.DeviceException
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.EmulationHandle
import dev.omniwallet.core.domain.EmulatorDevice
import dev.omniwallet.core.domain.FailureReason
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.RemoteCredential
import dev.omniwallet.protocol.flipper.FlipperApp
import dev.omniwallet.protocol.flipper.FlipperBleProfile
import com.flipperdevices.protobuf.CommandStatus
import dev.omniwallet.protocol.flipper.FlipperRpcClient
import dev.omniwallet.protocol.flipper.FlipperRpcSession
import dev.omniwallet.transport.ble.BondMonitor
import dev.omniwallet.transport.ble.ReconnectPolicy
import dev.omniwallet.transport.ble.ScanTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

/**
 * A Flipper Zero, as an [EmulatorDevice].
 *
 * Owns the connection state machine. Three behaviours here are what separate a
 * BLE app that feels solid from one that feels broken:
 *
 *  - **User intent is tracked separately from link state.** A disconnect the
 *    user asked for must not trigger a reconnect; a link that dropped because
 *    the Flipper slept or went out of range must. Conflating the two means the
 *    app either fights the user or gives up on them.
 *  - **Pairing failure is terminal, not retryable.** The firmware terminates
 *    the link when pairing fails, and a bond gone stale (commonly after a
 *    firmware change) can only be fixed by forgetting the device in Android's
 *    settings. Retrying that forever looks like a hang.
 *  - **Bonding is allowed to take its time.** The Flipper displays a six-digit
 *    code the user must type, so tens of seconds in BOND_BONDING is normal and
 *    must not be treated as a stall.
 */
class FlipperDevice(
    context: Context,
    private val bluetoothDevice: BluetoothDevice,
    private val scope: CoroutineScope,
    /**
     * Taken from the advertisement rather than read back off the
     * BluetoothDevice: `BluetoothDevice.getName` needs BLUETOOTH_CONNECT and
     * throws without it, and the scan already told us the name for free.
     */
    override val displayName: String = bluetoothDevice.address,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val connectTimeoutMillis: Long = 20_000,
) : EmulatorDevice {

    companion object {
        /**
         * The 128-bit serial service, available only once connected.
         *
         * Deliberately *not* what discovery filters on -- see
         * [ADVERTISED_SERVICE].
         */
        val SERIAL_SERVICE: UUID = FlipperBleProfile.SERVICE

        /**
         * What a Flipper actually puts in its advertisement.
         *
         * `targets/f7/ble_glue/profiles/serial_profile.c` sets a 16-bit service
         * UUID of `0x3080 | furi_hal_version_get_hw_color()`, so the value
         * differs per device colour -- a white unit (`hw_color = 2`) advertises
         * `0x3082`. The 128-bit serial service never appears in the
         * advertisement at all, so filtering on it finds nothing.
         *
         * `FuriHalVersionColor` runs 0x00..0x03, so masking the low four bits
         * matches every Flipper with room to spare. Momentum uses the identical
         * formula.
         */
        val ADVERTISED_SERVICE: UUID = ScanTarget.shortUuid(0x3080)
        val ADVERTISED_SERVICE_MASK: UUID = ScanTarget.shortUuidMask(wildcardBits = 4)

        /** Discovery pattern for a Flipper Zero. */
        val SCAN_TARGET: ScanTarget = ScanTarget(
            kind = DeviceKind.FLIPPER_ZERO,
            serviceUuid = ADVERTISED_SERVICE,
            mask = ADVERTISED_SERVICE_MASK,
        )
    }

    override val id: String = bluetoothDevice.address

    override val kind: DeviceKind = DeviceKind.FLIPPER_ZERO

    override val capabilities: Set<Protocol> = setOf(
        Protocol.NFC,
        Protocol.RFID_125K,
        Protocol.SUBGHZ,
        Protocol.IBUTTON,
        Protocol.INFRARED,
    )

    private val manager = FlipperBleManager(context)
    private val bondMonitor = BondMonitor(context)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Verbose event log for the diagnostics screen. */
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private val _credit = MutableStateFlow(0)

    /**
     * Live flow-control credit. Mirrored off the session rather than exposed
     * from it directly so the UI has a stable flow to observe across
     * reconnects, when the session object itself is replaced.
     */
    val credit: StateFlow<Int> = _credit.asStateFlow()

    val negotiatedMtu: Int get() = manager.negotiatedMtu

    val rpcActive: StateFlow<Boolean> get() = manager.rpcActive

    private var session: FlipperRpcSession? = null
    private var client: FlipperRpcClient? = null

    /**
     * Whether the user currently wants this device connected. The single most
     * important bit of state here: it is what distinguishes "reconnect" from
     * "leave it alone".
     */
    @Volatile
    private var userWantsConnection = false

    private var reconnectJob: Job? = null
    private var creditJob: Job? = null
    private var bondJob: Job? = null
    private var sawPairingFailure = false

    private fun note(message: String) {
        _events.value = (_events.value + "${System.currentTimeMillis()} $message").takeLast(500)
    }

    private val observer = object : ConnectionObserver {
        override fun onDeviceConnecting(device: BluetoothDevice) {
            note("connecting")
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            note("connected (GATT); discovering services")
            _connectionState.value = ConnectionState.Discovering
        }

        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
            note("failed to connect, reason=$reason")
            onLinkLost(reason)
        }

        override fun onDeviceReady(device: BluetoothDevice) {
            note("ready (mtu=${manager.negotiatedMtu})")
            sawPairingFailure = false
            _connectionState.value = ConnectionState.Ready
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) {
            note("disconnecting")
        }

        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            note("disconnected, reason=$reason")
            teardownSession()
            onLinkLost(reason)
        }
    }

    init {
        manager.setConnectionObserver(observer)
        scope.launch { manager.gattLog.collect { note("gatt: $it") } }
    }

    private fun onLinkLost(reason: Int) {
        if (!userWantsConnection || reason == ConnectionObserver.REASON_SUCCESS) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (sawPairingFailure) {
            note("pairing failed; not retrying until the user re-pairs")
            _connectionState.value = ConnectionState.Failed(FailureReason.PAIRING_REQUIRED)
            userWantsConnection = false
            return
        }

        val failure = when (reason) {
            ConnectionObserver.REASON_NOT_SUPPORTED -> FailureReason.SERVICE_NOT_FOUND
            ConnectionObserver.REASON_TIMEOUT -> FailureReason.TIMEOUT
            else -> FailureReason.LINK_LOST
        }

        // A device that does not expose the serial service will not grow one on
        // retry, so that case is terminal too.
        if (failure == FailureReason.SERVICE_NOT_FOUND) {
            _connectionState.value = ConnectionState.Failed(failure)
            userWantsConnection = false
            return
        }

        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 1
            while (userWantsConnection && reconnectPolicy.shouldRetry(attempt)) {
                val wait = reconnectPolicy.delayForAttempt(attempt)
                _connectionState.value = ConnectionState.Reconnecting(attempt, wait)
                note("reconnect attempt $attempt in ${wait}ms")
                delay(wait)
                if (!userWantsConnection) break

                _connectionState.value = ConnectionState.Connecting(attempt)
                val ok = runCatching { openLink() }
                    .onFailure { note("reconnect attempt $attempt failed: ${it.message}") }
                    .isSuccess
                if (ok) return@launch
                attempt++
            }
            if (userWantsConnection) {
                _connectionState.value = ConnectionState.Failed(FailureReason.LINK_LOST)
            }
        }
    }

    override suspend fun connect() {
        userWantsConnection = true
        sawPairingFailure = false
        startBondWatch()
        _connectionState.value = ConnectionState.Connecting(attempt = 1)
        try {
            openLink()
        } catch (t: Throwable) {
            note("connect failed: ${t.message}")
            if (userWantsConnection && !sawPairingFailure) scheduleReconnect()
            throw DeviceException.TransportError("could not connect to $displayName", t)
        }
    }

    private suspend fun openLink() {
        manager.connectTo(bluetoothDevice, connectTimeoutMillis)
        val rpc = FlipperRpcSession(FlipperTransport(manager), scope)
        rpc.start()
        session = rpc
        client = FlipperRpcClient(rpc)
        creditJob?.cancel()
        creditJob = scope.launch { rpc.flowControl.credit.collect { _credit.value = it } }
        _connectionState.value = ConnectionState.Ready
    }

    private fun startBondWatch() {
        if (bondJob?.isActive == true) return
        bondJob = scope.launch {
            bondMonitor.events()
                .collect { event ->
                    if (!event.address.equals(id, ignoreCase = true)) return@collect
                    when {
                        event.isBonding ->
                            // Expected to sit here while the user reads the
                            // six-digit code off the Flipper and types it.
                            note("bonding: waiting for the user to confirm the pairing code")

                        event.isPairingFailure -> {
                            sawPairingFailure = true
                            note("bonding failed: the stored pairing is no longer valid")
                            _connectionState.value =
                                ConnectionState.Failed(FailureReason.PAIRING_REQUIRED)
                        }

                        event.isBonded -> note("bonded")
                    }
                }
        }
    }

    private fun teardownSession() {
        creditJob?.cancel()
        creditJob = null
        session?.close()
        session = null
        client = null
    }

    override suspend fun disconnect() {
        // Set first: the observer consults this to decide whether the drop was
        // intentional, and it fires before disconnect() returns.
        userWantsConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        bondJob?.cancel()
        bondJob = null
        teardownSession()
        note("user requested disconnect")
        manager.disconnectSafely()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun requireClient(): FlipperRpcClient =
        client ?: throw DeviceException.NotConnected(id)

    override suspend fun listCredentials(): List<RemoteCredential> {
        val rpc = requireClient()
        return FlipperApp.entries.flatMap { app ->
            rpc.listDirectory(app.directory)
                .filter { !it.isDirectory && it.name.endsWith(app.fileExtension, ignoreCase = true) }
                .map { entry ->
                    RemoteCredential(
                        displayName = entry.name.substringBeforeLast('.'),
                        protocol = app.protocol,
                        location = CredentialLocation.FlipperFile("${app.directory}/${entry.name}"),
                        sizeBytes = entry.sizeBytes,
                    )
                }
        }
    }

    override fun canEmulate(credential: Credential): Boolean =
        credential.location is CredentialLocation.FlipperFile &&
            credential.protocol in capabilities

    override suspend fun startEmulation(credential: Credential): EmulationHandle {
        val location = credential.location as? CredentialLocation.FlipperFile
            ?: throw DeviceException.Unsupported(
                "a Flipper can only emulate credentials stored on the device",
            )
        val rpc = requireClient()
        val app = FlipperApp.forPath(location.path)
            ?: FlipperApp.forProtocol(credential.protocol)

        // The Flipper runs one app at a time, so a second credential fails with
        // ERROR_APP_SYSTEM_LOCKED unless whatever is running is closed first.
        // Clearing up front is cheaper than provoking the error, and this is
        // the natural place for it: switching cards must look like one action
        // from above, not like "stop, then start".
        if (!rpc.exitRunningApp()) {
            note("warning: an app is still running; the start may be refused")
        }

        note("starting ${app.appName} with ${location.path}")
        try {
            rpc.startApp(app, location.path)
        } catch (e: FlipperRpcSession.RpcException) {
            if (e.status != CommandStatus.ERROR_APP_SYSTEM_LOCKED) throw e
            // Something grabbed the lock in between. Clear it once more and
            // retry; a second failure is real and propagates.
            note("device was busy; closing the running app and retrying once")
            rpc.exitRunningApp()
            rpc.startApp(app, location.path)
        }

        return EmulationHandle(
            credentialId = credential.id,
            deviceId = id,
            startedAtMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun stopEmulation(handle: EmulationHandle) {
        note("stopping emulation")
        if (!requireClient().exitRunningApp()) {
            // Reporting success here would be a lie the UI then repeats: the
            // Flipper would still be emulating while the app said it had
            // stopped. That is exactly what the first hardware run did.
            throw DeviceException.RemoteError(
                code = "APP_STILL_RUNNING",
                detail = "could not close the running app; it may still be emulating",
            )
        }
        note("emulation stopped; no app running")
    }

    /** Device info as reported over RPC, for diagnostics and firmware checks. */
    suspend fun deviceInfo(): Map<String, String> = requireClient().deviceInfo()

    suspend fun ping(): Long {
        val started = System.nanoTime()
        requireClient().ping(byteArrayOf(0x01, 0x02, 0x03))
        return (System.nanoTime() - started) / 1_000_000
    }

    /** Start an arbitrary app by name, for the diagnostics screen's override. */
    suspend fun startAppByName(name: String, args: String) =
        requireClient().startAppByName(name, args)
}
