package dev.omniwallet.transport.ble

import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    /** Zero jitter isolates the exponential schedule itself. */
    private fun fixed(value: Double) = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(from: Long, until: Long) =
            (from + (until - from - 1) * value).toLong()
    }

    @Test
    fun `delay cap doubles each attempt`() {
        val policy = ReconnectPolicy(
            baseDelayMillis = 1_000,
            maxDelayMillis = 60_000,
            random = fixed(1.0), // always take the top of the jitter window
        )
        policy.delayForAttempt(1) shouldBe 1_000
        policy.delayForAttempt(2) shouldBe 2_000
        policy.delayForAttempt(3) shouldBe 4_000
        policy.delayForAttempt(4) shouldBe 8_000
    }

    @Test
    fun `delay is clamped to the maximum`() {
        val policy = ReconnectPolicy(
            baseDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            random = fixed(1.0),
        )
        policy.delayForAttempt(10) shouldBe 30_000
        policy.delayForAttempt(50) shouldBe 30_000
    }

    /**
     * A Flipper waking from sleep drops every paired phone at once. Without
     * jitter they would all retry in lockstep and collide, so the schedule must
     * actually spread attempts out rather than merely claim to.
     */
    @Test
    fun `jitter spreads retries across the whole window`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1_000, random = Random(42))
        val samples = List(200) { policy.delayForAttempt(5) }

        val cap = 16_000L // 1000 << 4
        samples.forEach { (it in 0..cap) shouldBe true }
        (samples.distinct().size > 50) shouldBe true
        (samples.min() < cap / 4) shouldBe true
        (samples.max() > cap * 3 / 4) shouldBe true
    }

    /** A huge attempt count must not overflow the shift into a negative delay. */
    @Test
    fun `survives an absurd attempt count without overflowing`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1_000, maxDelayMillis = 60_000)
        listOf(40, 64, 1_000, Int.MAX_VALUE).forEach { attempt ->
            val delay = policy.delayForAttempt(attempt)
            (delay in 0..60_000) shouldBe true
        }
    }

    @Test
    fun `stops retrying once the attempt limit is reached`() {
        val policy = ReconnectPolicy(maxAttempts = 3)
        policy.shouldRetry(1) shouldBe true
        policy.shouldRetry(2) shouldBe true
        policy.shouldRetry(3) shouldBe false
    }
}
