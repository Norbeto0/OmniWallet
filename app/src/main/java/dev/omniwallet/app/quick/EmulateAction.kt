package dev.omniwallet.app.quick

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dagger.hilt.android.EntryPointAccessors
import dev.omniwallet.app.session.EmulationService
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.QuickActionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a widget tap does.
 *
 * Deliberately almost nothing: it hands the request to [EmulationService] and
 * returns. Doing the work here instead would put a fifteen-second reconnect
 * inside a callback the system expects to finish in well under a second, and
 * it would be cancelled halfway through -- leaving a Flipper in an unknown
 * state, which is the worst of the available outcomes.
 *
 * It does, however, check whether the hand-off was accepted. Android 12+ can
 * refuse a background foreground-service start, and this used to discard that
 * answer, so a refused tap and a broken tap looked identical: nothing happened
 * and nothing said why. A tap now always ends in either emulation or a message.
 */
class EmulateAction : ActionCallback {

    companion object {
        val CREDENTIAL_ID = ActionParameters.Key<String>("credential_id")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val id = parameters[CREDENTIAL_ID] ?: return

        val started = EmulationService.start(
            context,
            EmulationService.toggleIntent(
                context,
                CredentialId(id),
                QuickActionPolicy.Source.WIDGET,
            ),
        )
        if (started) return

        // The service never ran, so it cannot post its own notification. Say
        // it here instead, and record it where Settings will show it.
        val message = "Android would not let OmniWallet start from the home " +
            "screen. Open the app once, then try again."

        EntryPointAccessors
            .fromApplication(context.applicationContext, WalletWidget.WidgetEntryPoint::class.java)
            .quickActions()
            .recordExternalRefusal(message)

        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
