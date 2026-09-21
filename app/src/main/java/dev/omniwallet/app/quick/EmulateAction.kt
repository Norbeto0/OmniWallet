package dev.omniwallet.app.quick

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dev.omniwallet.app.session.EmulationService
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.QuickActionPolicy

/**
 * What a widget tap does.
 *
 * Deliberately almost nothing: it hands the request to
 * [EmulationService] and returns. Doing the work here instead would put a
 * fifteen-second reconnect inside a callback the system expects to finish in
 * well under a second, and it would be cancelled halfway through -- leaving a
 * Flipper in an unknown state, which is the worst of the available outcomes.
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
        EmulationService.start(
            context,
            EmulationService.toggleIntent(
                context,
                CredentialId(id),
                QuickActionPolicy.Source.WIDGET,
            ),
        )
    }
}
