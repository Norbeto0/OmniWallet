package dev.omniwallet.protocol.flipper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The app-lifecycle behaviour the first hardware run exposed.
 *
 * Two features were silently broken: stopping emulation did nothing while
 * reporting success, and emulating a second card always failed. Both are
 * reproduced here against [FakeFlipper] so they cannot regress unnoticed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLifecycleTest {

    private fun lifecycleTest(
        flipper: FakeFlipper = FakeFlipper(),
        body: suspend TestScope.(FlipperRpcClient, FakeFlipper) -> Unit,
    ) = runTest {
        val transport = FakeSerialTransport(maxWriteSize = 64)
        transport.responder = { request -> flipper.respond(request) }

        val sessionScope = CoroutineScope(coroutineContext + Job())
        val session = FlipperRpcSession(transport, sessionScope)
        session.start()
        runCurrent()
        session.flowControl.onCreditReported(8192)

        try {
            body(FlipperRpcClient(session), flipper)
        } finally {
            session.close()
            sessionScope.cancel()
        }
    }

    @Test
    fun `reports whether an app holds the loader lock`() = lifecycleTest { client, flipper ->
        val idle = async { client.isAppRunning() }
        runCurrent()
        idle.await() shouldBe false

        flipper.simulateAppAlreadyRunning()
        val busy = async { client.isAppRunning() }
        runCurrent()
        busy.await() shouldBe true
    }

    /**
     * The exact failure from the hardware log: app_exit answers
     * ERROR_APP_NOT_RUNNING and the app keeps running. Backing out must still
     * close it.
     */
    @Test
    fun `closes a file-launched app despite app_exit refusing`() = lifecycleTest { client, flipper ->
        flipper.simulateAppAlreadyRunning("NFC")

        val call = async { client.exitRunningApp() }
        runCurrent()

        call.await() shouldBe true
        flipper.appRunning shouldBe false
        (flipper.backPresses >= 1) shouldBe true
    }

    @Test
    fun `backs out of an app nested several screens deep`() =
        lifecycleTest(FakeFlipper(screenDepth = 3)) { client, flipper ->
            flipper.simulateAppAlreadyRunning()

            val call = async { client.exitRunningApp() }
            runCurrent()

            call.await() shouldBe true
            flipper.appRunning shouldBe false
            flipper.backPresses shouldBe 3
        }

    /**
     * Reporting success when the app is still running is what made the UI claim
     * emulation had stopped while the Flipper kept emitting. It must return
     * false instead, so the caller can raise.
     */
    @Test
    fun `reports failure when the app refuses to close`() =
        lifecycleTest(FakeFlipper(refusesToExit = true)) { client, flipper ->
            flipper.simulateAppAlreadyRunning()

            val call = async { client.exitRunningApp(maxPresses = 3) }
            runCurrent()

            call.await() shouldBe false
            flipper.appRunning shouldBe true
        }

    @Test
    fun `exiting when nothing runs is a no-op`() = lifecycleTest { client, flipper ->
        val call = async { client.exitRunningApp() }
        runCurrent()

        call.await() shouldBe true
        flipper.backPresses shouldBe 0
    }

    @Test
    fun `starts an app and records the file argument`() = lifecycleTest { client, flipper ->
        val call = async { client.startApp(FlipperApp.NFC, "/ext/nfc/office.nfc") }
        runCurrent()
        call.await()

        flipper.appRunning shouldBe true
        flipper.runningApp shouldBe "NFC"
        flipper.runningArgs shouldBe "/ext/nfc/office.nfc"
    }

    /**
     * The second half of the hardware failure: with an app already running, a
     * bare start is refused. Closing first is what makes switching cards work.
     */
    @Test
    fun `a bare start is refused while another app runs`() = lifecycleTest { client, flipper ->
        flipper.simulateAppAlreadyRunning("NFC")

        val call = async {
            shouldThrow<FlipperRpcSession.RpcException> {
                client.startApp(FlipperApp.NFC, "/ext/nfc/other.nfc")
            }
        }
        runCurrent()
        call.await().status shouldBe
            com.flipperdevices.protobuf.CommandStatus.ERROR_APP_SYSTEM_LOCKED
    }

    @Test
    fun `closing first lets a second card start`() = lifecycleTest { client, flipper ->
        val first = async { client.startApp(FlipperApp.NFC, "/ext/nfc/a.nfc") }
        runCurrent()
        first.await()
        flipper.runningArgs shouldBe "/ext/nfc/a.nfc"

        val switch = async {
            client.exitRunningApp()
            client.startApp(FlipperApp.NFC, "/ext/nfc/b.nfc")
        }
        runCurrent()
        switch.await()

        flipper.appRunning shouldBe true
        flipper.runningArgs shouldBe "/ext/nfc/b.nfc"
    }

    /** A press must look like a real one: PRESS, then SHORT, then RELEASE. */
    @Test
    fun `a button press emits the full press sequence`() = lifecycleTest { client, _ ->
        val call = async {
            client.pressButton(com.flipperdevices.protobuf.screen.InputKey.BACK)
        }
        runCurrent()
        call.await()
    }
}
