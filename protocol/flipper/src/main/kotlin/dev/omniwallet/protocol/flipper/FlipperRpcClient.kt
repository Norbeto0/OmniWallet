package dev.omniwallet.protocol.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.AppExitRequest
import com.flipperdevices.protobuf.app.LockStatusRequest
import com.flipperdevices.protobuf.app.StartRequest
import com.flipperdevices.protobuf.screen.InputKey
import com.flipperdevices.protobuf.screen.InputType
import com.flipperdevices.protobuf.screen.SendInputEventRequest
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.ListRequest
import com.flipperdevices.protobuf.system.DeviceInfoRequest
import com.flipperdevices.protobuf.system.PingRequest
import okio.ByteString.Companion.toByteString

/** A file or directory as reported by `storage_list`. */
data class FlipperFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * The RPC calls this app actually makes, on top of [FlipperRpcSession].
 *
 * Deliberately a small surface: the app pins itself to the subset of the RPC
 * API that official firmware, Unleashed and Momentum all implement identically.
 */
class FlipperRpcClient(private val session: FlipperRpcSession) {

    /** Round-trips [payload] and returns what came back. */
    suspend fun ping(payload: ByteArray = ByteArray(0)): ByteArray {
        val reply = session.request(
            Main(system_ping_request = PingRequest(data_ = payload.toByteString())),
        )
        return reply.system_ping_response?.data_?.toByteArray() ?: ByteArray(0)
    }

    /**
     * Device info as a key/value map.
     *
     * The device answers with one key/value pair *per message*, chained with
     * `has_next`, so this is a streamed call rather than a single reply.
     */
    suspend fun deviceInfo(): Map<String, String> =
        session.requestStream(Main(system_device_info_request = DeviceInfoRequest()))
            .mapNotNull { it.system_device_info_response }
            .associate { it.key to it.value_ }

    /**
     * List [path]. Also a streamed call: the device chunks large directories
     * across several `has_next` messages.
     *
     * A missing directory is normal -- a user with no iButton files has no
     * `/ext/ibutton` -- so that case returns empty rather than throwing.
     */
    suspend fun listDirectory(path: String): List<FlipperFileEntry> {
        val replies = try {
            session.requestStream(Main(storage_list_request = ListRequest(path = path)))
        } catch (e: FlipperRpcSession.RpcException) {
            if (e.status == CommandStatus.ERROR_STORAGE_NOT_EXIST) return emptyList()
            throw e
        }

        return replies
            .mapNotNull { it.storage_list_response }
            .flatMap { it.file_ }
            .map { f ->
                FlipperFileEntry(
                    name = f.name,
                    isDirectory = f.type == File.FileType.DIR,
                    sizeBytes = f.size.toLong() and 0xFFFFFFFFL,
                )
            }
    }

    /**
     * Whether an application currently holds the loader lock.
     *
     * The handler returns `loader_is_locked(loader)` (`rpc_app.c`), which is
     * exactly the condition that makes [startApp] fail with
     * `ERROR_APP_SYSTEM_LOCKED`. That makes it a real termination condition for
     * [exitRunningApp] rather than a guess at how many screens deep an app is.
     */
    suspend fun isAppRunning(): Boolean {
        val reply = session.request(Main(app_lock_status_request = LockStatusRequest()))
        return reply.app_lock_status_response?.locked == true
    }

    /** Deliver one button press, as the real input stack would emit it. */
    suspend fun pressButton(key: InputKey) {
        // A physical press emits PRESS, then SHORT, then RELEASE. Sending only
        // SHORT leaves apps that track press/release state confused.
        listOf(InputType.PRESS, InputType.SHORT, InputType.RELEASE).forEach { type ->
            session.request(
                Main(gui_send_input_event_request = SendInputEventRequest(key = key, type = type)),
            )
        }
    }

    /**
     * Start [app] with [args], which for emulation is the saved file's path.
     *
     * Falls back to the app's id if the display name is rejected. External apps
     * are matched by display name only, but a custom firmware could rename one,
     * and internal-app builds accept either -- so one extra round trip buys
     * real compatibility headroom.
     */
    suspend fun startApp(app: FlipperApp, args: String) {
        try {
            startAppByName(app.appName, args)
        } catch (e: FlipperRpcSession.RpcException) {
            if (e.status != CommandStatus.ERROR_APP_CANT_START) throw e
            startAppByName(app.appId, args)
        }
    }

    suspend fun startAppByName(name: String, args: String) {
        session.request(Main(app_start_request = StartRequest(name = name, args = args)))
    }

    /**
     * Close whatever application is running, by backing out of it.
     *
     * [AppExitRequest] cannot do this. Its handler only acts when the app
     * registered RPC callbacks, which happens solely for apps launched in RPC
     * mode (`args == "RPC"`); anything started with a file path answers
     * `ERROR_APP_NOT_RUNNING` and keeps running. Confirmed on hardware: the
     * Flipper stayed in the NFC app while the exit call "succeeded".
     *
     * So back out with BACK presses instead, checking [isAppRunning] after each
     * one. The loop is bounded because an app could sit behind more screens
     * than expected, or refuse to leave at all.
     *
     * @return true if nothing is running by the end.
     */
    suspend fun exitRunningApp(maxPresses: Int = DEFAULT_MAX_BACK_PRESSES): Boolean {
        if (!isAppRunning()) return true

        repeat(maxPresses) {
            // Ask politely first: an app started in RPC mode does honour this,
            // and it is one round trip to find out.
            runCatching { session.request(Main(app_exit_request = AppExitRequest())) }
            if (!isAppRunning()) return true

            pressButton(InputKey.BACK)
            if (!isAppRunning()) return true
        }
        return !isAppRunning()
    }

    companion object {
        /**
         * Enough to back out of a nested app screen, few enough that a stuck
         * app fails quickly rather than hammering the device.
         */
        const val DEFAULT_MAX_BACK_PRESSES = 4
    }

}
