package dev.omniwallet.transport.ble

import kotlin.math.min
import kotlin.random.Random

/**
 * Backoff schedule for automatic reconnection.
 *
 * Exponential with full jitter. The jitter is not decoration: a Flipper that
 * goes to sleep drops every paired phone at once, and on wake they would
 * otherwise retry in lockstep and collide on the radio. Full jitter spreads
 * them out and is cheap.
 *
 * Pure logic with no Android dependency so the schedule can actually be tested
 * rather than eyeballed.
 */
class ReconnectPolicy(
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 60_000,
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val random: Random = Random.Default,
) {

    /** Whether another attempt is permitted after [attempt] failures. */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    /**
     * Delay before attempt number [attempt] (1-based).
     *
     * Full jitter: a uniform pick from `[0, cap]` where the cap doubles per
     * attempt. Picking from the whole interval rather than a narrow band around
     * the cap is what actually decorrelates retries.
     */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be 1-based, got $attempt" }
        // Cap the shift before it overflows, not after.
        val exponent = min(attempt - 1, 32)
        val cap = min(maxDelayMillis, baseDelayMillis shl exponent)
        return random.nextLong(0, cap + 1)
    }
}
