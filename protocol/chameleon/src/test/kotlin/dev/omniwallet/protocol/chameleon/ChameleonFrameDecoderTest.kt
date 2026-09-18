package dev.omniwallet.protocol.chameleon

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

class ChameleonFrameDecoderTest {

    private fun frame(cmd: Int, payloadSize: Int, seed: Int = 0): ByteArray =
        ChameleonFrame.encode(cmd, ByteArray(payloadSize) { (it + seed).toByte() })

    /**
     * The case that actually happens on BLE: notifications arrive in sizes that
     * have nothing to do with frame boundaries. Feeding one byte at a time is
     * the harshest version of that.
     */
    @Test
    fun `reassembles a frame delivered one byte at a time`() {
        val encoded = frame(ChameleonCommand.GET_SLOT_INFO, 40)
        val decoder = ChameleonFrameDecoder()

        val out = buildList {
            encoded.forEach { addAll(decoder.feed(byteArrayOf(it))) }
        }

        out shouldHaveSize 1
        out[0].command shouldBe ChameleonCommand.GET_SLOT_INFO
        decoder.pending shouldBe 0
    }

    @Test
    fun `splits several frames arriving in one chunk`() {
        val a = frame(ChameleonCommand.GET_APP_VERSION, 4, seed = 1)
        val b = frame(ChameleonCommand.GET_ACTIVE_SLOT, 1, seed = 2)
        val c = frame(ChameleonCommand.GET_DEVICE_MODEL, 16, seed = 3)

        val out = ChameleonFrameDecoder().feed(a + b + c)

        out shouldHaveSize 3
        out.map { it.command } shouldBe listOf(
            ChameleonCommand.GET_APP_VERSION,
            ChameleonCommand.GET_ACTIVE_SLOT,
            ChameleonCommand.GET_DEVICE_MODEL,
        )
    }

    /**
     * Randomised chunking across many frames. This is the test most likely to
     * catch an off-by-one in the buffer arithmetic, which is exactly the class
     * of bug that cannot be found without hardware otherwise.
     */
    @Test
    fun `survives randomised chunk boundaries`() {
        val rng = Random(99)
        repeat(200) { iteration ->
            val frames = List(rng.nextInt(1, 6)) {
                frame(1000 + it, rng.nextInt(0, 80), seed = iteration)
            }
            val stream = frames.reduce { acc, f -> acc + f }

            val decoder = ChameleonFrameDecoder()
            val out = mutableListOf<ChameleonResponse>()
            var offset = 0
            while (offset < stream.size) {
                val size = rng.nextInt(1, 25).coerceAtMost(stream.size - offset)
                out += decoder.feed(stream.copyOfRange(offset, offset + size))
                offset += size
            }

            out shouldHaveSize frames.size
            decoder.pending shouldBe 0
            decoder.drainErrors() shouldHaveSize 0
        }
    }

    @Test
    fun `rejects a frame whose payload LRC is corrupt`() {
        val encoded = frame(ChameleonCommand.GET_SLOT_INFO, 8)
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()

        val decoder = ChameleonFrameDecoder()
        decoder.feed(encoded) shouldHaveSize 0
        decoder.drainErrors() shouldContain ChameleonDecodeError.BadPayloadLrc
    }

    @Test
    fun `rejects a frame whose header LRC is corrupt`() {
        val encoded = frame(ChameleonCommand.GET_SLOT_INFO, 8)
        encoded[8] = (encoded[8] + 1).toByte()

        val decoder = ChameleonFrameDecoder()
        decoder.feed(encoded) shouldHaveSize 0
        decoder.drainErrors() shouldContain ChameleonDecodeError.BadHeaderLrc
    }

    /**
     * A corrupt byte must not wedge the stream permanently -- the decoder has
     * to resynchronise and still deliver the next good frame.
     */
    @Test
    fun `resynchronises after leading garbage`() {
        val good = frame(ChameleonCommand.GET_APP_VERSION, 4)
        val decoder = ChameleonFrameDecoder()

        val out = decoder.feed(byteArrayOf(0x00, 0x42, 0x7F) + good)

        out shouldHaveSize 1
        out[0].command shouldBe ChameleonCommand.GET_APP_VERSION
        decoder.drainErrors() shouldContain ChameleonDecodeError.BadSof
    }

    @Test
    fun `recovers the following frame after a corrupt one`() {
        val bad = frame(ChameleonCommand.GET_SLOT_INFO, 8).also {
            it[it.size - 1] = (it[it.size - 1] + 1).toByte()
        }
        val good = frame(ChameleonCommand.GET_ACTIVE_SLOT, 2)

        val out = ChameleonFrameDecoder().feed(bad + good)

        out shouldHaveSize 1
        out[0].command shouldBe ChameleonCommand.GET_ACTIVE_SLOT
    }

    @Test
    fun `holds an incomplete frame without emitting or erroring`() {
        val encoded = frame(ChameleonCommand.GET_SLOT_INFO, 32)
        val decoder = ChameleonFrameDecoder()

        decoder.feed(encoded.copyOfRange(0, 20)) shouldHaveSize 0
        decoder.drainErrors() shouldHaveSize 0
        decoder.pending shouldBe 20

        decoder.feed(encoded.copyOfRange(20, encoded.size)) shouldHaveSize 1
    }
}
