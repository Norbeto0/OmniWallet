package dev.omniwallet.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.omniwallet.app.MainActivity
import dev.omniwallet.app.R
import dev.omniwallet.core.domain.CredentialId
import dev.omniwallet.core.domain.ServiceLifecyclePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps an emulation alive while the app is not in front, and carries out the
 * requests that arrive from outside the app.
 *
 * ### Why a service at all
 *
 * The emulation runs on the Flipper, not the phone, but the *link* to it lives
 * in this process. Backgrounding the app makes that process a candidate for
 * being killed, and the link dying mid-tap-in is the one failure this app
 * cannot explain away. A foreground service of type `connectedDevice` is the
 * platform's answer to exactly this, and it comes with the notification the
 * situation deserves anyway: something is being emitted on your behalf, and
 * there should be a visible way to stop it that does not involve finding the
 * app.
 *
 * ### Why the quick surfaces route through here
 *
 * A widget tap, a tile tap and an automation broadcast all need to do work that
 * can take several seconds -- reconnecting to a sleeping Flipper -- from a
 * context that is about to be destroyed. Handing the request to a service with
 * its own scope is the difference between that working and it being cancelled
 * halfway. The rules about what is allowed still live in [QuickActions]; this
 * only provides somewhere for the work to happen.
 */
@AndroidEntryPoint
class EmulationService : Service() {

    companion object {
        const val ACTION_TOGGLE = "dev.omniwallet.action.TOGGLE"
        const val ACTION_STOP = "dev.omniwallet.action.STOP"

        /** Keep the process alive for a session started inside the app. */
        const val ACTION_ATTACH = "dev.omniwallet.action.ATTACH"

        const val EXTRA_CREDENTIAL_ID = "credential_id"
        const val EXTRA_CREDENTIAL_NAME = "credential_name"

        private const val CHANNEL_ID = "emulation"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2

        fun toggleIntent(context: Context, id: CredentialId): Intent =
            Intent(context, EmulationService::class.java)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_CREDENTIAL_ID, id.value)

