package dev.omniwallet.app.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omniwallet.app.ui.theme.LocalIsDarkTheme
import dev.omniwallet.app.ui.theme.style
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.StoredCredential

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onOpenDevice: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var detailsFor by remember { mutableStateOf<StoredCredential?>(null) }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    // Refresh on arrival when the device is ready, so the library is current
    // without anyone having to think about it.
    LaunchedEffect(state.connected) {
        if (state.connected) viewModel.sync()
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Wallet") },
                actions = {
                    IconButton(onClick = { viewModel.sync() }, enabled = state.connected) {
                        if (state.syncing) {
                            CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh from device")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ConnectionCard(
                    connected = state.connected,
                    deviceName = state.connectedName,
                    onClick = onOpenDevice,
                )
            }

            if (state.credentials.isNotEmpty() || state.protocolFilter != null) {
                item {
                    ProtocolFilterRow(
                        selected = state.protocolFilter,
                        onSelect = viewModel::setFilter,
                    )
                }
            }

            if (state.isEmpty) {
                item { EmptyState(connected = state.connected, onOpenDevice = onOpenDevice) }
            }

            if (state.favourites.isNotEmpty()) {
                item { SectionHeader("Favourites") }
                items(state.favourites, key = { it.id.value }) { credential ->
                    CredentialCard(
                        credential = credential,
                        enabled = state.connected && credential.present,
                        emulating = state.nowEmulating?.credential?.id == credential.id,
                        onEmulate = { viewModel.toggle(credential) },
                        onDetails = { detailsFor = credential },
                        onToggleFavourite = { viewModel.setFavourite(credential.id, false) },
                    )
                }
            }

            state.grouped.forEach { (protocol, credentials) ->
                item(key = "header-${protocol.name}") { SectionHeader(protocol.style().label) }
                items(credentials, key = { it.id.value }) { credential ->
                    CredentialCard(
                        credential = credential,
                        enabled = state.connected && credential.present,
                        emulating = state.nowEmulating?.credential?.id == credential.id,
                        onEmulate = { viewModel.toggle(credential) },
                        onDetails = { detailsFor = credential },
                        onToggleFavourite = { viewModel.setFavourite(credential.id, true) },
                    )
                }
            }
        }
    }

    detailsFor?.let { credential ->
        CredentialDetailsSheet(
            credential = credential,
            onDismiss = { detailsFor = null },
            onRename = { viewModel.rename(credential.id, it) },
            onToggleFavourite = { viewModel.setFavourite(credential.id, !credential.favourite) },
            onToggleHidden = { viewModel.setHidden(credential.id, !credential.hidden) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ConnectionCard(connected: Boolean, deviceName: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (connected) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
                contentDescription = null,
                tint = if (connected) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (connected) deviceName ?: "Connected" else "No device connected",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (connected) {
                        "Ready to emulate"
                    } else {
                        "Tap to connect your Flipper"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProtocolFilterRow(selected: Protocol?, onSelect: (Protocol?) -> Unit) {
    val dark = LocalIsDarkTheme.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
        )
        Protocol.entries.forEach { protocol ->
            val style = protocol.style()
            FilterChip(
                selected = selected == protocol,
                onClick = { onSelect(if (selected == protocol) null else protocol) },
                label = { Text(style.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = style.container(dark),
                    selectedLabelColor = style.color(dark),
                ),
            )
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean, onOpenDevice: () -> Unit) {
    Card(
        onClick = onOpenDevice,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (connected) "Nothing saved on the device yet" else "Connect a device",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (connected) {
                    "Cards you save on the Flipper appear here automatically."
                } else {
                    "Your cards live on the Flipper. Connect it to read the library."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
