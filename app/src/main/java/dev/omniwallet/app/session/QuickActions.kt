package dev.omniwallet.app.session

import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.CredentialRepository
import dev.omniwallet.core.domain.QuickActionPolicy
import dev.omniwallet.core.domain.StoredCredential
import dev.omniwallet.core.domain.WalletOrdering
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one door every surface outside the app comes through.
 *
 * The widget, the Quick Settings tile and the automation intent all want the
 * same thing -- "emulate this card" -- from contexts that have no UI, no
 * authentication and, in the automation case, no guarantee the request came
 * from the person holding the phone. Giving each of them its own path to
 * [EmulationController] would mean three places to get the rules right and
 * three places for them to drift apart. There is one.
 *
 * The rules themselves live in [QuickActionPolicy], which is pure and tested.
 * This class is the part that cannot be: reading settings, waiting for a radio,
 * driving the device.
 */
@Singleton
class QuickActions @Inject constructor(
    private val repository: CredentialRepository,
    private val emulation: EmulationController,
    private val connections: DeviceConnectionManager,
    private val autoConnector: AutoConnector,
    private val settings: SettingsStore,
) {

    companion object {
        /**
         * How long a quick action will wait for the device to come up.
         *
         * Generous compared with the app's own auto-connect scan, because
         * nobody is watching a spinner here -- the alternative to waiting is a
         * notification saying it did not work, which is worse than a few
         * seconds.
         */
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }

    /** What happened, in terms a notification or a toast can repeat verbatim. */
    sealed interface Outcome {
        val message: String

        data class Started(val name: String) : Outcome {
            override val message: String get() = "Emulating $name"
        }

        data object Stopped : Outcome {
            override val message: String get() = "Stopped emulating"
        }

        data class Refused(override val message: String) : Outcome

        /** Needs a human at the phone. The caller should open the activity. */
        data object NeedsUnlock : Outcome {
            override val message: String get() = "Open OmniWallet to unlock first"
        }
    }

    /**
     * The last thing a surface outside the app did, for Settings to show.
     *
     * An exported trigger that leaves no trace is an exported trigger nobody
     * can audit. This is the cheapest honest version of that: the user can
     * always see what the last request was and whether it worked.
     */
    private val _lastOutcome = MutableStateFlow<String?>(null)
    val lastOutcome: StateFlow<String?> = _lastOutcome.asStateFlow()

    /** Start [id], or stop it if it is what is already running. */
    suspend fun toggle(source: QuickActionPolicy.Source, id: CredentialId): Outcome = record {
        val target = repository.observeCredentials().first().firstOrNull { it.id == id }
        act(source, QuickActionPolicy.Request.START, target)
    }

    /**
     * Start the card matching [name], for callers that have a name and not an
     * internal id.
     *
     * Case-insensitive, because someone writing a Tasker rule is typing what
     * they see on the card, not reproducing it exactly. An ambiguous name is
     * refused rather than resolved by guessing -- picking one of two doors
     * because they are called the same thing is not a decision to make on the
     * user's behalf.
     */
    suspend fun toggleByName(source: QuickActionPolicy.Source, name: String): Outcome = record {
        val matches = repository.observeCredentials().first()
            .filter { it.displayName.equals(name.trim(), ignoreCase = true) }

        when (matches.size) {
            0 -> Outcome.Refused("No card called \"$name\"")
            1 -> act(source, QuickActionPolicy.Request.START, matches.single())
            else -> Outcome.Refused("More than one card is called \"$name\"")
        }
    }

    suspend fun stop(source: QuickActionPolicy.Source): Outcome = record {
        act(source, QuickActionPolicy.Request.STOP, target = null)
    }

    private suspend fun act(
        source: QuickActionPolicy.Source,
        request: QuickActionPolicy.Request,
        target: StoredCredential?,
    ): Outcome {
        val conditions = QuickActionPolicy.Conditions(
            appLockEnabled = settings.settings.first().appLockEnabled,
            automationEnabled = settings.settings.first().automationEnabled,
            target = target,
            alreadyRunning = target != null &&
                emulation.nowEmulating.value?.credential?.id == target.id,
        )

        return when (val decision = QuickActionPolicy.decide(source, request, conditions)) {
            is QuickActionPolicy.Decision.Refuse -> Outcome.Refused(decision.reason)
            QuickActionPolicy.Decision.NeedsUnlock -> Outcome.NeedsUnlock

            QuickActionPolicy.Decision.Stop ->
                if (emulation.stop()) {
                    Outcome.Stopped
                } else {
                    Outcome.Refused(emulation.lastError.value ?: "Could not stop emulation")
                }

            QuickActionPolicy.Decision.Start -> {
                // Non-null: the policy refuses a start with no target, so
                // reaching here means one was found.
                val credential = requireNotNull(target)
                val known = SettingsStore.explicitConnectTarget(settings.settings.first())
                if (known == null) {
                    // Two different problems, two different remedies. "Could
                    // not reach your device" sends someone hunting for a
                    // Flipper that this app has never been introduced to.
                    Outcome.Refused("Connect your device in OmniWallet once first")
                } else if (!ensureConnected()) {
                    Outcome.Refused("Could not reach ${known.name}. Is it awake?")
                } else if (emulation.emulate(credential)) {
                    Outcome.Started(credential.displayName)
                } else {
                    Outcome.Refused(emulation.lastError.value ?: "Could not start emulation")
                }
            }
        }
    }

    /**
     * What a single-button surface should act on: whatever is running, else the
     * card the user would most likely reach for next.
     *
     * Uses the wallet's own ordering rather than a second opinion, so the tile
     * and the top of the list agree about what "most likely" means.
     */
    suspend fun quickTarget(): StoredCredential? {
        emulation.nowEmulating.value?.let { return it.credential }
        val visible = WalletOrdering.visible(repository.observeCredentials().first())
            .filter { it.present }
        return WalletOrdering.sort(visible).firstOrNull()
    }

    /**
     * Bring the link up if it is not already.
     *
     * Uses `connectTo` rather than `tryReconnect`. The difference matters:
     * `tryReconnect` is the *automatic* path and gives up when auto-connect is
     * switched off or the device was forgotten by a deliberate disconnect --
     * which meant one tap on Disconnect permanently broke every widget and
     * tile tap thereafter. Someone tapping a card has asked for it, so this
     * reaches for the last device used, or failing that the most recent one
     * they have ever connected to.
     */
    private suspend fun ensureConnected(): Boolean {
        if (connections.isReady) return true
        val target = SettingsStore.explicitConnectTarget(settings.settings.first())
            ?: return false
        autoConnector.connectTo(target.address, target.name)
        return connections.awaitReady(CONNECT_TIMEOUT_MILLIS)
    }

    private suspend fun record(block: suspend () -> Outcome): Outcome =
        block().also { _lastOutcome.value = it.message }

    /** Record a refusal decided before this class was reached. */
    fun recordExternalRefusal(message: String) {
        _lastOutcome.value = message
    }
}
