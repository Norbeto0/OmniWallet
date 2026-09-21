package dev.omniwallet.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omniwallet.app.session.AppLock
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
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

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
}
