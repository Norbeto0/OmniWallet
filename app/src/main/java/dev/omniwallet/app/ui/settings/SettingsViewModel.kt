package dev.omniwallet.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omniwallet.app.session.AppLock
import dev.omniwallet.app.session.QuickActions
import dev.omniwallet.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val appLock: AppLock,
    quickActions: QuickActions,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * What the widget, tile or an automation rule last did.
     *
     * Surfaced in Settings because an exported trigger nobody can audit is an
     * exported trigger nobody should switch on.
     */
    val lastQuickAction: StateFlow<String?> = quickActions.lastOutcome

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { store.setDynamicColor(enabled) }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            store.setAppLockEnabled(enabled)
            // Turning the lock off must not strand the user behind it.
            appLock.onLockSettingChanged(enabled)
        }
    }

    fun setLockGrace(millis: Long) {
        viewModelScope.launch { store.setLockGraceMillis(millis) }
    }

    fun setBlockScreenshots(enabled: Boolean) {
        viewModelScope.launch { store.setBlockScreenshots(enabled) }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { store.setAutoConnect(enabled) }
    }

    fun setNearbyRanking(enabled: Boolean) {
        viewModelScope.launch { store.setNearbyRanking(enabled) }
    }

    fun setAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setAutomationEnabled(enabled) }
    }
}
