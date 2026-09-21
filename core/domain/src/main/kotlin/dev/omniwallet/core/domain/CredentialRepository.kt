package dev.omniwallet.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * The user's credential library.
 *
 * Local-first and local-only: nothing here reaches a network, and nothing here
 * writes back to the device. Renaming is the app's own metadata -- the brief is
 * explicit that device files are not mutated.
 */
interface CredentialRepository {

    /** Everything stored, ordered for display. Emits on every change. */
    fun observeCredentials(): Flow<List<StoredCredential>>

    /** Reconcile a completed device scan into the library. */
    suspend fun applyScan(
        deviceKind: DeviceKind,
        discovered: List<RemoteCredential>,
        nowMillis: Long = System.currentTimeMillis(),
    )

    suspend fun setFavourite(id: CredentialId, favourite: Boolean)

    /** Set a local display name. Blank or null restores the device's own name. */
    suspend fun rename(id: CredentialId, customName: String?)

    suspend fun setHidden(id: CredentialId, hidden: Boolean)

    /** Record that this credential was just emulated. */
    suspend fun markUsed(id: CredentialId, atMillis: Long = System.currentTimeMillis())

    /**
     * Tag -- or untag, with null -- where this credential gets used.
     *
     * Always an explicit act by the user. Nothing in this app writes a place
     * as a side effect of emulating somewhere; see [Place] for why.
     */
    suspend fun setPlace(id: CredentialId, place: Place?)
}

/**
 * How the wallet orders itself.
 *
 * Pure so the ordering is testable: a list that silently sorts wrong is the
 * kind of thing that looks fine in a screenshot and wrong in the hand.
 */
object WalletOrdering {

    /**
     * Favourites first, then most recently used, then alphabetical.
     *
     * Recency beats alphabetical because the card you used last is very often
     * the card you want next -- a door you go through twice a day.
     */
    fun sort(credentials: List<StoredCredential>): List<StoredCredential> =
        credentials.sortedWith(
            compareByDescending<StoredCredential> { it.favourite }
                .thenByDescending { it.lastUsedAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.displayName.lowercase() },
        )

    /** Group for display, preserving [sort] order inside each protocol. */
    fun groupByProtocol(credentials: List<StoredCredential>): Map<Protocol, List<StoredCredential>> =
        sort(credentials)
            .groupBy { it.protocol }
            .toSortedMap(compareBy { it.ordinal })

    /** Visible entries only: hidden ones stay stored but out of the way. */
    fun visible(credentials: List<StoredCredential>): List<StoredCredential> =
        credentials.filterNot { it.hidden }
}
