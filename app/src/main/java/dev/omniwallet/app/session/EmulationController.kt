package dev.omniwallet.app.session

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.core.domain.ConnectionState
import dev.omniwallet.core.domain.CredentialRepository
import dev.omniwallet.core.domain.DeviceException
import dev.omniwallet.core.domain.EmulationHandle
import dev.omniwallet.core.domain.StoredCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What is currently being emitted, for the now-emulating bar. */
data class NowEmulating(
    val credential: StoredCredential,
    val handle: EmulationHandle,
    val startedAtMillis: Long,
)

/**
 * Owns the live emulation session.
 *
 * A singleton rather than screen state because the bar that shows it has to
 * outlive navigation -- the same reason a music player's now-playing bar is not
 * owned by the library screen.
 */
@Singleton
class EmulationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: DeviceConnectionManager,
    private val repository: CredentialRepository,
    scope: CoroutineScope,
) {

    init {
        // Nothing can still be emulating over a link that has gone. Without
        // this the bar would keep ticking against a dead connection, which is
        // the same kind of confident lie the stop-button fix removed.
        scope.launch {
            connections.connectionState.collect { state ->
                if (state !is ConnectionState.Ready && _nowEmulating.value != null) {
                    _nowEmulating.value = null
                }
            }
        }
    }

    private val _nowEmulating = MutableStateFlow<NowEmulating?>(null)
    val nowEmulating: StateFlow<NowEmulating?> = _nowEmulating.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    /**
     * Tap to start, tap again to stop.
     *
     * The card in the list is the control, the way a track row in a player is
     * its own play/pause button. Tapping the card that is already running and
     * having it restart -- which is what it used to do -- is a small betrayal
     * of that: the obvious gesture did the one thing you did not mean.
     */
    suspend fun toggle(credential: StoredCredential): Boolean =
        if (_nowEmulating.value?.credential?.id == credential.id) {
            stop()
        } else {
            emulate(credential)
        }

    /**
     * Start emitting [credential], replacing anything already running.
     *
     * Switching cards is one action from here; the device layer handles closing
     * whatever was running, because only it knows that a Flipper runs one app
     * at a time.
     */
    suspend fun emulate(credential: StoredCredential): Boolean {
        val device = connections.readyDevice() ?: run {
            _lastError.value = "Connect your device first"
            return false
        }

        return try {
            val handle = device.startEmulation(credential.toCredential())
            _nowEmulating.value = NowEmulating(credential, handle, System.currentTimeMillis())
            repository.markUsed(credential.id)
            _lastError.value = null
            // The emulation runs on the Flipper, but the link to it lives in
            // this process, and a backgrounded process is a process the system
            // may reclaim. The service is what stops a card going dead the
            // moment the user switches away to check something.
            attachService()
            true
        } catch (e: DeviceException) {
            _lastError.value = e.message ?: "Could not start emulation"
            false
        }
    }

    /**
     * Stop whatever is running.
     *
     * Failure is surfaced, not swallowed. The device layer throws when it
     * cannot close the running app, and the whole point of that change was to
     * stop the UI announcing "stopped" while the Flipper carried on emitting --
     * so the state is only cleared once the device confirms.
     */
    suspend fun stop(): Boolean {
        val current = _nowEmulating.value ?: return true
        val device = connections.readyDevice() ?: run {
            // The link is gone, so nothing is being driven any more; clearing
            // is honest here, unlike claiming a successful stop.
            _nowEmulating.value = null
            return true
        }

        return try {
            device.stopEmulation(current.handle)
            _nowEmulating.value = null
            _lastError.value = null
            true
        } catch (e: DeviceException) {
            _lastError.value = e.message ?: "Could not stop emulation"
            false
        }
    }

    /**
     * Ask [EmulationService] to hold this session up.
     *
     * Best effort by design. The platform can refuse to start a foreground
     * service from the background, and when it does the emulation is still
     * running on the device and still stoppable from the app -- it simply has
     * no notification and no protection from the process being reclaimed. That
     * is worth degrading to, not worth throwing over.
     */
    private fun attachService() {
        EmulationService.start(
            context,
            Intent(context, EmulationService::class.java).setAction(EmulationService.ACTION_ATTACH),
        )
    }
}
