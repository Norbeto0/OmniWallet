package dev.omniwallet.app.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.omniwallet.app.session.EmulationService
import dev.omniwallet.app.session.QuickActions
import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.QuickActionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lets another app -- Tasker, an NFC tag, a launcher shortcut -- ask OmniWallet
 * to emulate a card.
 *
 * ### This is an exported trigger for physical-access credentials
 *
 * Worth stating plainly rather than burying: anything on the phone that can
 * send a broadcast can reach this receiver. That is what makes it useful and
 * it is also exactly what makes it a liability, so it is fenced:
 *
 *  - **Off by default.** `automationEnabled` starts false and only the user
 *    can change it.
 *  - **Refused while the app lock is on.** A broadcast cannot be
 *    authenticated, and silently bypassing a lock the user switched on would
 *    make that lock decorative.
 *  - **Every call is recorded.** [QuickActions.lastOutcome] surfaces in
 *    Settings, so a trigger firing when it should not is visible rather than
 *    invisible.
 *
 * The first two rules are enforced in `QuickActionPolicy` alongside the widget
 * and tile rules, not here, so there is one place to read them and one place
 * for them to be wrong.
 *
 * Usage, for anyone writing a rule:
 *
 * ```
 * am broadcast -a dev.omniwallet.action.EMULATE \
 *   -n dev.omniwallet.app/.quick.AutomationReceiver \
 *   --es name "Office door"
 * ```
 */
@AndroidEntryPoint
class AutomationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EMULATE = "dev.omniwallet.action.EMULATE"
        const val ACTION_STOP = "dev.omniwallet.action.STOP"

        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
    }

    @Inject
    lateinit var quickActions: QuickActions

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_EMULATE && action != ACTION_STOP) return

        // goAsync would buy about ten seconds, which is not enough to wake a
        // sleeping Flipper and is exactly the sort of "usually works" this
        // project keeps removing. The service gets the work instead.
        val pending = goAsync()
        scope.launch(Dispatchers.Default) {
            try {
                dispatch(context, action, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dispatch(context: Context, action: String, intent: Intent) {
        // Checked here as well as in the policy, because refusing before
        // starting a service is cheaper and leaves no notification behind.
        if (!settings.settings.first().automationEnabled) {
            quickActions.recordExternalRefusal(
                "Refused an automation request: automation is switched off",
            )
            return
        }

        val serviceIntent = when {
            action == Companion.ACTION_STOP ->
                EmulationService.stopIntent(context, QuickActionPolicy.Source.AUTOMATION)

            intent.getStringExtra(EXTRA_ID) != null -> EmulationService.toggleIntent(
                context,
                CredentialId(intent.getStringExtra(EXTRA_ID)!!),
                QuickActionPolicy.Source.AUTOMATION,
            )

            intent.getStringExtra(EXTRA_NAME) != null -> EmulationService.toggleByNameIntent(
                context,
                intent.getStringExtra(EXTRA_NAME)!!,
                QuickActionPolicy.Source.AUTOMATION,
            )

            else -> {
                quickActions.recordExternalRefusal(
                    "Refused an automation request: no card id or name was given",
                )
                return
            }
        }

        if (!EmulationService.start(context, serviceIntent)) {
            // Not a bug and not something this app can work around: Android 12+
            // forbids starting a foreground service from the background, and a
            // broadcast from another app usually has no exemption. Said plainly
            // rather than reported as a generic failure, because the remedy is
            // the user's (open the app first) and a vague message would send
            // them looking for one that does not exist.
            quickActions.recordExternalRefusal(
                "Android blocked the automation request: " +
                    "open OmniWallet once, then trigger it again",
            )
        }
    }
}
