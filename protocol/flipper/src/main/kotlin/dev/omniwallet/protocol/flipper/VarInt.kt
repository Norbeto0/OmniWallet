package dev.omniwallet.protocol.flipper

/**
 * Protobuf base-128 varint, used as the length prefix on every `PB_Main`.
 *
 * The wire format is `varint32(message.byteSize) + message.bytes`, matching
 * `_VarintBytes(...) + SerializeToString()` in the vendor Python client.
 */
object VarInt {

    /** Maximum bytes a 32-bit varint can occupy. */
    const val MAX_BYTES = 5

    fun encode(value: Int): ByteArray {
        require(value >= 0) { "negative length: $value" }
        var v = value
        val out = ArrayList<Byte>(MAX_BYTES)
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.add(v.toByte())
                return out.toByteArray()
            }
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
    }

    /** Result of decoding a varint from a partially-filled buffer. */
    sealed interface Decoded {
        /** Decoded [value], consuming [bytesRead] bytes. */
        data class Value(val value: Int, val bytesRead: Int) : Decoded
        /** Not enough bytes yet; try again once more have arrived. */
        data object Incomplete : Decoded
        /** More than [MAX_BYTES] continuation bytes: the stream is corrupt. */
        data object Malformed : Decoded
    }

    fun decode(buffer: ByteArray, offset: Int = 0, limit: Int = buffer.size): Decoded {
        var result = 0
        var shift = 0
        var i = offset
        while (i < limit) {
            val b = buffer[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) return Decoded.Value(result, i - offset)
            shift += 7
            if (shift >= MAX_BYTES * 7) return Decoded.Malformed
        }
        return Decoded.Incomplete
    }
}
