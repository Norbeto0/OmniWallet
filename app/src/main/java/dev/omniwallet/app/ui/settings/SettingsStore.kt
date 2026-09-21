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

/**
 * A device the user has connected to before.
 *
 * Kept so a second Flipper can be switched to without a rescan. Names are the
 * advertised ones, which Momentum lets the user change, so the address is the
 * identity and the name is only ever a label.
 */
data class KnownDevice(val address: String, val name: String)

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
    val onboardingComplete: Boolean = false,
    /** Most recently connected first. */
    val knownDevices: List<KnownDevice> = emptyList(),
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
    private val onboardingKey = booleanPreferencesKey("onboarding_complete")
    private val knownDevicesKey = stringPreferencesKey("known_devices")

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
            onboardingComplete = prefs[onboardingKey] ?: false,
            knownDevices = decodeKnownDevices(prefs[knownDevicesKey]),
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

    /**
     * Record a successful connection, and move it to the front of the list.
     *
     * Deduplicated on address rather than name: a renamed Flipper is the same
     * Flipper, and two entries for one device would be a switcher that lies
     * about how many devices you own.
     */
    suspend fun rememberDevice(address: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[lastAddressKey] = address
            prefs[lastNameKey] = name

            val existing = decodeKnownDevices(prefs[knownDevicesKey])
                .filterNot { it.address.equals(address, ignoreCase = true) }
            prefs[knownDevicesKey] =
                encodeKnownDevices(listOf(KnownDevice(address, name)) + existing)
        }
    }

    /** Drop a device from the switcher entirely. */
    suspend fun removeKnownDevice(address: String) {
        context.dataStore.edit { prefs ->
            prefs[knownDevicesKey] = encodeKnownDevices(
                decodeKnownDevices(prefs[knownDevicesKey])
                    .filterNot { it.address.equals(address, ignoreCase = true) },
            )
            if (prefs[lastAddressKey].equals(address, ignoreCase = true)) {
                prefs.remove(lastAddressKey)
                prefs.remove(lastNameKey)
            }
        }
    }

    suspend fun setNearbyRanking(enabled: Boolean) {
        context.dataStore.edit { it[nearbyKey] = enabled }
    }

    suspend fun setAutomationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[automationKey] = enabled }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[onboardingKey] = complete }
    }

    /**
     * Stop auto-connecting, without forgetting the device exists.
     *
     * Called when the user disconnects on purpose. The entry stays in
     * [AppSettings.knownDevices] so it can be reconnected with one tap --
     * respecting the disconnect does not mean pretending the device was never
     * there.
     */
    suspend fun forgetDevice() {
        context.dataStore.edit {
            it.remove(lastAddressKey)
            it.remove(lastNameKey)
        }
    }

    companion object {
        /**
         * How many devices the switcher remembers.
         *
         * Small deliberately: this is for someone with a Flipper and maybe a
         * second one, not a fleet. A list that grows without bound turns into
         * a graveyard of devices borrowed once.
         */
        const val MAX_KNOWN_DEVICES = 5

        private const val RECORD = "\n"
        private const val FIELD = "\t"

        /**
         * Preferences DataStore has no list type, so this is a flat string.
         *
         * A separator a name could contain would corrupt the whole list rather
         * than one entry, so tab and newline are stripped from names on the
         * way in. Bluetooth names containing either are pathological, and
         * losing the whitespace beats losing the list.
         */
        internal fun encodeKnownDevices(devices: List<KnownDevice>): String =
            devices.take(MAX_KNOWN_DEVICES).joinToString(RECORD) { device ->
                val name = device.name.replace(RECORD, " ").replace(FIELD, " ")
                "${device.address}$FIELD$name"
            }

        /**
         * Which device an explicit request should connect to.
         *
         * The auto-connect target if there is one, else the most recently used
         * known device. That fallback is the point: tapping Disconnect clears
         * the auto-connect target by design, and without this a widget or tile
         * tap could never connect again after one deliberate disconnect.
         *
         * An explicit tap is a statement of intent, so it reaches further than
         * the automatic path is allowed to.
         */
        fun explicitConnectTarget(settings: AppSettings): KnownDevice? {
            val address = settings.lastDeviceAddress
            if (address != null) {
                return KnownDevice(address, settings.lastDeviceName ?: address)
            }
            return settings.knownDevices.firstOrNull()
        }

        internal fun decodeKnownDevices(raw: String?): List<KnownDevice> =
            raw?.split(RECORD)
                ?.mapNotNull { line ->
                    // Anything unparseable is dropped rather than guessed at.
                    // A half-read entry would render as a nameless device the
                    // user cannot identify or connect to.
                    val parts = line.split(FIELD)
                    if (parts.size != 2 || parts[0].isBlank()) {
                        null
                    } else {
                        KnownDevice(parts[0], parts[1].ifBlank { parts[0] })
                    }
                }
                ?.take(MAX_KNOWN_DEVICES)
                .orEmpty()
    }
}
