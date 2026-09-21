package dev.omniwallet.app.ui.device

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omniwallet.app.session.AutoConnector
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.protocol.flipper.FirmwareCompatibility
import dev.omniwallet.core.domain.FailureReason
import dev.omniwallet.transport.ble.BlePermissions

/**
 * Everything asked for in one prompt.
 *
 * Notifications are bundled with the BLE permissions rather than asked for
 * separately at the moment something starts emulating. Without them the
 * foreground service still runs, but its notification is suppressed -- and
 * with it the Stop button, which is the one control that should never be hard
 * to reach.
 */
private fun requiredPermissions(): List<String> = buildList {
    addAll(BlePermissions.required)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    onOpenDiagnostics: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: DeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshReadiness() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device") },
                actions = {
                    TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard(state) }

            if (!state.readiness.canScan) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Each of these states says what is wrong AND what
                            // to do. "Permission needed" on its own leaves the
                            // user to work out which permission and where.
                            if (!state.readiness.bluetoothEnabled) {
                                Text("Bluetooth is off", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Turn Bluetooth on in Android's quick settings, then scan " +
                                        "again. OmniWallet talks to your Flipper over Bluetooth " +
                                        "Low Energy and cannot do anything without it.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    "Nearby devices permission needed",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Android returns zero results from a Bluetooth scan without " +
                                        "it, and reports no error while doing so. If the button " +
                                        "does nothing, the permission was denied permanently — " +
                                        "grant it in Android Settings → Apps → OmniWallet.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = {
                                    permissionLauncher.launch(requiredPermissions().toTypedArray())
                                }) { Text("Grant nearby devices") }
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startScan() },
                        enabled = !state.scanning && state.readiness.canScan,
                    ) { Text("Scan") }
                    OutlinedButton(onClick = { viewModel.stopScan() }, enabled = state.scanning) {
                        Text("Stop")
                    }
                    if (state.connected) {
                        OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                    }
                }
            }

            if (state.scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            // The switcher. Only worth showing when there is something to
            // switch to that is not already connected.
            val switchable = state.knownDevices.filterNot {
                state.connected && it.address.equals(state.connectedAddress, ignoreCase = true)
            }
            if (switchable.isNotEmpty()) {
                item(key = "known-header") {
                    Text(
                        "Your devices",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(switchable, key = { "known-${it.address}" }) { known ->
                    Card(
                        onClick = { viewModel.connectTo(known) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(known.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Tap to connect · ${known.address}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.forget(known) }) { Text("Forget") }
                        }
                    }
                }
            }

            // A scan that finished and found nothing is the single most
            // confusing state this screen can reach, because it looks
            // identical to one that never ran.
            if (!state.scanning && state.scanned && state.devices.isEmpty()) {
                item(key = "nothing-found") {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Nothing found", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Check the Flipper is awake and that Bluetooth is on under its " +
                                    "Settings → Bluetooth. A Flipper that is asleep does not " +
                                    "advertise, so it will not appear here until you wake it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(state.devices, key = { it.address }) { found ->
                Card(
                    onClick = { viewModel.connect(found) },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text(found.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${found.kind?.displayName ?: "Unrecognised"} · ${found.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: DeviceUiState) {
    val pairing = (state.connectionState as? ConnectionState.Failed)?.reason ==
        FailureReason.PAIRING_REQUIRED

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = when {
                pairing -> MaterialTheme.colorScheme.errorContainer
                state.connected -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = state.connectedName ?: "No device connected",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when (state.autoConnect) {
                    AutoConnector.Status.SEARCHING -> "Looking for your Flipper…"
                    AutoConnector.Status.CONNECTING -> "Reconnecting…"
                    AutoConnector.Status.FAILED ->
                        "Could not reach that device. Wake it and try again."
                    else -> describe(state.connectionState)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (pairing) {
                Text(
                    "Open Android Settings > Bluetooth, forget this Flipper, then connect again " +
                        "and enter the six-digit code it shows. Changing firmware regenerates the " +
                        "device's keys, which invalidates the stored pairing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.firmware?.let { report ->
                Text(
                    text = listOfNotNull(report.label, report.hardwareName)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only when there is something to say. A line reading "tested
                // against this firmware" on every connection is noise that
                // trains the user to stop reading the one time it matters.
                if (report.confidence != FirmwareCompatibility.Confidence.VERIFIED) {
                    Text(
                        text = report.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.confidence ==
                            FirmwareCompatibility.Confidence.INCOMPATIBLE
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun describe(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Connecting -> "Connecting (attempt ${state.attempt})"
    ConnectionState.Discovering -> "Discovering services"
    ConnectionState.Ready -> "Ready to emulate"
    is ConnectionState.Reconnecting ->
        "Reconnecting, attempt ${state.attempt} in ${state.nextRetryInMillis / 1000}s"
    is ConnectionState.Failed -> "Failed: ${state.reason.name.lowercase().replace('_', ' ')}"
}
