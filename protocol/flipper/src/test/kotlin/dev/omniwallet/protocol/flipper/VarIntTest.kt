package dev.omniwallet.protocol.flipper

import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

class VarIntTest {

    @Test
    fun `round trips across the whole positive range`() {
        val rng = Random(7)
        val values = buildList {
            // Boundaries where the encoded width changes -- the classic
            // off-by-one territory.
            addAll(listOf(0, 1, 127, 128, 129, 16383, 16384, 2097151, 2097152, 268435455, 268435456))
            addAll(listOf(Int.MAX_VALUE))
            repeat(500) { add(rng.nextInt(0, Int.MAX_VALUE)) }
        }

        values.forEach { value ->
            val encoded = VarInt.encode(value)
            when (val decoded = VarInt.decode(encoded)) {
                is VarInt.Decoded.Value -> {
                    decoded.value shouldBe value
                    decoded.bytesRead shouldBe encoded.size
                }
                else -> error("failed to decode $value from ${encoded.toList()}")
            }
        }
    }

    @Test
    fun `encoded width matches the protobuf spec at boundaries`() {
        VarInt.encode(0).size shouldBe 1
        VarInt.encode(127).size shouldBe 1
        VarInt.encode(128).size shouldBe 2
        VarInt.encode(16383).size shouldBe 2
        VarInt.encode(16384).size shouldBe 3
        VarInt.encode(Int.MAX_VALUE).size shouldBe 5
    }

    @Test
    fun `reports incomplete when continuation bits run past the buffer`() {
        val encoded = VarInt.encode(300) // two bytes
        VarInt.decode(encoded, limit = 1) shouldBe VarInt.Decoded.Incomplete
        VarInt.decode(ByteArray(0)) shouldBe VarInt.Decoded.Incomplete
    }

    @Test
    fun `reports malformed when the continuation never terminates`() {
        // Six continuation bytes: more than a 32-bit varint can ever need.
        val runaway = ByteArray(6) { 0x80.toByte() }
        VarInt.decode(runaway) shouldBe VarInt.Decoded.Malformed
    }

    @Test
    fun `decodes at an offset without disturbing surrounding bytes`() {
        val payload = byteArrayOf(0x11, 0x22) + VarInt.encode(300) + byteArrayOf(0x33)
        val decoded = VarInt.decode(payload, offset = 2) as VarInt.Decoded.Value
        decoded.value shouldBe 300
        decoded.bytesRead shouldBe 2
    }
}
