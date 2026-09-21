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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omniwallet.app.session.LocationSource
import dev.omniwallet.app.ui.theme.LocalIsDarkTheme
import dev.omniwallet.app.ui.theme.style
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.ProximityRanking
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

    // The id, not the credential. Holding the object would freeze the sheet on
    // the values it had when it opened, so tagging a place from inside it would
    // appear to do nothing until the sheet was closed and reopened.
    var detailsForId by remember { mutableStateOf<CredentialId?>(null) }
    val detailsFor = detailsForId?.let { id -> state.credentials.firstOrNull { it.id == id } }

    // Asking is the same call whether or not the permission is already held --
    // the contract returns immediately when it is -- so there is one path here
    // rather than a granted branch and an ungranted one. A refusal falls
    // through to tagHere, which reports it; the view model owns that message so
    // the two ways of reaching it cannot drift apart.
    var pendingTag by remember { mutableStateOf<StoredCredential?>(null) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingTag?.let(viewModel::tagHere)
        pendingTag = null
    }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    // Refresh on arrival when the device is ready, so the library is current
    // without anyone having to think about it.
    LaunchedEffect(state.connected) {
        if (state.connected) viewModel.sync()
    }

    // On resume rather than on first composition: coming back to the app after
    // walking somewhere is exactly the case this feature is for. The view model
    // short-circuits when nothing is tagged, so this costs nothing until the
    // user opts in by tagging something.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshLocation()
        onPauseOrDispose { }
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

            if (state.searchWorthwhile) {
                item(key = "search") {
                    SearchField(
                        query = state.query,
                        onQueryChange = viewModel::setQuery,
                    )
                }
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
                item {
                    EmptyState(
                        connected = state.connected,
                        emptyByFilter = state.emptyByFilter,
                        query = state.query,
                        filtered = state.protocolFilter != null,
                        hiddenCount = state.hiddenCount,
                        showHidden = state.showHidden,
                        onClearFilter = {
                            viewModel.setFilter(null)
                            viewModel.setQuery("")
                        },
                        onShowHidden = viewModel::toggleShowHidden,
                        onOpenDevice = onOpenDevice,
                    )
                }
            }

            if (state.nearby.isNotEmpty()) {
                item(key = "header-nearby") { SectionHeader("Nearby") }
                items(state.nearby, key = { "nearby-${it.credential.id.value}" }) { near ->
                    val credential = near.credential
                    CredentialCard(
                        credential = credential,
                        enabled = state.connected && credential.present,
                        emulating = state.nowEmulating?.credential?.id == credential.id,
                        onEmulate = { viewModel.toggle(credential) },
                        onDetails = { detailsForId = credential.id },
                        onToggleFavourite = {
                            viewModel.setFavourite(credential.id, !credential.favourite)
                        },
                        subtitleOverride = credential.place?.let { place ->
                            "${place.label} · ${ProximityRanking.formatDistance(near.distanceMetres)}"
                        },
                    )
                }
            }

            if (state.favourites.isNotEmpty()) {
                item { SectionHeader("Favourites") }
                items(state.favourites, key = { it.id.value }) { credential ->
                    CredentialCard(
                        credential = credential,
                        enabled = state.connected && credential.present,
                        emulating = state.nowEmulating?.credential?.id == credential.id,
                        onEmulate = { viewModel.toggle(credential) },
                        onDetails = { detailsForId = credential.id },
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
                        onDetails = { detailsForId = credential.id },
                        onToggleFavourite = { viewModel.setFavourite(credential.id, true) },
                    )
                }
            }
        }
    }

    detailsFor?.let { credential ->
        CredentialDetailsSheet(
            credential = credential,
            locating = state.locating,
            onDismiss = { detailsForId = null },
            onRename = { viewModel.rename(credential.id, it) },
            onToggleFavourite = { viewModel.setFavourite(credential.id, !credential.favourite) },
            onToggleHidden = { viewModel.setHidden(credential.id, !credential.hidden) },
            onTagHere = {
                pendingTag = credential
                locationPermission.launch(LocationSource.PERMISSIONS.toTypedArray())
            },
            onRenamePlace = { viewModel.renamePlace(credential, it) },
            onClearPlace = { viewModel.clearPlace(credential.id) },
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

/**
 * Four different empties, which used to be one.
 *
 * An empty list has more than one cause and they need different sentences.
 * Telling someone with forty cards and a sub-GHz filter on that they have
 * "nothing saved on the device yet" is not a rough edge, it is a false
 * statement, and it sends them to the Device tab to fix a problem they do not
 * have.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search your cards") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyState(
    connected: Boolean,
    emptyByFilter: Boolean,
    query: String,
    filtered: Boolean,
    hiddenCount: Int,
    showHidden: Boolean,
    onClearFilter: () -> Unit,
    onShowHidden: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    val everythingHidden = emptyByFilter && !filtered && !showHidden && hiddenCount > 0

    val title: String
    val body: String
    val action: Pair<String, () -> Unit>?

    when {
        everythingHidden -> {
            title = "Everything here is hidden"
            body = "You have $hiddenCount hidden ${if (hiddenCount == 1) "card" else "cards"} " +
                "and nothing else. They are still saved."
            action = "Show hidden" to onShowHidden
        }

        emptyByFilter && query.isNotEmpty() -> {
            title = "Nothing matches \"$query\""
            body = "Search looks at the name you gave a card, the name on the device, and any " +
                "place you tagged it."
            action = "Clear search" to onClearFilter
        }

        emptyByFilter -> {
            title = "No cards match this filter"
            body = "Your other cards are still here — this filter just does not match any of them."
            action = "Show all" to onClearFilter
        }

        connected -> {
            title = "Nothing saved on the device yet"
            body = "Save a card on the Flipper and it appears here on the next refresh. " +
                "OmniWallet only reads — it never writes to your device."
            action = null
        }

        else -> {
            title = "Connect a device"
            body = "Your cards live on the Flipper. Connect it to read the library."
            action = "Go to Device" to onOpenDevice
        }
    }

    Card(
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
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(label) }
            }
        }
    }
}
