package dev.omniwallet.protocol.flipper

import io.kotest.matchers.shouldBe
import org.junit.Test

class FlipperBleProfileTest {

    /**
     * The exact bytes a live Momentum session put on the RPC status
     * characteristic. RPC was demonstrably working at the time, so this must
     * read as active.
     *
     * This is a regression test with teeth: an earlier build read the value as
     * a little-endian uint32 -- on the reasonable-sounding grounds that the
     * firmware declares the characteristic `sizeof(uint32_t)` -- and got
     * 0x0B6E1401, reporting a healthy session as inactive. Only byte 0 is the
     * status.
     */
    @Test
    fun `reads the status observed on real hardware as active`() {
        val observed = byteArrayOf(0x01, 0x14, 0x6E, 0x0B)
        FlipperBleProfile.RpcStatus.isActive(observed) shouldBe true
    }

    @Test
    fun `ignores the trailing bytes entirely`() {
        // Whatever follows byte 0 is unrelated memory and must not influence
        // the result either way.
        FlipperBleProfile.RpcStatus.isActive(byteArrayOf(0x01, 0x00, 0x00, 0x00)) shouldBe true
        FlipperBleProfile.RpcStatus.isActive(byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) shouldBe true
        FlipperBleProfile.RpcStatus.isActive(byteArrayOf(0x00, 0x14, 0x6E, 0x0B)) shouldBe false
    }

    @Test
    fun `treats not-active and unexpected values as inactive`() {
        FlipperBleProfile.RpcStatus.isActive(byteArrayOf(0x00)) shouldBe false
        FlipperBleProfile.RpcStatus.isActive(byteArrayOf(0x02)) shouldBe false
        FlipperBleProfile.RpcStatus.isActive(ByteArray(0)) shouldBe false
    }

    /**
     * Guards the UUIDs themselves. These are read from firmware and a typo
     * would be invisible until a device failed to connect.
     */
    @Test
    fun `serial profile uuids match the firmware`() {
        FlipperBleProfile.SERVICE.toString() shouldBe "8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000"
        FlipperBleProfile.TX_CHARACTERISTIC.toString() shouldBe "19ed82ae-ed21-4c9d-4145-228e61fe0000"
        FlipperBleProfile.RX_CHARACTERISTIC.toString() shouldBe "19ed82ae-ed21-4c9d-4145-228e62fe0000"
        FlipperBleProfile.FLOW_CONTROL_CHARACTERISTIC.toString() shouldBe "19ed82ae-ed21-4c9d-4145-228e63fe0000"
        FlipperBleProfile.RPC_STATUS_CHARACTERISTIC.toString() shouldBe "19ed82ae-ed21-4c9d-4145-228e64fe0000"
    }

    /** The value a real device reported on connect: 1024 bytes, big-endian. */
    @Test
    fun `decodes the flow control value observed on hardware`() {
        FlowControlGate.decodeCredit(byteArrayOf(0x00, 0x00, 0x04, 0x00)) shouldBe 1024
    }
}
