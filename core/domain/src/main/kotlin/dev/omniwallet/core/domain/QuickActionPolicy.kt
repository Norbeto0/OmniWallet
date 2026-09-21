package dev.omniwallet.core.domain

/**
 * What a surface outside the app is allowed to do.
 *
 * The home-screen widget, the Quick Settings tile and the automation intent
 * all ask the same question -- "may I start this card?" -- from contexts with
 * no UI and, for automation, no guarantee the request came from the person
 * holding the phone. Three surfaces means three chances to get the answer
 * subtly wrong, so the answer is computed in exactly one place.
 *
 * Pure, and here rather than in the app module, for the reason the protocol
 * codecs are: this decides whether a door opens. It should be provable from
 * fixtures rather than reasoned about.
 */
object QuickActionPolicy {

    /** Where a request came from. Automation is held to a stricter standard. */
    enum class Source {
        /** A tap on the home-screen widget. */
        WIDGET,

        /** A tap on the Quick Settings tile. */
        TILE,

        /** A broadcast from another app, e.g. Tasker. */
        AUTOMATION,
    }

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
     *  3. **A locked app does not act.** None of these surfaces can show a
     *     biometric prompt, so the request goes to the activity where it can
     *     be authenticated. A widget that emulated past a lock the user
     *     switched on would make that lock decorative.
     *  4. Only then: is this card actually usable?
     */
    fun decide(source: Source, request: Request, conditions: Conditions): Decision {
        if (request == Request.STOP || conditions.alreadyRunning) return Decision.Stop

        if (source == Source.AUTOMATION && !conditions.automationEnabled) {
            return Decision.Refuse("Automation is switched off in OmniWallet settings")
        }

        if (conditions.appLockEnabled) return Decision.NeedsUnlock

        val target = conditions.target
            ?: return Decision.Refuse("That card is not in your wallet")

        if (!target.present) {
            return Decision.Refuse("${target.displayName} is not on the device")
        }

        // Hidden means the user deliberately pushed it out of the way. Acting
        // on it from a surface where they cannot see what they are choosing
        // would quietly undo that.
        if (target.hidden) {
            return Decision.Refuse("${target.displayName} is hidden")
        }

        return Decision.Start
    }
}
