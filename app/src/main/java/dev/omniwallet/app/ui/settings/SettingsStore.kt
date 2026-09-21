package dev.omniwallet.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val appLockEnabled: Boolean = false,
    /** How long the app may sit in the background before re-locking. */
    val lockGraceMillis: Long = DEFAULT_LOCK_GRACE_MILLIS,
    val blockScreenshots: Boolean = false,
    val autoConnect: Boolean = true,
    /** Address of the device to reconnect to, or null if none is remembered. */
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val nearbyRanking: Boolean = true,
    /**
     * Whether other apps may ask OmniWallet to emulate a card.
     *
     * Off, and staying off until asked for. This is an exported trigger for
     * the user's physical-access credentials; defaulting it on would mean
     * every install ships an attack surface nobody requested.
     */
    val automationEnabled: Boolean = false,
) {
    companion object {
        /**
         * Half a minute. Long enough that glancing at a notification does not
         * re-prompt -- which is what makes people switch the lock off -- and
         * short enough that a phone left on a desk is not an open wallet.
         */
        const val DEFAULT_LOCK_GRACE_MILLIS = 30_000L
    }
}

/** Preferences. Local only, like everything else in this app. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val lockKey = booleanPreferencesKey("app_lock_enabled")
    private val graceKey = longPreferencesKey("lock_grace_millis")
    private val screenshotKey = booleanPreferencesKey("block_screenshots")
    private val autoConnectKey = booleanPreferencesKey("auto_connect")
    private val lastAddressKey = stringPreferencesKey("last_device_address")
    private val lastNameKey = stringPreferencesKey("last_device_name")
    private val nearbyKey = booleanPreferencesKey("nearby_ranking")
    private val automationKey = booleanPreferencesKey("automation_enabled")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            // Off by default: Flipper orange is the app's identity, and an app
            // that turns blue on a blue wallpaper stops looking like itself.
            dynamicColor = prefs[dynamicKey] ?: false,
            appLockEnabled = prefs[lockKey] ?: false,
            lockGraceMillis = prefs[graceKey] ?: AppSettings.DEFAULT_LOCK_GRACE_MILLIS,
            blockScreenshots = prefs[screenshotKey] ?: false,
            // On by default: the app is useless without a device, so making
            // people reconnect by hand every launch is friction for its own
            // sake.
            autoConnect = prefs[autoConnectKey] ?: true,
            lastDeviceAddress = prefs[lastAddressKey],
            lastDeviceName = prefs[lastNameKey],
            // On by default, but inert: nothing reads a location until the
            // user has tagged at least one card, so this switch only matters
            // to someone who has already opted in by using the feature.
            nearbyRanking = prefs[nearbyKey] ?: true,
            automationEnabled = prefs[automationKey] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[dynamicKey] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[lockKey] = enabled }
    }

    suspend fun setLockGraceMillis(millis: Long) {
        context.dataStore.edit { it[graceKey] = millis }
    }

    suspend fun setBlockScreenshots(enabled: Boolean) {
        context.dataStore.edit { it[screenshotKey] = enabled }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { it[autoConnectKey] = enabled }
    }

    suspend fun rememberDevice(address: String, name: String) {
        context.dataStore.edit {
            it[lastAddressKey] = address
            it[lastNameKey] = name
        }
    }

    suspend fun setNearbyRanking(enabled: Boolean) {
        context.dataStore.edit { it[nearbyKey] = enabled }
    }

    suspend fun setAutomationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[automationKey] = enabled }
    }

    suspend fun forgetDevice() {
        context.dataStore.edit {
            it.remove(lastAddressKey)
            it.remove(lastNameKey)
        }
    }
}
