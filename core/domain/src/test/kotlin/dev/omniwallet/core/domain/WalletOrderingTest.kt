package dev.omniwallet.core.domain

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test

class WalletOrderingTest {

    private fun card(
        name: String,
        protocol: Protocol = Protocol.NFC,
        favourite: Boolean = false,
        lastUsed: Long? = null,
        hidden: Boolean = false,
    ) = StoredCredential(
        id = CredentialId("FLIPPER_ZERO:/ext/x/$name"),
        deviceKind = DeviceKind.FLIPPER_ZERO,
        protocol = protocol,
        location = CredentialLocation.FlipperFile("/ext/x/$name"),
        discoveredName = name,
        lastSeenAtMillis = 0,
        favourite = favourite,
        hidden = hidden,
        lastUsedAtMillis = lastUsed,
    )

    @Test
    fun `favourites come first`() {
        val sorted = WalletOrdering.sort(
            listOf(card("zebra"), card("apple", favourite = true)),
        )
        sorted.map { it.displayName } shouldBe listOf("apple", "zebra")
    }

    /**
     * Recency beats alphabetical among equals: the card you used last is very
     * often the one you want next -- a door you go through twice a day.
     */
    @Test
    fun `most recently used wins among non-favourites`() {
        val sorted = WalletOrdering.sort(
            listOf(
                card("alpha", lastUsed = 100),
                card("bravo", lastUsed = 900),
                card("charlie", lastUsed = null),
            ),
        )
        sorted.map { it.displayName } shouldBe listOf("bravo", "alpha", "charlie")
    }

    @Test
    fun `never-used cards fall back to alphabetical`() {
        val sorted = WalletOrdering.sort(listOf(card("Zoe"), card("adam"), card("Mia")))
        sorted.map { it.displayName } shouldBe listOf("adam", "Mia", "Zoe")
    }

    @Test
    fun `a favourite outranks a more recently used non-favourite`() {
        val sorted = WalletOrdering.sort(
            listOf(card("recent", lastUsed = 9_999), card("fav", favourite = true, lastUsed = 1)),
        )
        sorted.first().displayName shouldBe "fav"
    }

    @Test
    fun `grouping keeps protocols in declaration order`() {
        val grouped = WalletOrdering.groupByProtocol(
            listOf(
                card("ir", Protocol.INFRARED),
                card("nfc", Protocol.NFC),
                card("sub", Protocol.SUBGHZ),
            ),
        )
        grouped.keys.toList() shouldBe listOf(Protocol.NFC, Protocol.SUBGHZ, Protocol.INFRARED)
    }

    @Test
    fun `grouping preserves sort order inside each protocol`() {
        val grouped = WalletOrdering.groupByProtocol(
            listOf(
                card("old", Protocol.NFC, lastUsed = 1),
                card("new", Protocol.NFC, lastUsed = 500),
            ),
        )
        grouped.getValue(Protocol.NFC).map { it.displayName } shouldBe listOf("new", "old")
    }

    @Test
    fun `hidden cards are filtered but not lost`() {
        val all = listOf(card("shown"), card("secret", hidden = true))
        WalletOrdering.visible(all) shouldHaveSize 1
        all shouldHaveSize 2
    }

    @Test
    fun `a custom name drives ordering, not the discovered one`() {
        val renamed = card("zzz").copy(customName = "aaa")
        val sorted = WalletOrdering.sort(listOf(card("mmm"), renamed))
        sorted.map { it.displayName } shouldBe listOf("aaa", "mmm")
    }
}
