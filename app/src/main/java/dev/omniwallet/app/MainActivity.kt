package dev.omniwallet.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.omniwallet.app.session.AppLock
import dev.omniwallet.app.session.AutoConnector
import dev.omniwallet.app.ui.OmniWalletApp
import dev.omniwallet.app.ui.lock.BiometricAuthenticator
import dev.omniwallet.app.ui.lock.LockScreen
import dev.omniwallet.app.ui.onboarding.OnboardingScreen
import dev.omniwallet.app.ui.settings.AppSettings
import dev.omniwallet.app.ui.settings.SettingsStore
import dev.omniwallet.app.ui.theme.OmniWalletTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A [FragmentActivity] because `BiometricPrompt` requires one.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var autoConnector: AutoConnector

    private var settings: AppSettings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            // Start locked if the setting says so, before anything is drawn.
            appLock.initialise(settingsStore.settings.first().appLockEnabled)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsStore.settings.collect { current ->
                    settings = current
                    applyScreenshotPolicy(current.blockScreenshots)
                }
            }
        }

        setContent {
            val current by settingsStore.settings.collectAsState(initial = AppSettings())
            val locked by appLock.locked.collectAsState()
            var lockError by remember { mutableStateOf<String?>(null) }

            // Tracked here rather than read straight from settings so that
            // finishing onboarding takes effect immediately, without waiting
            // for the DataStore write to come back round.
            var onboarded by remember { mutableStateOf<Boolean?>(null) }
            val showOnboarding = onboarded?.not() ?: !current.onboardingComplete

            OmniWalletTheme(
                themeMode = current.themeMode,
                dynamicColor = current.dynamicColor,
            ) {
                when {
                    locked -> {
                        // Prompt as soon as the lock appears, so the common
                        // case is a single glance rather than a tap then a
                        // glance.
                        LaunchedEffect(Unit) { promptForUnlock { lockError = it } }
                        LockScreen(
                            onUnlock = { promptForUnlock { lockError = it } },
                            error = lockError,
                        )
                    }

                    showOnboarding -> OnboardingScreen(onFinished = { onboarded = true })

                    // Straight to the Device tab: the app does nothing useful
                    // without hardware, so the first thing after onboarding
                    // should be the screen that finds it.
                    else -> OmniWalletApp(startOnDevice = onboarded == true)
                }
            }
        }
    }

    private fun promptForUnlock(onError: (String?) -> Unit) {
        BiometricAuthenticator.authenticate(
            activity = this,
            onSuccess = {
                onError(null)
                appLock.onAuthenticated()
            },
            onError = onError,
        )
    }

    /**
     * `FLAG_SECURE` blanks the app in the task switcher and blocks screen
     * recording. Off by default: it is genuinely useful for a credential
     * manager, and it also breaks legitimate screenshots, so it is the user's
     * call rather than ours.
     */
    private fun applyScreenshotPolicy(block: Boolean) {
        if (block) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Record when we left rather than locking outright, so the grace period
        // can decide. onStop rather than onPause: onPause also fires for a
        // permission dialog or the biometric prompt itself, which would lock
        // the app in the middle of unlocking it.
        appLock.onBackgrounded()
    }

    override fun onStart() {
        super.onStart()
        appLock.onForegrounded(
            lockEnabled = settings.appLockEnabled,
            graceMillis = settings.lockGraceMillis,
        )
        // Reconnect on every entry to the foreground, not only cold start: the
        // common case is coming back to a Flipper that has since woken up.
        autoConnector.tryReconnect()
    }
}
