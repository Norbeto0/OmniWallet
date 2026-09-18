package dev.omniwallet.protocol.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.AppState
import com.flipperdevices.protobuf.app.AppStateResponse
import com.flipperdevices.protobuf.app.StartRequest
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.ListResponse
import com.flipperdevices.protobuf.system.DeviceInfoResponse
import com.flipperdevices.protobuf.system.PingResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlipperRpcSessionTest {

    /**
     * Runs the session in a child scope that is cancelled afterwards.
     *
     * The session's reader coroutine collects the transport forever by design,
     * so handing it the TestScope directly would leave runTest waiting on a job
     * that never finishes. The child Job keeps that lifetime ours to end.
     */
    private fun rpcTest(
        maxWriteSize: Int = 64,
        credit: Int = 4096,
        body: suspend TestScope.(FlipperRpcSession, FakeSerialTransport) -> Unit,
    ) = runTest {
        val transport = FakeSerialTransport(maxWriteSize)
        val sessionScope = CoroutineScope(coroutineContext + Job())
        val session = FlipperRpcSession(transport, sessionScope)
        session.start()
        runCurrent()
        session.flowControl.onCreditReported(credit)
        try {
            body(session, transport)
        } finally {
            session.close()
            sessionScope.cancel()
        }
    }

    @Test
    fun `assigns an incrementing command id and matches the reply to it`() = rpcTest(maxWriteSize = 64) { session, transport ->

        val call = async { session.request(Main(system_ping_request = null)) }
        runCurrent()

        val sent = transport.decodeWrittenRequests().single()
        sent.command_id shouldBe 1

        transport.deliver(Main(command_id = 1, system_ping_response = PingResponse()))
        runCurrent()
        call.await().command_id shouldBe 1
    }

    /**
     * Replies can arrive out of order. If the session assumed the next message
     * answered the most recent request, this is where it would break.
     */
    @Test
    fun `routes interleaved replies to the right caller`() = rpcTest(maxWriteSize = 64) { session, transport ->

        val first = async { session.request(Main(system_ping_request = null)) }
        runCurrent()
        val second = async { session.request(Main(system_ping_request = null)) }
        runCurrent()

        val ids = transport.decodeWrittenRequests().map { it.command_id }
        ids shouldBe listOf(1, 2)

        // Answer the second request first.
        transport.deliver(
            Main(command_id = 2, system_ping_response = PingResponse(data_ = byteArrayOf(2).toByteString())),
        )
        runCurrent()
        transport.deliver(
            Main(command_id = 1, system_ping_response = PingResponse(data_ = byteArrayOf(1).toByteString())),
        )
        runCurrent()

        first.await().system_ping_response?.data_?.toByteArray()?.single() shouldBe 1.toByte()
        second.await().system_ping_response?.data_?.toByteArray()?.single() shouldBe 2.toByte()
    }

    /**
     * device_info streams one key/value pair per message, chained by has_next.
     * Stopping at the first reply would silently return a single field.
     */
    @Test
    fun `assembles a multi-part reply until has_next clears`() = rpcTest(maxWriteSize = 64) { session, transport ->
        val client = FlipperRpcClient(session)

        val call = async { client.deviceInfo() }
        runCurrent()

        transport.deliver(
            Main(
                command_id = 1,
                has_next = true,
                system_device_info_response = DeviceInfoResponse("firmware_origin", "momentum"),
            ),
        )
        runCurrent()
        transport.deliver(
            Main(
                command_id = 1,
                has_next = true,
                system_device_info_response = DeviceInfoResponse("hardware_name", "Unyana"),
            ),
        )
        runCurrent()
        transport.deliver(
            Main(
                command_id = 1,
                has_next = false,
                system_device_info_response = DeviceInfoResponse("protobuf_version_major", "0"),
            ),
        )
        runCurrent()

        call.await() shouldBe mapOf(
            "firmware_origin" to "momentum",
            "hardware_name" to "Unyana",
            "protobuf_version_major" to "0",
        )
    }

    @Test
    fun `collects files across chunked storage_list replies`() = rpcTest(maxWriteSize = 64) { session, transport ->
        val client = FlipperRpcClient(session)

        val call = async { client.listDirectory("/ext/nfc") }
        runCurrent()

        transport.deliver(
            Main(
                command_id = 1,
                has_next = true,
                storage_list_response = ListResponse(
                    file_ = listOf(File(name = "office.nfc", size = 512)),
                ),
            ),
        )
        runCurrent()
        transport.deliver(
            Main(
                command_id = 1,
                storage_list_response = ListResponse(
                    file_ = listOf(
                        File(name = "gym.nfc", size = 256),
                        File(name = "subdir", type = File.FileType.DIR),
                    ),
                ),
            ),
        )
        runCurrent()

        val files = call.await()
        files shouldHaveSize 3
        files.map { it.name } shouldBe listOf("office.nfc", "gym.nfc", "subdir")
        files.last().isDirectory shouldBe true
    }

    @Test
    fun `surfaces a device error status as an exception`() = rpcTest(maxWriteSize = 64) { session, transport ->

        val call = async {
            shouldThrow<FlipperRpcSession.RpcException> {
                session.request(Main(app_start_request = StartRequest("NFC", "/ext/nfc/x.nfc")))
            }
        }
        runCurrent()
        transport.deliver(
            Main(command_id = 1, command_status = CommandStatus.ERROR_APP_CANT_START),
        )
        runCurrent()

        call.await().status shouldBe CommandStatus.ERROR_APP_CANT_START
    }

    /**
     * A missing asset directory is ordinary -- a user with no iButton files has
     * no /ext/ibutton -- and must not read as a failure.
     */
    @Test
    fun `treats a missing directory as empty rather than an error`() = rpcTest(maxWriteSize = 64) { session, transport ->
        val client = FlipperRpcClient(session)

        val call = async { client.listDirectory("/ext/ibutton") }
        runCurrent()
        transport.deliver(
            Main(command_id = 1, command_status = CommandStatus.ERROR_STORAGE_NOT_EXIST),
        )
        runCurrent()

        call.await() shouldHaveSize 0
    }

    /** command_id 0 is the device talking unprompted; it must not be dropped. */
    @Test
    fun `publishes unsolicited app state as an event`() = rpcTest(maxWriteSize = 64) { session, transport ->

        val events = mutableListOf<Main>()
        val collector = async { session.events.collect { events += it } }
        runCurrent()

        transport.deliver(
            Main(command_id = 0, app_state_response = AppStateResponse(AppState.APP_STARTED)),
        )
        runCurrent()

        events shouldHaveSize 1
        events.single().app_state_response?.state shouldBe AppState.APP_STARTED
        collector.cancel()
    }

    @Test
    fun `reassembles a reply split across notification boundaries`() = rpcTest(maxWriteSize = 64) { session, transport ->

        val call = async { session.request(Main(system_ping_request = null)) }
        runCurrent()

        val payload = ByteArray(300) { it.toByte() }
        transport.deliver(
            Main(command_id = 1, system_ping_response = PingResponse(payload.toByteString())),
            chunkSize = 7, // deliberately nothing like a frame boundary
        )
        runCurrent()

        call.await().system_ping_response?.data_?.toByteArray()?.size shouldBe 300
    }

    /** Requests must be chunked to the link's MTU, not written whole. */
    @Test
    fun `splits an outbound request into mtu-sized chunks`() = rpcTest(maxWriteSize = 20) { session, transport ->

        val longPath = "/ext/subghz/" + "a".repeat(200) + ".sub"
        val call = async {
            session.request(Main(app_start_request = StartRequest("Sub-GHz", longPath)))
        }
        runCurrent()

        transport.writes.forEach { it.size shouldBe it.size.coerceAtMost(20) }
        (transport.writes.size > 1) shouldBe true
        transport.decodeWrittenRequests().single().app_start_request?.args shouldBe longPath

        transport.deliver(Main(command_id = 1))
        runCurrent()
        call.await()
    }

    /**
     * Two concurrent requests must not interleave their chunks on the wire --
     * that would corrupt both messages irrecoverably.
     */
    @Test
    fun `never interleaves chunks from concurrent requests`() = rpcTest(maxWriteSize = 16) { session, transport ->

        val a = async { session.request(Main(app_start_request = StartRequest("NFC", "a".repeat(120)))) }
        val b = async { session.request(Main(app_start_request = StartRequest("NFC", "b".repeat(120)))) }
        runCurrent()

        // If chunks interleaved, the accumulator could not recover two intact
        // messages from the stream.
        val requests = transport.decodeWrittenRequests()
        requests shouldHaveSize 2
        requests.forEach { req ->
            val args = req.app_start_request?.args.orEmpty()
            (args.all { it == 'a' } || args.all { it == 'b' }) shouldBe true
        }

        transport.deliver(Main(command_id = 1))
        transport.deliver(Main(command_id = 2))
        runCurrent()
        a.await(); b.await()
    }
}
