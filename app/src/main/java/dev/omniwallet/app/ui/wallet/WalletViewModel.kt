package dev.omniwallet.app.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omniwallet.app.session.DeviceConnectionManager
import dev.omniwallet.app.session.EmulationController
import dev.omniwallet.app.session.NowEmulating
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialRepository
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.StoredCredential
import dev.omniwallet.core.domain.WalletOrdering
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletUiState(
    val credentials: List<StoredCredential> = emptyList(),
    val grouped: Map<Protocol, List<StoredCredential>> = emptyMap(),
    val favourites: List<StoredCredential> = emptyList(),
    val protocolFilter: Protocol? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val nowEmulating: NowEmulating? = null,
    val syncing: Boolean = false,
    val message: String? = null,
    val showHidden: Boolean = false,
) {
    val connected: Boolean get() = connectionState is ConnectionState.Ready
    val isEmpty: Boolean get() = credentials.isEmpty()
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: CredentialRepository,
    private val connections: DeviceConnectionManager,
    private val emulation: EmulationController,
) : ViewModel() {

    private val filter = MutableStateFlow<Protocol?>(null)
    private val showHidden = MutableStateFlow(false)
    private val syncing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<WalletUiState> = combine(
        repository.observeCredentials(),
        connections.connectionState,
        emulation.nowEmulating,
        combine(filter, showHidden, syncing) { f, h, s -> Triple(f, h, s) },
        combine(message, emulation.lastError, connections.connectedName) { m, e, n -> Triple(m, e, n) },
    ) { stored, connection, now, (protocolFilter, hidden, isSyncing), (msg, err, name) ->
        val visible = if (hidden) stored else WalletOrdering.visible(stored)
        val filtered = protocolFilter?.let { p -> visible.filter { it.protocol == p } } ?: visible

        WalletUiState(
            credentials = WalletOrdering.sort(filtered),
            grouped = WalletOrdering.groupByProtocol(filtered.filterNot { it.favourite }),
            favourites = WalletOrdering.sort(filtered.filter { it.favourite }),
            protocolFilter = protocolFilter,
            connectionState = connection,
            connectedName = name,
            nowEmulating = now,
            syncing = isSyncing,
            message = msg ?: err,
            showHidden = hidden,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletUiState())

    /** Re-read the library from the device. Safe to call when disconnected. */
    fun sync() {
        val device = connections.readyDevice() ?: run {
            message.value = "Connect your device to refresh"
            return
        }
        viewModelScope.launch {
            syncing.value = true
            try {
                repository.applyScan(DeviceKind.FLIPPER_ZERO, device.listCredentials())
                message.value = null
            } catch (e: Exception) {
                message.value = e.message ?: "Could not read the device"
            } finally {
                syncing.value = false
            }
        }
    }

    fun emulate(credential: StoredCredential) {
        viewModelScope.launch { emulation.emulate(credential) }
    }

    fun stop() {
        viewModelScope.launch { emulation.stop() }
    }

    fun setFilter(protocol: Protocol?) {
        filter.value = protocol
    }

    fun toggleShowHidden() {
        showHidden.value = !showHidden.value
    }

    fun setFavourite(id: CredentialId, favourite: Boolean) {
        viewModelScope.launch { repository.setFavourite(id, favourite) }
    }

    fun rename(id: CredentialId, name: String?) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun setHidden(id: CredentialId, hidden: Boolean) {
        viewModelScope.launch { repository.setHidden(id, hidden) }
    }

    fun dismissMessage() {
        message.value = null
        emulation.clearError()
    }
}
