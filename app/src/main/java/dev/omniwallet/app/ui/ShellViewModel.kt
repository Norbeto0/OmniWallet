package dev.omniwallet.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.omniwallet.app.session.EmulationController
import dev.omniwallet.app.session.NowEmulating
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the shell: just enough to drive the now-emulating bar. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val emulation: EmulationController,
) : ViewModel() {

    val nowEmulating: StateFlow<NowEmulating?> = emulation.nowEmulating

    fun stop() {
        viewModelScope.launch { emulation.stop() }
    }
}