        fun toggleByNameIntent(context: Context, name: String): Intent =
            Intent(context, EmulationService::class.java)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_CREDENTIAL_NAME, name)

        fun stopIntent(context: Context): Intent =
            Intent(context, EmulationService::class.java).setAction(ACTION_STOP)

        /**
         * Start the service, reporting rather than crashing when the platform
         * says no.
         *
         * Android 12+ forbids starting a foreground service from the
         * background. A widget or tile tap is exempt for a few seconds; a
         * broadcast from another app generally is not. That restriction cannot
         * be worked around, so it is reported honestly instead of being
         * disguised as some other failure.
         */
        fun start(context: Context, intent: Intent): Boolean = try {
            context.startForegroundService(intent)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+, which is an
            // IllegalStateException; caught by supertype so this still compiles
            // and behaves on older releases.
            false
        }
    }

    @Inject
    lateinit var emulation: EmulationController

    @Inject
    lateinit var quickActions: QuickActions

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var watcher: Job? = null

    /**
     * Requests still running.
     *
     * The term that was missing. Without it the service treated "nothing is
     * emulating yet" as "nothing to do" and shut down before the request it
     * was started for had got as far as reading a setting.
     */
    private val inFlight = MutableStateFlow(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promoted immediately, before any work. The platform kills a service
        // that does not call startForeground within a few seconds, and the work
        // below can legitimately take longer than that while a Flipper wakes up.
        promote(emulation.nowEmulating.value?.credential?.displayName)

        when (intent?.action) {
            ACTION_TOGGLE -> {
                val id = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
                val name = intent.getStringExtra(EXTRA_CREDENTIAL_NAME)
                when {
                    id != null -> submit { quickActions.toggle(CredentialId(id)) }
                    name != null -> submit { quickActions.toggleByName(name) }
                    else -> stopIfIdle()
                }
            }

            ACTION_STOP -> submit { quickActions.stop() }

            // Nothing to do: attaching is the request, and the session watcher
            // started below is what honours it.
            ACTION_ATTACH -> Unit

            else -> stopIfIdle()
        }

        // Started *after* the branch above, never before it. `submit`
        // increments the counter synchronously on this thread, so by the time
        // the watcher's first reading happens the request is already counted.
        // Launching the watcher first leaves a window in which it sees an idle
        // service and stops it -- a narrower version of the very bug this
        // counter was added to fix.
        observeSession()

        // Not sticky: a restart with a null intent would have this service
        // running with no emulation to hold up, showing a notification about
        // nothing.
        return START_NOT_STICKY
    }

    /**
     * Run a request, and count it while it runs.
     *
     * The counting is the fix. Incrementing *before* the coroutine starts
     * matters: doing it inside the launch would leave a window where the
     * service looks idle, and that window is all the old bug needed.
     */
    private fun submit(request: suspend () -> QuickActions.Outcome) {
        inFlight.update { it + 1 }
        scope.launch {
            try {
                announce(request())
            } finally {
                inFlight.update { it - 1 }
                stopIfIdle()
            }
        }
    }

    /**
     * Follow the session and shut down once there is genuinely nothing to do.
     *
     * The service exists to hold work up; with no session and no request
     * running it is an unexplained notification and a process the system
     * cannot reclaim.
     *
     * This used to watch the session alone, and a state flow hands its current
     * value to every new collector -- so a service started to *begin* an
     * emulation was told "nothing is emulating" immediately and stopped, taking
     * the request with it when `onDestroy` cancelled the scope. That is why a
     * widget tap did nothing whatsoever. [ServiceLifecyclePolicy] now owns the
     * decision and is tested.
     */
    private fun observeSession() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            combine(emulation.nowEmulating, inFlight) { session, busy -> session to busy }
                .collect { (session, busy) ->
                    when {
                        session != null -> promote(session.credential.displayName)
                        ServiceLifecyclePolicy.shouldStop(false, busy) -> stopSelf()
                        else -> Unit // work in progress; hold the service up
                    }
                }
        }
    }

    private fun stopIfIdle() {
        if (
            ServiceLifecyclePolicy.shouldStop(
                sessionActive = emulation.nowEmulating.value != null,
                requestsInFlight = inFlight.value,
            )
        ) {
            stopSelf()
        }
    }

    /**
     * Say what happened, then stand down if nothing came of it.
     *
     * A refusal has to be visible. The request came from a home screen or
     * another app entirely, so there is no screen waiting to show an error --
     * without this, a widget tap that was refused looks identical to one that
     * silently failed.
     */
    private fun announce(outcome: QuickActions.Outcome) {
        when (outcome) {
            is QuickActions.Outcome.Started -> Unit // the ongoing notification says it
            QuickActions.Outcome.NeedsUnlock -> {
                notify(outcome.message)
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> notify(outcome.message)
        }
        // Stopping is left to submit's finally, which runs after this request
        // has been counted out. Deciding it here would read the count before
        // it had been decremented, and never stop.
    }

    /**
     * A one-off notification for something the user needs to know.
     *
     * Separate id from the ongoing one, so a refusal does not replace or
     * cancel the notification holding up a running session.
     */
    private fun notify(message: String) {
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alert = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OmniWallet")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).apply {
            if (areNotificationsEnabled()) notify(ALERT_NOTIFICATION_ID, alert)
        }
    }

    private fun promote(runningName: String?) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(runningName),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun notification(runningName: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(runningName?.let { "Emulating $it" } ?: "OmniWallet")
            .setContentText(
                runningName?.let { "Your device is emitting this card" }
                    ?: "Working…",
            )
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            // Quiet: this appears because something is running, not because
            // anything needs attention.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Emulation in progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while your device is emitting a card, with a way to stop it."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
