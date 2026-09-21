package dev.omniwallet.core.domain

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * These decide whether a door opens from a surface with no authentication in
 * front of it. Worth proving from fixtures rather than reasoning about.
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
        source: QuickActionPolicy.Source = QuickActionPolicy.Source.WIDGET,
        request: QuickActionPolicy.Request = QuickActionPolicy.Request.START,
        conditions: QuickActionPolicy.Conditions = conditions(),
    ) = QuickActionPolicy.decide(source, request, conditions)

    @Test
    fun `a widget tap on a usable card starts it`() {
        decide() shouldBe QuickActionPolicy.Decision.Start
    }

    // --- the lock ---------------------------------------------------------

    @Test
    fun `a locked app never starts a card from outside`() {
        QuickActionPolicy.Source.entries.forEach { source ->
            decide(source = source, conditions = conditions(appLock = true)) shouldBe
                QuickActionPolicy.Decision.NeedsUnlock
        }
    }

    @Test
    fun `a locked app still allows a stop`() {
        // The check that must sit above the lock. A stop reveals nothing, and
        // refusing it would be the same failure as a stop button that does not
        // stop -- which this project has already fixed once.
        decide(
            request = QuickActionPolicy.Request.STOP,
            conditions = conditions(appLock = true),
        ) shouldBe QuickActionPolicy.Decision.Stop
    }

    @Test
    fun `tapping the running card stops it even when locked`() {
        decide(conditions = conditions(appLock = true, running = true)) shouldBe
            QuickActionPolicy.Decision.Stop
    }

    @Test
    fun `tapping the running card stops rather than restarting it`() {
        // The wallet's tap-again-to-stop gesture, honoured identically from
        // the widget. A surface that restarted instead would be the exact bug
        // the in-app card fixed.
        decide(conditions = conditions(running = true)) shouldBe
            QuickActionPolicy.Decision.Stop
    }

    // --- automation -------------------------------------------------------

    @Test
    fun `automation is refused until it is switched on`() {
        val decision = decide(
            source = QuickActionPolicy.Source.AUTOMATION,
            conditions = conditions(automation = false),
        )
        decision.shouldBeRefusal()
    }

    @Test
    fun `the automation switch does not gate the widget or the tile`() {
        listOf(QuickActionPolicy.Source.WIDGET, QuickActionPolicy.Source.TILE).forEach { source ->
            decide(source = source, conditions = conditions(automation = false)) shouldBe
                QuickActionPolicy.Decision.Start
        }
    }

    @Test
    fun `automation may always stop, switched on or not`() {
        // Ending an emulation is the one thing worth allowing unconditionally:
        // a "stop everything when I leave the building" rule should work even
        // for someone who never turned automation on for starting things.
        decide(
            source = QuickActionPolicy.Source.AUTOMATION,
            request = QuickActionPolicy.Request.STOP,
            conditions = conditions(automation = false),
        ) shouldBe QuickActionPolicy.Decision.Stop
    }

    @Test
    fun `automation switched on behaves like any other source`() {
        decide(
            source = QuickActionPolicy.Source.AUTOMATION,
            conditions = conditions(automation = true),
        ) shouldBe QuickActionPolicy.Decision.Start
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
        // Hiding it was a deliberate act. Acting on it from a surface where the
        // user cannot see what they picked would quietly undo that.
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
            val decision = QuickActionPolicy.decide(
                QuickActionPolicy.Source.AUTOMATION,
                QuickActionPolicy.Request.START,
                c,
            )
            (decision as QuickActionPolicy.Decision.Refuse).reason.isNotBlank() shouldBe true
        }
    }

    private fun QuickActionPolicy.Decision.shouldBeRefusal() {
        (this is QuickActionPolicy.Decision.Refuse) shouldBe true
    }
}
