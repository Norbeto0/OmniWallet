package dev.omniwallet.core.domain

/**
 * Finding one credential among many.
 *
 * A file browser over a device can get away without search: it has folders,
 * and the folders are the organisation. A vault cannot. Once there are more
 * cards than fit on a screen, scrolling to the one you need at a door is the
 * whole experience, and it is a bad one.
 *
 * ### It only matches what you can see
 *
 * Deliberately not the file path, not the protocol, not the device kind.
 * Matching on a hidden field produces results with no visible reason to be
 * there, which reads as a bug rather than a feature -- the user is left
 * looking at a card wondering what it has to do with what they typed. Names
 * and the tagged place are on screen or one long-press away, so a match on
 * them always explains itself.
 */
object CredentialSearch {

    /**
     * Every whitespace-separated token must match somewhere.
     *
     * So "office door" finds a card named "Door, office side" -- people recall
     * the words, rarely the order. Requiring *all* tokens rather than any
     * keeps a second word narrowing the list, which is what typing more is
     * for.
     */
    fun matches(credential: StoredCredential, query: String): Boolean {
        val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true

        val haystack = buildString {
            append(credential.displayName.lowercase())
            append(' ')
            // The name on the device, which is often what someone remembers
            // even after renaming it here. Visible in the details sheet.
            append(credential.discoveredName.lowercase())
            credential.place?.let {
                append(' ')
                append(it.label.lowercase())
            }
        }

        return tokens.all { haystack.contains(it) }
    }

    /** Filter, preserving the order it was given. */
    fun filter(credentials: List<StoredCredential>, query: String): List<StoredCredential> =
        if (query.isBlank()) credentials else credentials.filter { matches(it, query) }

    private val WHITESPACE = Regex("\\s+")
}
