package dev.omniwallet.core.domain

/**
 * When the emulation service may shut itself down.
 *
 * Three lines of logic, which is exactly why it was wrong. The service used to
 * decide this by collecting the "what is emulating" state flow and stopping on
 * a null -- and a state flow replays its current value to every new collector,
 * so a service started to *begin* an emulation received null immediately and
 * killed itself before the work it was started for had read a single setting.
 * Cancelling its own scope on the way out took the request with it.
 *
 * The missing term is work in flight. A service holding up a request that has
 * not finished is not idle, however empty the session is at that instant.
 *
 * Pure and here, next to [QuickActionPolicy], because the service itself needs
 * a running Android to test and this rule does not.
 */
object ServiceLifecyclePolicy {

    /**
     * @param sessionActive whether something is currently being emulated.
     * @param requestsInFlight how many start/stop requests are still running.
     */
    fun shouldStop(sessionActive: Boolean, requestsInFlight: Int): Boolean {
        if (sessionActive) return false
        // Defensive: a negative count means the accounting is broken, and
        // reading that as "less than no work" would stop the service mid
        // request -- the exact failure this exists to prevent.
        if (requestsInFlight != 0) return false
        return true
    }
}
