package dev.omniwallet.protocol.flipper

import io.kotest.matchers.shouldBe
import org.junit.Test

class FirmwareCompatibilityTest {

    /** As a real Momentum unit reported it, from the diagnostics log. */
    private val momentum = mapOf(
        "firmware_origin_fork" to "Momentum",
        "firmware_origin_git" to "https://github.com/Next-Flip/Momentum-Firmware.git",
        "firmware_version" to "mntm-008",
        "hardware_name" to "Flipper",
        "hardware_color" to "2",
        "protobuf_version_major" to "0",
        "protobuf_version_minor" to "24",
    )

    @Test
    fun `a known fork is verified`() {
        val report = FirmwareCompatibility.inspect(momentum)
        report.fork shouldBe "Momentum"
        report.version shouldBe "mntm-008"
        report.confidence shouldBe FirmwareCompatibility.Confidence.VERIFIED
        report.label shouldBe "Momentum mntm-008"
    }

    @Test
    fun `official firmware omits the fork key entirely`() {
        // The key that does not exist is the signal. There is no
        // firmware_origin key at all -- assuming one is how this was wrong
        // before hardware settled it.
        val report = FirmwareCompatibility.inspect(
            mapOf("firmware_version" to "0.105.0", "protobuf_version_major" to "0"),
        )
        report.fork shouldBe "Official"
        report.confidence shouldBe FirmwareCompatibility.Confidence.VERIFIED
    }

    @Test
    fun `an unknown fork is reported, never blocked`() {
        // The posture that matters: every custom-firmware delta is additive in
        // messages this app does not send, so refusing here would invent a
        // problem the protocol does not have.
        val report = FirmwareCompatibility.inspect(
            momentum + ("firmware_origin_fork" to "SomeoneElsesFork"),
        )
        report.confidence shouldBe FirmwareCompatibility.Confidence.UNTESTED
        report.summary.contains("should work") shouldBe true
    }

    @Test
    fun `fork matching ignores case`() {
        FirmwareCompatibility.inspect(momentum + ("firmware_origin_fork" to "UNLEASHED"))
            .confidence shouldBe FirmwareCompatibility.Confidence.VERIFIED
    }

    @Test
    fun `a newer protobuf major is the one real warning`() {
        val report = FirmwareCompatibility.inspect(momentum + ("protobuf_version_major" to "1"))
        report.confidence shouldBe FirmwareCompatibility.Confidence.INCOMPATIBLE
        report.protobufMajor shouldBe 1
    }

    @Test
    fun `a newer protobuf minor is not a warning`() {
        // Minor versions are additive by definition. Flagging them would make
        // the warning meaningless by firing on every firmware update.
        FirmwareCompatibility.inspect(momentum + ("protobuf_version_minor" to "99"))
            .confidence shouldBe FirmwareCompatibility.Confidence.VERIFIED
    }

    @Test
    fun `missing and malformed values do not throw`() {
        val report = FirmwareCompatibility.inspect(
            mapOf("protobuf_version_major" to "not a number", "firmware_origin_fork" to "  "),
        )
        report.fork shouldBe "Official"
        report.protobufMajor shouldBe null
        report.confidence shouldBe FirmwareCompatibility.Confidence.VERIFIED
    }

    @Test
    fun `an empty device info map is survivable`() {
        val report = FirmwareCompatibility.inspect(emptyMap())
        report.label shouldBe "Official"
        report.confidence shouldBe FirmwareCompatibility.Confidence.VERIFIED
    }
}
