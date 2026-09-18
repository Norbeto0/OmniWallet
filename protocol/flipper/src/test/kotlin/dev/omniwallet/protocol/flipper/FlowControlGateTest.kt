package dev.omniwallet.protocol.flipper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FlowControlGateTest {

    @Test
    fun `decodes the credit value as big-endian`() {
        // REVERSE_BYTES_U32 on a little-endian MCU puts this on the wire
        // most-significant byte first.
        FlowControlGate.decodeCredit(byteArrayOf(0x00, 0x00, 0x02, 0x00)) shouldBe 512
        FlowControlGate.decodeCredit(byteArrayOf(0x00, 0x01, 0x00, 0x00)) shouldBe 65536
        FlowControlGate.decodeCredit(byteArrayOf(0x12, 0x34, 0x56, 0x78)) shouldBe 0x12345678
    }

    @Test
    fun `consumes credit as writes are acquired`() = runTest {
        val gate = FlowControlGate()
        gate.onCreditReported(1024)

        gate.acquire(200)
        gate.credit.value shouldBe 824

        gate.acquire(24)
        gate.credit.value shouldBe 800
    }

    /**
     * The single most important behaviour here. The firmware publishes the
     * *absolute* number of bytes it can accept and resets it to the full buffer
     * size once drained. A client that accumulated the notified values would
     * believe it had far more headroom than the device does, and would overrun
     * the receive buffer -- which the firmware itself warns about.
     */
    @Test
    fun `a credit report replaces the balance rather than adding to it`() = runTest {
        val gate = FlowControlGate()
        gate.onCreditReported(1024)
        gate.acquire(1000)
        gate.credit.value shouldBe 24

        // Device drained its buffer and re-advertised the full size.
        gate.onCreditReported(1024)
        gate.credit.value shouldBe 1024 // not 1048
    }

    @Test
    fun `a write blocks until the device reports more credit`() = runTest {
        val gate = FlowControlGate()
        gate.onCreditReported(100)

        var completed = false
        val writer = launch {
            gate.acquire(250)
            completed = true
        }

        runCurrent()
        completed shouldBe false // not enough credit yet

        gate.onCreditReported(400)
        runCurrent()
        completed shouldBe true
        gate.credit.value shouldBe 150

        writer.join()
    }

    /**
     * The invariant that matters: across an arbitrary interleaving of writes
     * and credit reports, the balance must never go negative -- that would mean
     * we wrote more than the device said it could take.
     */
    @Test
    fun `credit never goes negative under randomised traffic`() = runTest {
        val rng = Random(31337)
        val bufferSize = 512
        val gate = FlowControlGate()
        gate.onCreditReported(bufferSize)

        val writers = List(40) {
            launch {
                repeat(10) {
                    gate.acquire(rng.nextInt(1, 64))
                    gate.credit.value shouldBe gate.credit.value.coerceAtLeast(0)
                }
            }
        }

        // Device periodically drains and re-advertises, as the firmware does.
        repeat(80) {
            runCurrent()
            gate.onCreditReported(bufferSize)
        }

        runCurrent()
        writers.forEach { it.join() }
        (gate.credit.value >= 0) shouldBe true
    }

    /**
     * A write that can never be satisfied -- because it exceeds the device's
     * buffer, or because the device stopped reporting credit -- must surface as
     * an error rather than hanging the session forever with no diagnosis.
     */
    @Test
    fun `gives up when credit never arrives`() = runTest {
        val gate = FlowControlGate()
        gate.onCreditReported(256)
        gate.acquire(256)

        val thrown = shouldThrow<FlowControlGate.CreditStalledException> {
            gate.acquire(512, stallTimeoutMillis = 5_000)
        }
        thrown.requested shouldBe 512
        thrown.available shouldBe 0
    }

    /**
     * The complement of the above: a partial reading is only a lower bound on
     * the device's real buffer, so a write larger than anything seen so far
     * must still go through once the device reports enough.
     */
    @Test
    fun `allows a write larger than any credit seen so far`() = runTest {
        val gate = FlowControlGate()
        gate.onCreditReported(100) // device happens to be partly full

        var completed = false
        val writer = launch {
            gate.acquire(400)
            completed = true
        }
        runCurrent()
        completed shouldBe false

        gate.onCreditReported(1024) // buffer drained; true size revealed
        runCurrent()
        completed shouldBe true

        writer.join()
    }

    @Test
    fun `tracks the largest credit seen as the device buffer size`() = runTest {
        val gate = FlowControlGate()
        gate.onCreditReported(1024)
        gate.acquire(900)
        gate.onCreditReported(124)
        gate.observedBufferSize shouldBe 1024
    }

    @Test
    fun `a zero-byte write is a no-op`() = runTest {
        val gate = FlowControlGate()
        gate.acquire(0) // must not block even with no credit at all
        gate.credit.value shouldBe 0
    }
}
