package dev.omniwallet.app.quick

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.omniwallet.app.session.DeviceConnectionManager
import dev.omniwallet.app.session.EmulationController
import dev.omniwallet.core.domain.CredentialRepository
import dev.omniwallet.core.domain.StoredCredential
import dev.omniwallet.core.domain.WalletOrdering
import kotlinx.coroutines.flow.first

/**
 * The home-screen widget: the cards you are most likely to want, one tap away.
 *
 * The brief's whole premise is "tap the phone instead of navigating the
 * device's menus". A widget is the shortest version of that -- it removes the
 * app from the sequence entirely.
 *
 * Tapping a row starts it immediately rather than opening the app, which was
 * the user's explicit choice. The safety rules still apply and still live in
 * `QuickActionPolicy`: with the app lock on, a tap opens the app to
 * authenticate instead, because a widget cannot show a biometric prompt.
 *
 * Note what this does *not* do: it never emulates from inside the widget
 * process. A tap hands off to [dev.omniwallet.app.session.EmulationService],
 * because waking a sleeping Flipper can take ten seconds and a widget callback
 * does not live that long.
 */
class WalletWidget : GlanceAppWidget() {

    companion object {
        /**
         * Rows to show.
         *
         * Small on purpose. A widget that lists a whole library is a worse
         * version of the app; this is for the two or three cards someone uses
         * daily.
         */
        const val MAX_ROWS = 6
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun repository(): CredentialRepository
        fun connections(): DeviceConnectionManager
        fun emulation(): EmulationController
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Glance composables are built outside Hilt's reach, so the graph is
        // entered by hand rather than by injection.
        val entryPoint = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val stored = entryPoint.repository().observeCredentials().first()
        val cards = WalletOrdering
            .sort(WalletOrdering.visible(stored).filter { it.present })
            .take(MAX_ROWS)

        val connected = entryPoint.connections().isReady
        val runningId = entryPoint.emulation().nowEmulating.value?.credential?.id

        provideContent {
            GlanceTheme {
                WidgetBody(
                    cards = cards,
                    connected = connected,
                    runningIdValue = runningId?.value,
                )
            }
        }
    }
}

@Composable
private fun WidgetBody(
    cards: List<StoredCredential>,
    connected: Boolean,
    runningIdValue: String?,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(12.dp),
    ) {
        Text(
            text = if (connected) "OmniWallet" else "OmniWallet · not connected",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(6.dp))

        // The empty library is the first thing a new user sees here, not an
        // edge case, so it gets a sentence rather than blank space.
        if (cards.isEmpty()) {
            Text(
                text = "Open OmniWallet and connect your device to load your cards.",
                style = TextStyle(color = GlanceTheme.colors.onSurface),
            )
            return@Column
        }

        cards.forEach { card ->
            WidgetRow(card = card, running = card.id.value == runningIdValue)
            Spacer(GlanceModifier.height(4.dp))
        }
    }
}

@Composable
private fun WidgetRow(card: StoredCredential, running: Boolean) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(
                if (running) GlanceTheme.colors.primaryContainer
                else GlanceTheme.colors.secondaryContainer,
            )
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(
                actionRunCallback<EmulateAction>(
                    actionParametersOf(EmulateAction.CREDENTIAL_ID to card.id.value),
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = card.displayName,
                style = TextStyle(
                    color = if (running) GlanceTheme.colors.onPrimaryContainer
                    else GlanceTheme.colors.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            // Says what a tap will do, rather than leaving the user to
            // remember whether this one is the card already running.
            Text(
                text = if (running) "Emulating · tap to stop" else "Tap to emulate",
                style = TextStyle(
                    color = if (running) GlanceTheme.colors.onPrimaryContainer
                    else GlanceTheme.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Receiver half of the widget. Nothing to configure; Glance does the work. */
class WalletWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WalletWidget()
}
