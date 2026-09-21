package dev.omniwallet.core.domain

import io.kotest.matchers.shouldBe
import org.junit.Test

class CredentialSearchTest {

    private fun card(
        discovered: String,
        custom: String? = null,
        place: Place? = null,
        path: String = "/ext/nfc/$discovered.nfc",
    ) = StoredCredential(
        id = CredentialId("FLIPPER_ZERO:$path"),
        deviceKind = DeviceKind.FLIPPER_ZERO,
        protocol = Protocol.NFC,
        location = CredentialLocation.FlipperFile(path),
        discoveredName = discovered,
        lastSeenAtMillis = 0,
        customName = custom,
        place = place,
    )

    private val office = card("Opatov_karta", custom = "Office door")
    private val gym = card("gym_fob", place = Place("Gym", 51.5, -0.12))
    private val garage = card("garage")

    private val all = listOf(office, gym, garage)

    @Test
    fun `an empty query matches everything`() {
        CredentialSearch.filter(all, "") shouldBe all
        CredentialSearch.filter(all, "   ") shouldBe all
    }

    @Test
    fun `matches the name you gave it`() {
        CredentialSearch.filter(all, "office") shouldBe listOf(office)
    }

    @Test
    fun `matches the name on the device`() {
        // People remember the original filename even after renaming it here.
        CredentialSearch.filter(all, "opatov") shouldBe listOf(office)
    }

    @Test
    fun `matches a tagged place`() {
        CredentialSearch.filter(all, "gym") shouldBe listOf(gym)
    }

    @Test
    fun `is case insensitive`() {
        CredentialSearch.filter(all, "OFFICE") shouldBe listOf(office)
        CredentialSearch.filter(all, "OpAtOv") shouldBe listOf(office)
    }

    @Test
    fun `every token must match, so a second word narrows`() {
        CredentialSearch.filter(all, "office door") shouldBe listOf(office)
        CredentialSearch.filter(all, "office garage") shouldBe emptyList()
    }

    @Test
    fun `token order does not matter`() {
        // People recall the words, rarely the order.
        CredentialSearch.filter(all, "door office") shouldBe listOf(office)
    }

    @Test
    fun `does not match the file path`() {
        // Matching a hidden field yields results with no visible reason to be
        // there, which reads as a bug. "nfc" appears in every path and in no
        // name, so this returns nothing rather than everything.
        CredentialSearch.filter(all, "ext") shouldBe emptyList()
        CredentialSearch.filter(all, "nfc") shouldBe emptyList()
    }

    @Test
    fun `no match is an empty list, not everything`() {
        CredentialSearch.filter(all, "nonsense") shouldBe emptyList()
    }

    @Test
    fun `order is preserved`() {
        // "o" is in "office"/"opatov" and in "gym_fob", but not in "garage".
        CredentialSearch.filter(all, "o") shouldBe listOf(office, gym)
        CredentialSearch.filter(all.reversed(), "o") shouldBe listOf(gym, office)
    }
}
