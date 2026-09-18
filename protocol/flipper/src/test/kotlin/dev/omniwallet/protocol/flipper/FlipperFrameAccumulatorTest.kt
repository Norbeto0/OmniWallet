package dev.omniwallet.protocol.flipper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

class FlipperFrameAccumulatorTest {

    /** Frame a body the way the wire format requires. */
    private fun framed(body: ByteArray): ByteArray = VarInt.encode(body.size) + body

    private fun body(size: Int, seed: Int = 0) = ByteArray(size) { (it + seed).toByte() }

    @Test
    fun `yields a single whole message`() {
        val payload = body(24)
        val out = FlipperFrameAccumulator().feed(framed(payload))
        out shouldHaveSize 1
        out[0].toList() shouldBe payload.toList()
    }

    @Test
    fun `splits several messages delivered in one notification`() {
        val a = body(10, 1)
        val b = body(3, 2)
        val c = body(200, 3) // >127, so a two-byte length prefix

        val out = FlipperFrameAccumulator().feed(framed(a) + framed(b) + framed(c))

        out shouldHaveSize 3
        out[0].toList() shouldBe a.toList()
        out[1].toList() shouldBe b.toList()
        out[2].toList() shouldBe c.toList()
    }

    /**
     * The real BLE case: the firmware chunks TX at the characteristic value
     * length, so messages routinely straddle notifications -- including
     * splitting the varint length prefix itself, which is the nastiest variant.
     */
    @Test
    fun `reassembles a message split one byte at a time`() {
        val payload = body(300)
        val stream = framed(payload)
        val acc = FlipperFrameAccumulator()

        val out = buildList { stream.forEach { addAll(acc.feed(byteArrayOf(it))) } }

        out shouldHaveSize 1
        out[0].toList() shouldBe payload.toList()
        acc.pending shouldBe 0
    }

    @Test
    fun `survives randomised chunk boundaries across many messages`() {
        val rng = Random(2024)
        repeat(200) { iteration ->
            val payloads = List(rng.nextInt(1, 7)) { body(rng.nextInt(0, 400), iteration + it) }
            val stream = payloads.map { framed(it) }.reduce { a, b -> a + b }

            val acc = FlipperFrameAccumulator()
            val out = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < stream.size) {
                val size = rng.nextInt(1, 64).coerceAtMost(stream.size - offset)
                out += acc.feed(stream.copyOfRange(offset, offset + size))
                offset += size
            }

            out shouldHaveSize payloads.size
            out.zip(payloads).forEach { (got, want) -> got.toList() shouldBe want.toList() }
            acc.pending shouldBe 0
        }
    }

    @Test
    fun `holds a partial message without emitting it`() {
        val stream = framed(body(100))
        val acc = FlipperFrameAccumulator()
        acc.feed(stream.copyOfRange(0, 40)) shouldHaveSize 0
        acc.feed(stream.copyOfRange(40, stream.size)) shouldHaveSize 1
    }

    @Test
    fun `handles a zero-length message`() {
        val out = FlipperFrameAccumulator().feed(framed(ByteArray(0)))
        out shouldHaveSize 1
        out[0].size shouldBe 0
    }

    /**
     * A corrupt length prefix must fail loudly rather than buffering without
     * limit -- otherwise a desynchronised stream turns into slow memory growth
     * that only shows up on a user's phone.
     */
    @Test
    fun `rejects an implausibly large declared length`() {
        val acc = FlipperFrameAccumulator(maxMessageSize = 1024)
        shouldThrow<FlipperFrameAccumulator.MalformedStreamException> {
            acc.feed(VarInt.encode(2048) + body(10))
        }
    }

    @Test
    fun `rejects a runaway varint prefix`() {
        val acc = FlipperFrameAccumulator()
        shouldThrow<FlipperFrameAccumulator.MalformedStreamException> {
            acc.feed(ByteArray(6) { 0x80.toByte() })
        }
    }
}
