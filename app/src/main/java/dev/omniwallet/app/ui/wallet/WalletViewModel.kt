package dev.omniwallet.app.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omniwallet.app.session.DeviceConnectionManager
import dev.omniwallet.app.session.EmulationController
import dev.omniwallet.app.session.LocationSource
import dev.omniwallet.app.session.NowEmulating
import dev.omniwallet.app.session.nowElapsedRealtime
import dev.omniwallet.app.ui.settings.AppSettings
import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialRepository
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.core.domain.Fix
import dev.omniwallet.core.domain.Place
import dev.omniwallet.core.domain.Protocol
import dev.omniwallet.core.domain.ProximityRanking
import dev.omniwallet.core.domain.StoredCredential
import dev.omniwallet.core.domain.WalletOrdering
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletUiState(
    val credentials: List<StoredCredential> = emptyList(),
    val grouped: Map<Protocol, List<StoredCredential>> = emptyMap(),
    val favourites: List<StoredCredential> = emptyList(),
    val nearby: List<ProximityRanking.Nearby> = emptyList(),
    val protocolFilter: Protocol? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val nowEmulating: NowEmulating? = null,
    val syncing: Boolean = false,
    val locating: Boolean = false,
    val message: String? = null,
    val showHidden: Boolean = false,
    val nearbyEnabled: Boolean = true,
) {
    val connected: Boolean get() = connectionState is ConnectionState.Ready
    val isEmpty: Boolean get() = credentials.isEmpty()

    /** Whether reading a location could serve any purpose at all. */
    val anyPlaceTagged: Boolean get() = credentials.any { it.place != null }
}

/** Everything this screen owns, as opposed to everything it observes. */
private data class Local(
    val filter: Protocol? = null,
    val showHidden: Boolean = false,
    val syncing: Boolean = false,
    val locating: Boolean = false,
    val message: String? = null,
    val fix: Fix? = null,
)

private data class Device(val state: ConnectionState, val name: String?)

