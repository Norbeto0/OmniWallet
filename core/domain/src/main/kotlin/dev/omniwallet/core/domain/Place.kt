package dev.omniwallet.core.domain

/**
 * Where a credential gets used, as the user tagged it.
 *
 * Tagging is entirely manual. The app never infers a place from where a card
 * was last emulated, because a single accidental tap somewhere would then
 * permanently teach the wallet the wrong thing, and unlearning it would need a
 * UI nobody wants to build. One explicit "use my current location" is clearer
 * than a heuristic that is right most of the time.
 *
 * ### What this costs in privacy
 *
 * A coordinate attached to a door key is, quite literally, the location of a
 * door the user can open. It is held in the same encrypted database as the rest
 * of the library and leaves the phone exactly as often as everything else here
 * does, which is never. It is not rounded: rounding to a hundred metres would
 * not stop anyone identifying a building, so it would buy no real privacy while
 * making the feature worse. Honest precision in an encrypted store beats
 * decorative fuzzing.
 */
data class Place(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * A position fix from the platform.
 *
 * [atElapsedRealtimeMillis] is deliberately a *monotonic* timestamp rather than
 * wall-clock. A wall-clock reading makes a perfectly good fix look hours stale
 * (or impossibly fresh) the moment the phone corrects its clock or crosses a
 * timezone, and freshness is the whole basis for trusting a fix at all.
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    /** Radius in metres within which the platform claims the true position lies. */
    val accuracyMetres: Double,
    val atElapsedRealtimeMillis: Long,
)
