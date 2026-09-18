package dev.omniwallet.protocol.chameleon

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.Test
import kotlin.random.Random

class ChameleonFrameTest {

    /**
     * The defining property of this LRC: a span summed together with its own
     * check byte is 0 mod 256. If this holds, the formula matches the vendor
     * client's `lrc_calc` whatever the input.
     */
    @Test
    fun `lrc makes the span sum to zero mod 256`() {
        val rng = Random(1234)
        repeat(500) {
            val bytes = ByteArray(rng.nextInt(1, 64)) { rng.nextInt(256).toByte() }
            val check = ChameleonFrame.lrc(bytes)
            val total = bytes.sumOf { it.toInt() and 0xFF } + check
            (total and 0xFF) shouldBe 0
        }
    }

    @Test
    fun `lrc of the SOF alone is 0xEF`() {
        // 0x100 - 0x11 = 0xEF. A fixed value worth nailing down, since every
        // frame carries it at offset 1.
        ChameleonFrame.lrc(byteArrayOf(ChameleonFrame.SOF)) shouldBe 0xEF
    }

    /**
     * Golden vector: an empty-payload GET_APP_VERSION (1000 = 0x03E8), built by
     * hand from the documented layout rather than from our own encoder, so this
     * catches an encoder that is self-consistently wrong.
     */
    @Test
    fun `encodes a known frame byte for byte`() {
        val frame = ChameleonFrame.encode(ChameleonCommand.GET_APP_VERSION)

        frame.size shouldBe 10
        frame[0] shouldBe 0x11.toByte()          // SOF
        frame[1] shouldBe 0xEF.toByte()          // LRC1 over the SOF
        frame[2] shouldBe 0x03.toByte()          // cmd hi  (1000 = 0x03E8)
        frame[3] shouldBe 0xE8.toByte()          // cmd lo
        frame[4] shouldBe 0x00.toByte()          // status hi
        frame[5] shouldBe 0x00.toByte()          // status lo
        frame[6] shouldBe 0x00.toByte()          // length hi
        frame[7] shouldBe 0x00.toByte()          // length lo

        // LRC2 covers the 8 header bytes; LRC3 covers everything before it.
        (frame[8].toInt() and 0xFF) shouldBe ChameleonFrame.lrc(frame, 0, 8)
        (frame[9].toInt() and 0xFF) shouldBe ChameleonFrame.lrc(frame, 0, 9)
    }

    @Test
    fun `command status and payload survive a round trip`() {
        val payload = ByteArray(32) { it.toByte() }
        val encoded = ChameleonFrame.encode(ChameleonCommand.MF1_WRITE_EMU_BLOCK_DATA, payload)

        val decoded = ChameleonFrameDecoder().feed(encoded)
        decoded shouldHaveSize 1
        decoded[0].command shouldBe ChameleonCommand.MF1_WRITE_EMU_BLOCK_DATA
        decoded[0].status shouldBe 0
        decoded[0].payload.toList() shouldBe payload.toList()
    }

    @Test
    fun `big-endian fields survive values that differ per byte`() {
        // 0x1234 would pass even with the bytes swapped if both halves matched,
        // so use a value where hi and lo differ.
        val encoded = ChameleonFrame.encode(command = 0x1234, status = 0x00AB)
        val decoded = ChameleonFrameDecoder().feed(encoded).single()
        decoded.command shouldBe 0x1234
        decoded.status shouldBe 0x00AB
    }
}
