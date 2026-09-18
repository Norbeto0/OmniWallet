package dev.omniwallet.app.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.transport.ble.BlePermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshReadiness() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ReadinessCard(state, onRequest = { permissionLauncher.launch(BlePermissions.required.toTypedArray()) }) }
                item { ScanCard(state, viewModel) }
                items(state.devices, key = { it.address }) { device ->
                    DeviceRow(
                        name = device.displayName,
                        address = device.address,
                        detail = "rssi ${device.rssi} dBm  ${device.kind?.displayName ?: "unrecognised"}",
                        services = device.advertisedServices,
                        onConnect = { viewModel.connect(device) },
                    )
                }
                item { ConnectionCard(state, viewModel) }
                item { RpcCard(state, viewModel) }
                item { LogCard(state) }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val file = viewModel.exportLog()
                        shareFile(context, file)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Export log") }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ReadinessCard(state: DiagnosticsUiState, onRequest: () -> Unit) {
    SectionCard("Permissions") {
        val r = state.readiness
        Text(if (r.bluetoothEnabled) "Bluetooth: on" else "Bluetooth: OFF -- turn it on to scan")
        if (r.missingPermissions.isEmpty()) {
            Text("All required permissions granted.")
        } else {
            Text("Missing: ${r.missingPermissions.joinToString()}")
            Button(onClick = onRequest) { Text("Grant permissions") }
        }
    }
}

@Composable
private fun ScanCard(state: DiagnosticsUiState, viewModel: DiagnosticsViewModel) {
    SectionCard("Scan") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.filteredScan,
                onClick = { viewModel.setFilteredScan(true) },
                label = { Text("Known devices") },
            )
            FilterChip(
                selected = !state.filteredScan,
                onClick = { viewModel.setFilteredScan(false) },
                label = { Text("Everything") },
            )
        }
        Text(
            if (state.filteredScan) {
                "Filtering on the serial service UUID, which is identical across official, " +
                    "Unleashed and Momentum firmware."
            } else {
                "Unfiltered. Use this if your Flipper does not appear above, and send the log."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.startScan() }, enabled = !state.scanning) { Text("Scan") }
            OutlinedButton(onClick = { viewModel.stopScan() }, enabled = state.scanning) { Text("Stop") }
        }
        Text("${state.devices.size} device(s) seen", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    detail: String,
    services: List<String>,
    onConnect: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(address, style = MaterialTheme.typography.bodySmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (services.isNotEmpty()) {
                Text(
                    services.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun ConnectionCard(state: DiagnosticsUiState, viewModel: DiagnosticsViewModel) {
    SectionCard("Connection") {
        Text("Device: ${state.connectedTo ?: "none"}")
        Text("State: ${describe(state.connectionState)}")
        Text("MTU: ${state.mtu}   credit: ${state.credit}   RPC active: ${state.rpcActive}")

        if (state.connectionState is ConnectionState.Failed &&
            (state.connectionState as ConnectionState.Failed).reason ==
            dev.omniwallet.core.domain.FailureReason.PAIRING_REQUIRED
        ) {
            Text(
                "Pairing needs to be redone. Open Android Settings > Bluetooth, forget this " +
                    "Flipper, then connect again and enter the six-digit code it shows. " +
                    "Changing firmware regenerates the device's keys, which invalidates the " +
                    "pairing Android had stored.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
    }
}

@Composable
private fun RpcCard(state: DiagnosticsUiState, viewModel: DiagnosticsViewModel) {
    var appName by rememberSaveable { mutableStateOf("NFC") }
    var appArgs by rememberSaveable { mutableStateOf("") }

    SectionCard("RPC") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.ping() }) { Text("Ping") }
            Button(onClick = { viewModel.loadDeviceInfo() }) { Text("Device info") }
            Button(onClick = { viewModel.listCredentials() }) { Text("List files") }
        }
        state.pingMillis?.let { Text("Ping round trip: ${it} ms") }

        if (state.deviceInfo.isNotEmpty()) {
            HorizontalDivider()
            listOf("firmware_origin", "firmware_version", "hardware_name", "protobuf_version_major", "protobuf_version_minor")
                .mapNotNull { key -> state.deviceInfo[key]?.let { key to it } }
                .forEach { (k, v) -> Text("$k = $v", style = MaterialTheme.typography.bodySmall) }
        }

        if (state.credentials.isNotEmpty()) {
            HorizontalDivider()
            Text("${state.credentials.size} saved item(s)", style = MaterialTheme.typography.bodySmall)
            state.credentials.take(20).forEach { cred ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${cred.protocol}  ${cred.displayName}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.emulate(cred) }) { Text("Emulate") }
                }
            }
        }

        state.emulating?.let {
            HorizontalDivider()
            Text("Emulating since ${it.startedAtMillis}")
            Button(onClick = { viewModel.stopEmulation() }) { Text("Stop emulation") }
        }

        HorizontalDivider()
        Text(
            "Manual app start. External apps are matched on display name, " +
                "case-sensitively: NFC, 125 kHz RFID, Sub-GHz, Infrared, iButton.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = appName,
            onValueChange = { appName = it },
            label = { Text("App name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = appArgs,
            onValueChange = { appArgs = it },
            label = { Text("File path, e.g. /ext/nfc/office.nfc") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.startAppByName(appName, appArgs) }) { Text("Start app") }
    }
}

@Composable
private fun LogCard(state: DiagnosticsUiState) {
    SectionCard("Event log") {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            state.log.takeLast(200).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun describe(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "disconnected"
    is ConnectionState.Connecting -> "connecting (attempt ${state.attempt})"
    ConnectionState.Discovering -> "discovering services"
    ConnectionState.Ready -> "ready"
    is ConnectionState.Reconnecting ->
        "reconnecting, attempt ${state.attempt} in ${state.nextRetryInMillis} ms"
    is ConnectionState.Failed -> "failed: ${state.reason}"
}
