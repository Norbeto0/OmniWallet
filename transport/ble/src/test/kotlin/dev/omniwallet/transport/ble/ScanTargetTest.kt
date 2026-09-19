package dev.omniwallet.transport.ble

import dev.omniwallet.core.domain.DeviceKind
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.UUID

/**
 * Pins the advertising-filter behaviour that the first hardware run disproved.
 *
 * The original build filtered on the Flipper's 128-bit serial service UUID and
 * found nothing at all, because that service never appears in the
 * advertisement. What is advertised is a 16-bit `0x3080 | hw_color`.
 */
class ScanTargetTest {

    private val flipper = ScanTarget(
        kind = DeviceKind.FLIPPER_ZERO,
        serviceUuid = ScanTarget.shortUuid(0x3080),
        mask = ScanTarget.shortUuidMask(wildcardBits = 4),
    )

    @Test
    fun `expands a 16-bit uuid into the bluetooth base uuid`() {
        ScanTarget.shortUuid(0x3082) shouldBe
            UUID.fromString("00003082-0000-1000-8000-00805f9b34fb")
        ScanTarget.shortUuid(0x180F) shouldBe
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    }

    /**
     * The exact value observed on hardware: a white Flipper reports
     * `hardware_color = 2` and advertised `0x3082`.
     */
    @Test
    fun `matches the uuid a real Flipper advertised`() {
        val observed = UUID.fromString("00003082-0000-1000-8000-00805f9b34fb")
        flipper.matches(observed) shouldBe true
    }

    @Test
    fun `matches every hardware colour`() {
        // FuriHalVersionColor: Unknown, Black, White, Transparent.
        (0x3080..0x3083).forEach { advertised ->
            flipper.matches(ScanTarget.shortUuid(advertised)) shouldBe true
        }
        // Headroom: the mask covers the whole low nibble.
        flipper.matches(ScanTarget.shortUuid(0x308F)) shouldBe true
    }

    /**
     * The mask must not be so loose that it claims unrelated devices. 0x3090
     * is one bit outside the wildcarded nibble.
     */
    @Test
    fun `does not match neighbouring uuids outside the mask`() {
        listOf(0x3090, 0x3070, 0x3180, 0x180F).forEach { advertised ->
            flipper.matches(ScanTarget.shortUuid(advertised)) shouldBe false
        }
    }

    /**
     * Filtering on the 128-bit serial service is precisely the bug: it is real,
     * it is what you connect to, and it is never advertised.
     */
    @Test
    fun `does not match the 128-bit serial service`() {
        flipper.matches(UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")) shouldBe false
    }

    @Test
    fun `an unmasked target matches exactly and nothing else`() {
        val nus = ScanTarget(
            kind = DeviceKind.CHAMELEON_ULTRA,
            serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        )
        nus.matches(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")) shouldBe true
        nus.matches(UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")) shouldBe false
    }

    @Test
    fun `mask width controls how many bits are wildcarded`() {
        val exact = ScanTarget(DeviceKind.FLIPPER_ZERO, ScanTarget.shortUuid(0x3080), ScanTarget.shortUuidMask(0))
        exact.matches(ScanTarget.shortUuid(0x3080)) shouldBe true
        exact.matches(ScanTarget.shortUuid(0x3082)) shouldBe false

        val wide = ScanTarget(DeviceKind.FLIPPER_ZERO, ScanTarget.shortUuid(0x3000), ScanTarget.shortUuidMask(8))
        wide.matches(ScanTarget.shortUuid(0x30FF)) shouldBe true
        wide.matches(ScanTarget.shortUuid(0x3100)) shouldBe false
    }
}
