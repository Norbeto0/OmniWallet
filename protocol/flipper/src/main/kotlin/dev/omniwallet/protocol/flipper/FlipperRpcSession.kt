package dev.omniwallet.protocol.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * RPC over the Flipper's BLE serial service.
 *
 * Wire format, matching the vendor Python client:
 * `varint32(main.byteSize) + main.bytes`.
 *
 * Three behaviours are easy to get wrong and are handled explicitly here:
 *
 *  1. **Correlation.** `command_id` increments from 1 and a reply carries the
 *     id of its request. Replies can interleave, so calls are tracked in a map
 *     rather than assuming the next message answers the last request.
 *  2. **Multi-part replies.** `storage_list` and `device_info` stream: the
 *     device sends several messages sharing one `command_id`, each with
 *     `has_next = true` until the final one. `device_info` in particular sends
 *     a single key/value pair per message.
 *  3. **Unsolicited messages.** `AppStateResponse` arrives with `command_id`
 *     0 when an app starts or exits. That is how "now emulating" is observed
 *     rather than assumed, so it is published on [events] instead of discarded.
 */
class FlipperRpcSession(
    private val transport: FlipperSerialTransport,
    private val scope: CoroutineScope,
    private val defaultTimeoutMillis: Long = 10_000,
) {

    class RpcException(val status: CommandStatus, val request: String) :
        Exception("Flipper returned $status for $request")

    class SessionClosedException(cause: Throwable?) :
        Exception("RPC session closed", cause)

    private val accumulator = FlipperFrameAccumulator()
    private val nextCommandId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Channel<Main>>()

    /** Serialises writers so two requests cannot interleave their chunks. */
    private val writeMutex = Mutex()

    private val _events = MutableSharedFlow<Main>(extraBufferCapacity = 32)

    /** Device-initiated messages, chiefly app state changes. */
    val events: Flow<Main> = _events.asSharedFlow()

    val flowControl = FlowControlGate()

    private var readerJob: Job? = null
    private var creditJob: Job? = null

    fun start() {
        check(readerJob == null) { "session already started" }

        creditJob = scope.launch {
            transport.creditReports.collect { flowControl.onCreditReported(it) }
        }

        readerJob = scope.launch {
            try {
                transport.incoming.collect { chunk ->
                    accumulator.feed(chunk).forEach { dispatch(Main.ADAPTER.decode(it)) }
                }
            } catch (t: Throwable) {
                failAll(t)
                throw t
            }
        }
    }

    fun close() {
        readerJob?.cancel()
        creditJob?.cancel()
        readerJob = null
        creditJob = null
        failAll(null)
        accumulator.reset()
    }

    private fun failAll(cause: Throwable?) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.close(SessionClosedException(cause))
        }
    }

    private suspend fun dispatch(main: Main) {
        // command_id 0 is the device talking unprompted.
        if (main.command_id == 0) {
            _events.emit(main)
            return
        }
        val channel = pending[main.command_id]
        if (channel == null) {
            // A late reply to a call that already timed out. Dropping it is
            // correct, but it must not be mistaken for an event.
            return
        }
        channel.send(main)
        if (!main.has_next) {
            pending.remove(main.command_id)
            channel.close()
        }
    }

    /**
     * Send [content] and collect every message of the reply.
     *
     * [content]'s `command_id` is assigned here; any value already set is
     * overwritten.
     */
    suspend fun requestStream(
        content: Main,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): List<Main> {
        val id = nextCommandId.getAndIncrement()
        val channel = Channel<Main>(Channel.UNLIMITED)
        pending[id] = channel

        try {
            val request = content.copy(command_id = id, command_status = CommandStatus.OK)
            writeFramed(request)

            return withTimeout(timeoutMillis) {
                buildList {
                    for (message in channel) {
                        if (message.command_status != CommandStatus.OK) {
                            throw RpcException(message.command_status, describe(content))
                        }
                        add(message)
                    }
                }
            }
        } finally {
            pending.remove(id)?.close()
        }
    }

    /** Send [content] and return the single reply. */
    suspend fun request(
        content: Main,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): Main = requestStream(content, timeoutMillis).last()

    private suspend fun writeFramed(main: Main) {
        val body = main.encode()
        val frame = VarInt.encode(body.size) + body

        writeMutex.withLock {
            var offset = 0
            while (offset < frame.size) {
                val size = minOf(transport.maxWriteSize, frame.size - offset)
                // Take credit before writing, never after: the device decrements
                // its own counter on receipt and warns about overflow if we
                // exceed it.
                flowControl.acquire(size)
                transport.write(frame.copyOfRange(offset, offset + size))
                offset += size
            }
        }
    }

    /** Best-effort label for error messages: which oneof field was set. */
    private fun describe(main: Main): String = when {
        main.system_ping_request != null -> "ping"
        main.system_device_info_request != null -> "device_info"
        main.storage_list_request != null -> "storage_list(${main.storage_list_request?.path})"
        main.app_start_request != null ->
            "app_start(${main.app_start_request?.name}, ${main.app_start_request?.args})"
        main.app_exit_request != null -> "app_exit"
        else -> "request"
    }
}
