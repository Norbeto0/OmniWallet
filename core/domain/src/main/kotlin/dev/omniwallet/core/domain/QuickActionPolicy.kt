package dev.omniwallet.core.domain

/**
 * Whether a request arriving from outside the app may act.
 *
 * Only one such surface remains: the automation intent, which another app on
 * the phone can broadcast. It used to also cover a home-screen widget and a
 * Quick Settings tile, both since removed -- the official Flipper app does
 * those better, and this one is a credential vault rather than a device
 * companion.
 *
 * Pure, and in the domain module, because this decides whether a door opens
 * for a caller that has not authenticated. It should be provable from fixtures
 * rather than reasoned about.
 */
object QuickActionPolicy {

    /** What the caller asked for. */
    enum class Request { START, STOP }

    /** The state the decision is made against. */
    data class Conditions(
        val appLockEnabled: Boolean,
        val automationEnabled: Boolean,
        /** The card being asked for, or null if it is not in the library. */
        val target: StoredCredential?,
        /** Whether [target] is what is already running. */
        val alreadyRunning: Boolean,
    )

    sealed interface Decision {
        /** Go ahead. */
        data object Start : Decision

        /** Go ahead, but the request resolves to a stop. */
        data object Stop : Decision

        /** Refuse, with something the user can act on. */
        data class Refuse(val reason: String) : Decision

        /** Needs a human at the phone: open the app so it can authenticate. */
        data object NeedsUnlock : Decision
    }

    /**
     * Decide.
     *
     * The order of these checks is the design, not an implementation detail:
     *
     *  1. **A request that resolves to a stop is always granted.** Stopping is
     *     the safe direction, it discloses nothing, and a stop that refuses
     *     because the app is locked is the same failure as a stop button that
     *     does not stop. This sits above the lock check deliberately.
     *  2. **Automation must have been switched on.** Off by default, because an
     *     exported trigger for physical-access credentials is an attack
     *     surface whether or not anyone is currently pointing at it.
     *  3. **A locked vault does not open for a broadcast.** There is no way to
     *     authenticate one, so the request goes to the activity where there
     *     is. A vault that opened for whatever asked loudest would not be one.
     *  4. Only then: is this card actually usable?
     */
    fun decide(request: Request, conditions: Conditions): Decision {
        if (request == Request.STOP || conditions.alreadyRunning) return Decision.Stop

        if (!conditions.automationEnabled) {
            return Decision.Refuse("Automation is switched off in OmniWallet settings")
        }

        if (conditions.appLockEnabled) return Decision.NeedsUnlock

        val target = conditions.target
            ?: return Decision.Refuse("That card is not in your wallet")

        if (!target.present) {
            return Decision.Refuse("${target.displayName} is not on the device")
        }

        // Hidden means the user deliberately pushed it out of the way. Acting
        // on it for a caller that cannot see what it is choosing would quietly
        // undo that.
        if (target.hidden) {
            return Decision.Refuse("${target.displayName} is hidden")
        }

        return Decision.Start
    }
}
