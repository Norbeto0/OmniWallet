package dev.omniwallet.core.domain

import io.kotest.matchers.shouldBe
import org.junit.Test

class ServiceLifecyclePolicyTest {

    @Test
    fun `idle with nothing to do means stop`() {
        ServiceLifecyclePolicy.shouldStop(sessionActive = false, requestsInFlight = 0) shouldBe true
    }

    @Test
    fun `a request in flight holds the service up`() {
        // The regression. A service started to begin an emulation has no
        // session yet -- that is the point of starting it. Stopping here
        // cancels the request that started it, which is why tapping the widget
        // did nothing at all.
        ServiceLifecyclePolicy.shouldStop(sessionActive = false, requestsInFlight = 1) shouldBe false
    }

    @Test
    fun `a live session holds the service up`() {
        ServiceLifecyclePolicy.shouldStop(sessionActive = true, requestsInFlight = 0) shouldBe false
    }

    @Test
    fun `a live session and work in flight holds the service up`() {
        ServiceLifecyclePolicy.shouldStop(sessionActive = true, requestsInFlight = 2) shouldBe false
    }

    @Test
    fun `broken accounting fails safe`() {
        // A negative count should not read as "less than no work".
        ServiceLifecyclePolicy.shouldStop(sessionActive = false, requestsInFlight = -1) shouldBe false
    }
}
