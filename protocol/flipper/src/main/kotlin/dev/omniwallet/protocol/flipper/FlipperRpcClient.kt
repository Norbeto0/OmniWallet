package dev.omniwallet.protocol.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.AppExitRequest
import com.flipperdevices.protobuf.app.StartRequest
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

    /** Ask the running app to exit, which is how emulation is stopped. */
    suspend fun exitApp() {
        try {
            session.request(Main(app_exit_request = AppExitRequest()))
        } catch (e: FlipperRpcSession.RpcException) {
            // Already closed -- treat stopping a stopped app as success so the
            // UI cannot get stuck showing "emulating".
            if (e.status != CommandStatus.ERROR_APP_NOT_RUNNING) throw e
        }
    }
}
