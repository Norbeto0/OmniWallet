package dev.omniwallet.app.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Preferences DataStore has no list type, so the known-device list is a flat
 * string. That makes the separator a correctness concern rather than a detail:
 * a name containing one would corrupt the whole list, not one entry, and the
 * user would lose every device they had paired.
 */
class KnownDeviceCodecTest {

    private fun roundTrip(devices: List<KnownDevice>) =
        SettingsStore.decodeKnownDevices(SettingsStore.encodeKnownDevices(devices))

    @Test
    fun `a list survives a round trip in order`() {
        val devices = listOf(
            KnownDevice("80:E1:26:00:11:22", "Flipper Qidoso"),
            KnownDevice("80:E1:26:00:33:44", "Spare"),
        )
        roundTrip(devices) shouldBe devices
    }

    @Test
    fun `an empty list round trips to empty`() {
        roundTrip(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `nothing stored reads as nothing`() {
        SettingsStore.decodeKnownDevices(null) shouldBe emptyList()
        SettingsStore.decodeKnownDevices("") shouldBe emptyList()
    }

    @Test
    fun `a name containing a separator cannot corrupt the list`() {
        // The failure this test exists for: one pathological name taking every
        // other device with it.
        val devices = listOf(
            KnownDevice("AA:BB", "Line\nbreak\tand tab"),
            KnownDevice("CC:DD", "Ordinary"),
        )
        val decoded = roundTrip(devices)

        decoded.size shouldBe 2
        decoded[0].address shouldBe "AA:BB"
        decoded[0].name shouldBe "Line break and tab"
        decoded[1] shouldBe KnownDevice("CC:DD", "Ordinary")
    }

    @Test
    fun `the list is capped`() {
        val many = (1..12).map { KnownDevice("address-$it", "Device $it") }
        val decoded = roundTrip(many)

        decoded.size shouldBe SettingsStore.MAX_KNOWN_DEVICES
        // The cap keeps the front, which is the most recently connected.
        decoded.first() shouldBe KnownDevice("address-1", "Device 1")
    }

    @Test
    fun `a malformed record is dropped rather than guessed at`() {
        // A half-read entry would render as a device the user cannot identify
        // and cannot connect to, which is worse than it not being there.
        val decoded = SettingsStore.decodeKnownDevices("AA:BB\tGood\nrubbish\n\tNo address")
        decoded shouldBe listOf(KnownDevice("AA:BB", "Good"))
    }

    @Test
    fun `a nameless device falls back to its address`() {
        SettingsStore.decodeKnownDevices("AA:BB\t") shouldBe listOf(KnownDevice("AA:BB", "AA:BB"))
    }
}

/**
 * Which device an explicit tap reaches for.
 *
 * The bug this defends: tapping Disconnect clears the auto-connect target by
 * design, and the quick surfaces used to go through the automatic path only —
 * so one deliberate disconnect permanently broke every widget and tile tap.
 */
class ExplicitConnectTargetTest {

    private val known = listOf(
        KnownDevice("AA:11", "Flipper Qidoso"),
        KnownDevice("BB:22", "Spare"),
    )

    @Test
    fun `the auto-connect target wins when there is one`() {
        val settings = AppSettings(
            lastDeviceAddress = "BB:22",
            lastDeviceName = "Spare",
            knownDevices = known,
        )
        SettingsStore.explicitConnectTarget(settings) shouldBe KnownDevice("BB:22", "Spare")
    }

    @Test
    fun `after a deliberate disconnect it falls back to the most recent known device`() {
        // lastDeviceAddress is null here because the user tapped Disconnect.
        val settings = AppSettings(knownDevices = known)
        SettingsStore.explicitConnectTarget(settings) shouldBe KnownDevice("AA:11", "Flipper Qidoso")
    }

    @Test
    fun `a device with no remembered name falls back to its address`() {
        val settings = AppSettings(lastDeviceAddress = "CC:33", lastDeviceName = null)
        SettingsStore.explicitConnectTarget(settings) shouldBe KnownDevice("CC:33", "CC:33")
    }

    @Test
    fun `nothing paired ever yields nothing`() {
        // Distinct from "could not reach it", and the two need different
        // messages: one is a device that is asleep, the other is a device this
        // app has never been introduced to.
        SettingsStore.explicitConnectTarget(AppSettings()) shouldBe null
    }
}
