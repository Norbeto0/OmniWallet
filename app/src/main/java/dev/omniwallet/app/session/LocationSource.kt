package dev.omniwallet.app.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.omniwallet.core.domain.Fix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One-shot position fixes, for the nearby ranking and nothing else.
 *
 * ### Foreground only, on purpose
 *
 * There is no background location permission, no geofence registration and no
 * location service. The app asks where it is at the moment someone is looking
 * at their wallet or tagging a card, and at no other time. A credential manager
 * that quietly tracked its owner's movements would be a surveillance tool that
 * happens to open doors, and the brief's "no account, no cloud, no telemetry"
 * is not honoured by keeping the log local.
 *
 * ### Why the platform LocationManager rather than Play Services
 *
 * The fused provider from `play-services-location` gets a fix faster and more
 * often, and would be the obvious choice in most apps. It is not used here: it
 * would add a proprietary dependency, with its own network behaviour, to an app
 * whose defining property is that nothing leaves the phone. [LocationManagerCompat]
 * gets the same job done through the platform, including the fused provider
 * itself where the OS offers one, at the cost of occasionally returning nothing.
 * Returning nothing is an acceptable outcome for a ranking hint.
 */
@Singleton
class LocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /**
         * How long to wait for a fix.
         *
         * Short, because this competes with a user who wants to tap a card. A
         * missing fix costs a suggestion; a wallet that hangs for half a minute
         * costs the app's entire reason to exist.
         */
        const val TIMEOUT_MILLIS = 6_000L

        val PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    /**
     * True once *either* precision has been granted.
     *
     * Coarse is enough. The question being asked is "which building is this",
     * and Android 12+ lets the user downgrade a fine request to coarse from the
     * permission dialog -- treating that as a refusal would punish exactly the
     * privacy-conscious choice this feature should be comfortable with.
     */
    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Ask the platform where the phone is, once.
     *
     * Returns null rather than throwing on every failure path -- no permission,
     * no provider, location switched off, nothing in time. Each of those is a
     * perfectly ordinary state for an optional hint, and none of them is worth
     * an error in front of someone trying to open a door.
     */
    suspend fun current(timeoutMillis: Long = TIMEOUT_MILLIS): Fix? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        for (provider in providers(manager)) {
            val location = withTimeoutOrNull(timeoutMillis) { awaitLocation(manager, provider) }
            if (location != null) return location.toFix()
        }
        return null
    }

    /**
     * Providers to try, best first.
     *
     * Network before GPS deliberately. Doors are attached to buildings, this
     * gets asked from inside or beside one, and a GPS cold start under a roof
     * can take minutes to produce what wifi positioning gives in a second at
     * more than good enough accuracy for "which building".
     */
    private fun providers(manager: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private suspend fun awaitLocation(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            // Without this the platform keeps working on a fix nobody is
            // waiting for any more, which is the battery cost this whole class
            // is arranged to avoid.
            continuation.invokeOnCancellation { signal.cancel() }

            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (e: SecurityException) {
                // Permission revoked between the check above and this call.
                // Caught explicitly rather than swallowed by a runCatching over
                // the whole block: an earlier bug in this project hid a real
                // SecurityException that way, and a broad catch here would also
                // hide a genuinely broken provider.
                if (continuation.isActive) continuation.resume(null)
            }
        }
}

/**
 * Convert to the domain's [Fix].
 *
 * Elapsed realtime rather than `time`: the age of a fix has to be measured on a
 * clock that cannot jump, and wall-clock time does jump -- NTP corrections,
 * timezone changes, the user setting it by hand.
 *
 * `elapsedRealtimeNanos` rather than the millisecond accessor that reads better:
 * the latter is API 33, which lint pointed out, and this app runs from 26. The
 * nanosecond one has been there since API 17.
 */
private fun Location.toFix(): Fix = Fix(
    latitude = latitude,
    longitude = longitude,
    // A location with no accuracy claim gets the worst accuracy that still
    // counts as usable, so it is believed weakly rather than blindly.
    accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_METRES,
    atElapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000,
)

private const val UNKNOWN_ACCURACY_METRES = 500.0

/** Now, on the same monotonic clock the fixes are stamped with. */
fun nowElapsedRealtime(): Long = SystemClock.elapsedRealtime()
