package dev.omniwallet.app.session

import io.kotest.matchers.shouldBe
import org.junit.Test

class LockPolicyTest {

    @Test
    fun `never locks when the setting is off`() {
        LockPolicy.shouldLock(
            lockEnabled = false,
            backgroundedAtMillis = 0,
            nowMillis = 10_000_000,
            graceMillis = 0,
        ) shouldBe false
    }

    @Test
    fun `does not lock while the app has stayed in the foreground`() {
        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = null,
            nowMillis = 1_000,
            graceMillis = 0,
        ) shouldBe false
    }

    @Test
    fun `locks immediately with no grace period`() {
        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = 5_000,
            nowMillis = 5_000,
            graceMillis = LockPolicy.IMMEDIATE_MILLIS,
        ) shouldBe true
    }

    /**
     * The case that decides whether people leave the lock switched on. Glancing
     * at a notification and coming straight back must not re-prompt.
     */
    @Test
    fun `a brief switch away stays unlocked within the grace period`() {
        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = 10_000,
            nowMillis = 12_000,
            graceMillis = 30_000,
        ) shouldBe false
    }

    @Test
    fun `locks once the grace period has elapsed`() {
        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = 10_000,
            nowMillis = 45_000,
            graceMillis = 30_000,
        ) shouldBe true
    }

    /** Exactly at the boundary counts as elapsed, not as still inside it. */
    @Test
    fun `the boundary itself locks`() {
        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = 10_000,
            nowMillis = 40_000, // exactly backgroundedAt + grace
            graceMillis = 30_000,
        ) shouldBe true

        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = 10_000,
            nowMillis = 39_999, // one millisecond inside
            graceMillis = 30_000,
        ) shouldBe false
    }

    /**
     * Time appearing to run backwards should never buy extra unlocked minutes.
     * A security control that cannot trust its clock must fail closed.
     */
    @Test
    fun `apparent time travel locks rather than extending the grace period`() {
        LockPolicy.shouldLock(
            lockEnabled = true,
            backgroundedAtMillis = 50_000,
            nowMillis = 10_000,
            graceMillis = 600_000,
        ) shouldBe true
    }
}
