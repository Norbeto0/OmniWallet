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
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (!state.readiness.bluetoothEnabled) {
                                    "Bluetooth is off"
                                } else {
                                    "Permission needed"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (state.readiness.missingPermissions.isNotEmpty()) {
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
            state.firmware?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
