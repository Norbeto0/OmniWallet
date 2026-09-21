package dev.omniwallet.app.quick

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.app.session.DeviceConnectionManager
import dev.omniwallet.app.session.EmulationController
import dev.omniwallet.core.domain.CredentialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the home-screen widget honest.
 *
 * A Glance widget only redraws when something tells it to, and this one is
 * declared with `updatePeriodMillis="0"` -- no polling, because polling to
 * discover a change this process already knows about is pure battery cost.
 *
 * Without this the widget would keep saying "Tap to emulate" on a card that is
 * already running, and would keep offering cards from a device that is no
 * longer connected. Both are the same failure: a control that reports a state
 * the system is not actually in. The whole tap-again-to-stop design depends on
 * the row telling the truth about what a tap will do.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CredentialRepository,
    private val connections: DeviceConnectionManager,
    private val emulation: EmulationController,
    private val scope: CoroutineScope,
) {

    companion object {
        /**
         * Coalesces bursts into one redraw.
         *
         * A device scan writes the whole library in one transaction, which
         * arrives as a flurry of emissions; redrawing for each would be a
         * visible flicker on the home screen for no gain.
         */
        const val DEBOUNCE_MILLIS = 300L
    }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(
                repository.observeCredentials(),
                connections.connectionState,
                emulation.nowEmulating,
            ) { credentials, connection, running ->
                // A fingerprint rather than the objects themselves, so an
                // identical redraw is dropped rather than repainted.
                Triple(
                    credentials.map { it.id.value to it.displayName },
                    connection::class.simpleName,
                    running?.credential?.id?.value,
                )
            }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MILLIS)
                .collect {
                    // Never fatal. A widget that fails to redraw is a stale
                    // widget; a crash here would take the app's whole
                    // application scope with it.
                    runCatching { WalletWidget().updateAll(context) }
                }
        }
    }
}
