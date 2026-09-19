package dev.omniwallet.protocol.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.LockStatusResponse
import com.flipperdevices.protobuf.screen.InputKey
import com.flipperdevices.protobuf.screen.InputType

/**
 * A stand-in Flipper that reproduces the app-lifecycle behaviour observed on
 * real hardware, so the fixes for it can be tested without a device.
 *
 * The behaviours that matter, all taken from the first hardware run and
 * corroborated in `rpc_app.c`:
 *
 *  - only one app runs at a time; starting another answers
 *    `ERROR_APP_SYSTEM_LOCKED`
 *  - `AppExitRequest` answers `ERROR_APP_NOT_RUNNING` for an app launched with
 *    a file path, and leaves it running
 *  - backing out takes [screenDepth] BACK presses
 *  - `app_lock_status` reports whether anything is running
 */
class FakeFlipper(
    /** How many BACK presses it takes to leave the running app. */
    private val screenDepth: Int = 1,
    /** When true, BACK never closes the app -- the stuck case. */
    private val refusesToExit: Boolean = false,
) {

    var appRunning: Boolean = false
        private set

    var runningApp: String? = null
        private set

    var runningArgs: String? = null
        private set

    private var remainingPresses = 0

    /** Every app the fake was asked to start, including refused attempts. */
    val startAttempts = mutableListOf<Pair<String, String>>()

    var backPresses = 0
        private set

    /** Pretend an app was launched by the user, outside our control. */
    fun simulateAppAlreadyRunning(name: String = "NFC") {
        appRunning = true
        runningApp = name
        remainingPresses = screenDepth
    }

    fun respond(request: Main): List<Main> {
        val id = request.command_id
        fun ok(content: Main = Main()) = listOf(content.copy(command_id = id))
        fun error(status: CommandStatus) =
            listOf(Main(command_id = id, command_status = status))

        return when {
            request.app_lock_status_request != null ->
                ok(Main(app_lock_status_response = LockStatusResponse(locked = appRunning)))

            request.app_start_request != null -> {
                val name = request.app_start_request?.name.orEmpty()
                val args = request.app_start_request?.args.orEmpty()
                startAttempts += name to args
                if (appRunning) {
                    error(CommandStatus.ERROR_APP_SYSTEM_LOCKED)
                } else {
                    appRunning = true
                    runningApp = name
                    runningArgs = args
                    remainingPresses = screenDepth
                    ok()
                }
            }

            // Matches the firmware: an app started with a file path never
            // registered RPC callbacks, so it cannot be exited this way and
            // keeps running.
            request.app_exit_request != null ->
                error(CommandStatus.ERROR_APP_NOT_RUNNING)

            request.gui_send_input_event_request != null -> {
                val event = request.gui_send_input_event_request
                if (event?.key == InputKey.BACK && event.type == InputType.RELEASE) {
                    backPresses++
                    if (appRunning && !refusesToExit) {
                        remainingPresses--
                        if (remainingPresses <= 0) {
                            appRunning = false
                            runningApp = null
                            runningArgs = null
                        }
                    }
                }
                ok()
            }

            else -> ok()
        }
    }
}
