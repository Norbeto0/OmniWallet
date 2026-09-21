package dev.omniwallet.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omniwallet.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val store: SettingsStore,
) : ViewModel() {

    /** Record that this has been seen, whether or not permission was granted. */
    fun complete() {
        viewModelScope.launch { store.setOnboardingComplete(true) }
    }
}
