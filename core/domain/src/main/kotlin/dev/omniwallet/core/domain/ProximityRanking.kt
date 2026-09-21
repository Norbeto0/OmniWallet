package dev.omniwallet.core.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which tagged credentials are near the phone right now.
 *
 * ### This is a ranking aid, nothing more
 *
 * It adds a section to the top of the wallet. It never hides a card, never
 * reorders the main list, and never starts an emulation. That restraint is the
 * feature: being at the office is a hint about which key you want, not a fact,
 * and a wallet that quietly buried the card you were reaching for because the
 * GPS disagreed would be worse than one with no location awareness at all.
 *
 * Pure, and in the domain module, for the same reason as [CredentialMerge]: the
 * development environment for this project has no GPS any more than it has a
 * Bluetooth radio, so the only honest way to know this is right is to compute
 * it from fixtures on the JVM.
 */
object ProximityRanking {

    /**
     * Mean Earth radius (IUGG). Metres.
     */
    const val EARTH_RADIUS_METRES = 6_371_008.8

    /**
     * How close counts as "here".
     *
     * Sized for a building rather than a doorway. A door key is wanted while
     * walking up to the building, not once already standing at the reader, and
     * a civilian GPS fix indoors is rarely better than this anyway.
     */
    const val DEFAULT_RADIUS_METRES = 150.0

    /**
     * Beyond this, a fix says nothing useful.
     *
     * A cell-tower-only fix can be accurate to kilometres. Widening the search
     * by that much would mark half a city as "nearby", which is noise wearing
     * the costume of a feature. Better to show no Nearby section at all.
     */
    const val MAX_ACCURACY_METRES = 500.0

    /**
     * How old a fix may be and still be believed.
     *
     * Two minutes is roughly how long it takes to walk out of range of the
     * place a fix describes.
     */
    const val MAX_FIX_AGE_MILLIS = 120_000L

    /** A credential and how far away its tagged place is. */
    data class Nearby(
        val credential: StoredCredential,
        val distanceMetres: Double,
    )

    /**
     * Great-circle distance in metres.
     *
     * Haversine on a sphere, not an ellipsoidal solution. Over the hundreds of
     * metres this is asked about, the two differ by well under a metre --
     * far inside the accuracy of the fix being compared against -- so the
     * extra machinery would buy precision that the input does not have.
     */
    fun distanceMetres(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val dLat = Math.toRadians(toLatitude - fromLatitude)
        val dLon = Math.toRadians(toLongitude - fromLongitude)
        val lat1 = Math.toRadians(fromLatitude)
        val lat2 = Math.toRadians(toLatitude)

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        // Clamped because accumulated floating-point error can push h a hair
        // above 1 for antipodal points, and asin would then return NaN.
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(h)))
    }

    fun distanceMetres(fix: Fix, place: Place): Double =
        distanceMetres(fix.latitude, fix.longitude, place.latitude, place.longitude)

    /**
     * Whether a fix is worth acting on.
     *
     * Fails closed on a negative age. A fix stamped in the future means the
     * monotonic clock and the fix disagree about reality, and the safe reading
     * of "I cannot tell how old this is" is to not trust it -- the same call
     * made in the app lock for the same reason.
     */
    fun isUsable(fix: Fix, nowElapsedRealtimeMillis: Long): Boolean {
        val age = nowElapsedRealtimeMillis - fix.atElapsedRealtimeMillis
        if (age < 0) return false
        if (age > MAX_FIX_AGE_MILLIS) return false
        // A non-finite or negative accuracy is a broken reading, not an
        // extremely good one.
        if (!fix.accuracyMetres.isFinite() || fix.accuracyMetres < 0) return false
        return fix.accuracyMetres <= MAX_ACCURACY_METRES
    }

    /**
     * Tagged credentials within reach of [fix], nearest first.
     *
     * The search radius is widened by the fix's own accuracy. Given a poor fix
     * this errs towards showing a card that turns out not to be here, rather
     * than omitting one that is -- for a section that only ever *adds*
     * suggestions, a false positive costs a glance and a false negative costs
     * the entire point of the feature.
     *
     * Hidden entries stay hidden and absent ones are dropped: a card the device
     * no longer reports cannot be used, so promoting it would be an offer the
     * app cannot honour.
     */
    fun nearby(
        credentials: List<StoredCredential>,
        fix: Fix?,
        nowElapsedRealtimeMillis: Long,
        radiusMetres: Double = DEFAULT_RADIUS_METRES,
    ): List<Nearby> {
        if (fix == null || !isUsable(fix, nowElapsedRealtimeMillis)) return emptyList()
        val reach = radiusMetres + fix.accuracyMetres

        return credentials
            .asSequence()
            .filter { !it.hidden && it.present }
            .mapNotNull { credential ->
                val place = credential.place ?: return@mapNotNull null
                val distance = distanceMetres(fix, place)
                if (distance <= reach) Nearby(credential, distance) else null
            }
            // Nearest first, then the wallet's usual tie-break so two cards
            // tagged at the same door do not swap places between frames.
            .sortedWith(
                compareBy<Nearby> { it.distanceMetres }
                    .thenByDescending { it.credential.favourite }
                    .thenBy { it.credential.displayName.lowercase() },
            )
            .toList()
    }

    /**
     * Human-readable distance. Metres up close, kilometres once that is silly.
     *
     * Formatted in the platform's default locale, so the decimal separator is
     * the one the reader expects rather than the one this file was written in.
     */
    fun formatDistance(metres: Double): String = when {
        metres < 10 -> "here"
        metres < 1_000 -> "${metres.toInt()} m away"
        else -> String.format(java.util.Locale.getDefault(), "%.1f km away", metres / 1_000)
    }
}