private data class Emulation(val now: NowEmulating?, val error: String?)

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: CredentialRepository,
    private val connections: DeviceConnectionManager,
    private val emulation: EmulationController,
    private val location: LocationSource,
    private val settings: SettingsStore,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    /** Expires the current fix, so a stale Nearby section cannot linger. */
    private var fixExpiry: Job? = null

    val state: StateFlow<WalletUiState> = combine(
        repository.observeCredentials(),
        combine(connections.connectionState, connections.connectedName, ::Device),
        combine(emulation.nowEmulating, emulation.lastError, ::Emulation),
        combine(local, settings.settings) { own, prefs -> own to prefs },
    ) { stored, device, running, (own, prefs) ->
        val visible = if (own.showHidden) stored else WalletOrdering.visible(stored)
        val filtered = own.filter?.let { p -> visible.filter { it.protocol == p } } ?: visible

        WalletUiState(
            credentials = WalletOrdering.sort(filtered),
            grouped = WalletOrdering.groupByProtocol(filtered.filterNot { it.favourite }),
            favourites = WalletOrdering.sort(filtered.filter { it.favourite }),
            nearby = nearbyOf(filtered, own.fix, prefs),
            protocolFilter = own.filter,
            connectionState = device.state,
            connectedName = device.name,
            nowEmulating = running.now,
            syncing = own.syncing,
            locating = own.locating,
            message = own.message ?: running.error,
            showHidden = own.showHidden,
            nearbyEnabled = prefs.nearbyRanking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletUiState())

    /**
     * The Nearby section, or nothing.
     *
     * Ranged over the *filtered* list rather than everything stored, so a
     * protocol filter still means what it says. The section only ever adds
     * suggestions above the wallet -- it never removes a card from the list
     * below it.
     */
    private fun nearbyOf(
        credentials: List<StoredCredential>,
        fix: Fix?,
        prefs: AppSettings,
    ): List<ProximityRanking.Nearby> {
        if (!prefs.nearbyRanking) return emptyList()
        return ProximityRanking.nearby(credentials, fix, nowElapsedRealtime())
    }

    /** Re-read the library from the device. Safe to call when disconnected. */
    fun sync() {
        val device = connections.readyDevice() ?: run {
            local.update { it.copy(message = "Connect your device to refresh") }
            return
        }
        viewModelScope.launch {
            local.update { it.copy(syncing = true) }
            try {
                repository.applyScan(DeviceKind.FLIPPER_ZERO, device.listCredentials())
                local.update { it.copy(message = null) }
            } catch (e: Exception) {
                local.update { it.copy(message = e.message ?: "Could not read the device") }
            } finally {
                local.update { it.copy(syncing = false) }
            }
        }
    }

    /**
     * Refresh the position behind the Nearby section.
     *
     * Called when the wallet comes into view, and only then. It short-circuits
     * unless the user has actually tagged something, so a wallet with no places
     * in it never touches the location APIs at all -- the feature costs nothing
     * to the people who do not use it.
     */
    fun refreshLocation() {
        viewModelScope.launch {
            val prefs = settings.settings.first()
            if (!prefs.nearbyRanking) return@launch
            if (state.value.credentials.none { it.place != null }) return@launch
            if (!location.hasPermission()) return@launch
            captureFix()
        }
    }

    /**
     * Tag this credential at the phone's current position.
     *
     * The label defaults to the card's own name, because "Office key" tagged at
     * "Office key" reads perfectly well and asking for a second name before
     * anything works would be a form to fill in for a one-tap feature.
     */
    fun tagHere(credential: StoredCredential, label: String? = null) {
        viewModelScope.launch {
            if (!location.hasPermission()) {
                local.update { it.copy(message = "Location permission is needed to tag a place") }
                return@launch
            }
            local.update { it.copy(locating = true) }
            val fix = try {
                captureFix()
            } finally {
                local.update { it.copy(locating = false) }
            }
            if (fix == null) {
                local.update {
                    it.copy(message = "Could not get a location. Try again outdoors or near wifi.")
                }
                return@launch
            }
            val name = label?.takeIf { it.isNotBlank() } ?: credential.displayName
            repository.setPlace(credential.id, Place(name, fix.latitude, fix.longitude))
        }
    }

    /** Rename a place without moving it. */
    fun renamePlace(credential: StoredCredential, label: String) {
        val place = credential.place ?: return
        viewModelScope.launch { repository.setPlace(credential.id, place.copy(label = label)) }
    }

    fun clearPlace(id: CredentialId) {
        viewModelScope.launch { repository.setPlace(id, null) }
    }

    /**
     * Take a fix and arm its expiry.
     *
     * The expiry is the point. Without an emission when the fix ages out, a
     * Nearby section stays on screen long after the phone has left the place it
     * describes -- correct at the moment it was computed and a lie a minute
     * later.
     */
    private suspend fun captureFix(): Fix? {
        val fix = location.current()
        local.update { it.copy(fix = fix) }
        fixExpiry?.cancel()
        if (fix != null) {
            fixExpiry = viewModelScope.launch {
                delay(ProximityRanking.MAX_FIX_AGE_MILLIS)
                local.update { if (it.fix === fix) it.copy(fix = null) else it }
            }
        }
        return fix
    }

    /** Tap a card to start it; tap the running one again to stop. */
    fun toggle(credential: StoredCredential) {
        viewModelScope.launch { emulation.toggle(credential) }
    }

    fun stop() {
        viewModelScope.launch { emulation.stop() }
    }

    fun setFilter(protocol: Protocol?) {
        local.update { it.copy(filter = protocol) }
    }

    fun toggleShowHidden() {
        local.update { it.copy(showHidden = !it.showHidden) }
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
        local.update { it.copy(message = null) }
        emulation.clearError()
    }
}
