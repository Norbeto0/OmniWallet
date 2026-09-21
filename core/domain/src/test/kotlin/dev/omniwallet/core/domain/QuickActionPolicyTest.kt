package dev.omniwallet.core.domain

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * This decides whether a door opens for a broadcast from another app. Worth
 * proving from fixtures rather than reasoning about.
 */
class QuickActionPolicyTest {

    private val card = StoredCredential(
        id = CredentialId("FLIPPER_ZERO:/ext/nfc/office.nfc"),
        deviceKind = DeviceKind.FLIPPER_ZERO,
        protocol = Protocol.NFC,
        location = CredentialLocation.FlipperFile("/ext/nfc/office.nfc"),
        discoveredName = "office",
        lastSeenAtMillis = 0,
    )

    private fun conditions(
        appLock: Boolean = false,
        automation: Boolean = true,
        target: StoredCredential? = card,
        running: Boolean = false,
    ) = QuickActionPolicy.Conditions(
        appLockEnabled = appLock,
        automationEnabled = automation,
        target = target,
        alreadyRunning = running,
    )

    private fun decide(
        request: QuickActionPolicy.Request = QuickActionPolicy.Request.START,
        conditions: QuickActionPolicy.Conditions = conditions(),
    ) = QuickActionPolicy.decide(request, conditions)

    @Test
    fun `an enabled automation request on a usable card starts it`() {
        decide() shouldBe QuickActionPolicy.Decision.Start
    }

    // --- the lock ---------------------------------------------------------

    @Test
    fun `a locked vault does not open for a broadcast`() {
        decide(conditions = conditions(appLock = true)) shouldBe
            QuickActionPolicy.Decision.NeedsUnlock
    }

    @Test
    fun `a locked vault still allows a stop`() {
        // The check that must sit above the lock. A stop reveals nothing, and
        // refusing it would be the same failure as a stop button that does not
        // stop -- which this project has already fixed once.
        decide(
            request = QuickActionPolicy.Request.STOP,
            conditions = conditions(appLock = true),
        ) shouldBe QuickActionPolicy.Decision.Stop
    }

    @Test
    fun `asking for the running card stops it, even when locked`() {
        decide(conditions = conditions(appLock = true, running = true)) shouldBe
            QuickActionPolicy.Decision.Stop
    }

    @Test
    fun `asking for the running card stops rather than restarting it`() {
        // The wallet's tap-again-to-stop gesture, honoured identically from
        // outside. A surface that restarted instead would be the exact bug the
        // in-app card fixed.
        decide(conditions = conditions(running = true)) shouldBe
            QuickActionPolicy.Decision.Stop
    }

    // --- the automation switch --------------------------------------------

    @Test
    fun `automation is refused until it is switched on`() {
        decide(conditions = conditions(automation = false)).shouldBeRefusal()
    }

    @Test
    fun `automation may always stop, switched on or not`() {
        // Ending an emulation is the one thing worth allowing unconditionally:
        // a "stop everything when I leave the building" rule should work even
        // for someone who never turned automation on for starting things.
        decide(
            request = QuickActionPolicy.Request.STOP,
            conditions = conditions(automation = false),
        ) shouldBe QuickActionPolicy.Decision.Stop
    }

    // --- the card itself --------------------------------------------------

    @Test
    fun `an unknown card is refused`() {
        decide(conditions = conditions(target = null)).shouldBeRefusal()
    }

    @Test
    fun `a card the device no longer has is refused`() {
        decide(conditions = conditions(target = card.copy(present = false))).shouldBeRefusal()
    }

    @Test
    fun `a hidden card is refused`() {
        // Hiding it was a deliberate act. Acting on it for a caller that cannot
        // see what it picked would quietly undo that.
        decide(conditions = conditions(target = card.copy(hidden = true))).shouldBeRefusal()
    }

    @Test
    fun `a refusal always carries something to act on`() {
        listOf(
            conditions(target = null),
            conditions(target = card.copy(present = false)),
            conditions(target = card.copy(hidden = true)),
            conditions(automation = false),
        ).forEach { c ->
            val decision = QuickActionPolicy.decide(QuickActionPolicy.Request.START, c)
            (decision as QuickActionPolicy.Decision.Refuse).reason.isNotBlank() shouldBe true
        }
    }

    private fun QuickActionPolicy.Decision.shouldBeRefusal() {
        (this is QuickActionPolicy.Decision.Refuse) shouldBe true
    }
}
