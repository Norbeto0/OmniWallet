package dev.omniwallet.app.session

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the app should re-ask for authentication.
 *
 * Pure, so the rule can actually be tested rather than reasoned about: lock
 * behaviour is easy to get subtly wrong and unpleasant when it is -- a wallet
 * that re-prompts every time you glance at a notification gets its lock turned
 * off, which protects nothing.
 */
object LockPolicy {

    /** No grace period: lock the moment the app leaves the foreground. */
    const val IMMEDIATE_MILLIS = 0L

    /**
     * Whether the app should be locked now.
     *
     * Times are expected to come from a **monotonic** clock
     * (`SystemClock.elapsedRealtime`), not the wall clock, so that changing
     * timezone or an NTP correction cannot extend the grace period. Negative
     * elapsed time is treated as a locking condition anyway: if the clock
     * cannot be trusted, a security control should fail closed rather than
     * leave the wallet open.
     *
     * @param backgroundedAtMillis when the app last left the foreground, or
     *   null if it has not since being unlocked.
     */
    fun shouldLock(
        lockEnabled: Boolean,
        backgroundedAtMillis: Long?,
        nowMillis: Long,
        graceMillis: Long,
    ): Boolean {
        if (!lockEnabled) return false
        val backgroundedAt = backgroundedAtMillis ?: return false
        val elapsed = nowMillis - backgroundedAt
        if (elapsed < 0) return true
        return elapsed >= graceMillis
    }
}

/**
 * Whether the UI is currently behind the lock.
 *
 * A singleton because the gate covers the whole app rather than one screen,
 * and because the moment of backgrounding has to be remembered across an
 * activity being recreated.
 */
@Singleton
class AppLock @Inject constructor() {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAtMillis: Long? = null

    /** Called when the lock setting is first known, at startup. */
    fun initialise(lockEnabled: Boolean) {
        _locked.value = lockEnabled
        backgroundedAtMillis = null
    }

    fun onAuthenticated() {
        _locked.value = false
        backgroundedAtMillis = null
    }

    fun onBackgrounded(nowMillis: Long = SystemClock.elapsedRealtime()) {
        if (backgroundedAtMillis == null) backgroundedAtMillis = nowMillis
    }

    fun onForegrounded(
        lockEnabled: Boolean,
        graceMillis: Long,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        if (LockPolicy.shouldLock(lockEnabled, backgroundedAtMillis, nowMillis, graceMillis)) {
            _locked.value = true
        }
        backgroundedAtMillis = null
    }

    /** Turning the setting off must not leave the user staring at a lock. */
    fun onLockSettingChanged(enabled: Boolean) {
        if (!enabled) _locked.value = false
    }
}
