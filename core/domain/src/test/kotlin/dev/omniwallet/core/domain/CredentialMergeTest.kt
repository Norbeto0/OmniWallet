package dev.omniwallet.core.domain

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The invariant these defend: **a device scan must never destroy anything the
 * user did.** Renames, favourites and usage history belong to the user; only
 * the discovered fields belong to the device.
 */
class CredentialMergeTest {

    private fun remote(path: String, name: String = path.substringAfterLast('/').substringBeforeLast('.')) =
        RemoteCredential(
            displayName = name,
            protocol = Protocol.NFC,
            location = CredentialLocation.FlipperFile(path),
            sizeBytes = 512,
        )

    private fun stored(
        path: String,
        customName: String? = null,
        favourite: Boolean = false,
        lastUsed: Long? = null,
        hidden: Boolean = false,
        present: Boolean = true,
    ) = StoredCredential(
        id = CredentialMerge.idFor(DeviceKind.FLIPPER_ZERO, CredentialLocation.FlipperFile(path)),
        deviceKind = DeviceKind.FLIPPER_ZERO,
        protocol = Protocol.NFC,
        location = CredentialLocation.FlipperFile(path),
        discoveredName = path.substringAfterLast('/').substringBeforeLast('.'),
        lastSeenAtMillis = 1_000,
        present = present,
        customName = customName,
        favourite = favourite,
        hidden = hidden,
        lastUsedAtMillis = lastUsed,
    )

    @Test
    fun `adds newly discovered credentials`() {
        val merged = CredentialMerge.merge(
            existing = emptyList(),
            discovered = listOf(remote("/ext/nfc/Opatov_karta.nfc")),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 5_000,
        )

        merged shouldHaveSize 1
        merged[0].discoveredName shouldBe "Opatov_karta"
        merged[0].lastSeenAtMillis shouldBe 5_000
        merged[0].present shouldBe true
    }

    /** The one that matters most. */
    @Test
    fun `a rescan preserves every user-owned field`() {
        val existing = listOf(
            stored(
                path = "/ext/nfc/Opatov_karta.nfc",
                customName = "Office door",
                favourite = true,
                lastUsed = 4_242,
                hidden = true,
            ),
        )

        val merged = CredentialMerge.merge(
            existing = existing,
            discovered = listOf(remote("/ext/nfc/Opatov_karta.nfc")),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 9_000,
        ).single()

        merged.customName shouldBe "Office door"
        merged.favourite shouldBe true
        merged.lastUsedAtMillis shouldBe 4_242
        merged.hidden shouldBe true
        merged.displayName shouldBe "Office door"

        // ...while device-owned fields do refresh.
        merged.lastSeenAtMillis shouldBe 9_000
        merged.present shouldBe true
    }

    @Test
    fun `refreshes the discovered name without disturbing the custom one`() {
        val existing = listOf(stored("/ext/nfc/card.nfc", customName = "Gym"))

        val merged = CredentialMerge.merge(
            existing = existing,
            discovered = listOf(remote("/ext/nfc/card.nfc", name = "card_v2")),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 2_000,
        ).single()

        merged.discoveredName shouldBe "card_v2"
        merged.customName shouldBe "Gym"
        merged.displayName shouldBe "Gym"
    }

    /**
     * A Flipper that is switched off, asleep or out of range reports nothing.
     * Deleting the library on that basis would lose the user's renames to a
     * flat battery.
     */
    @Test
    fun `an empty scan marks entries absent rather than deleting them`() {
        val existing = listOf(stored("/ext/nfc/a.nfc", favourite = true))

        val merged = CredentialMerge.merge(
            existing = existing,
            discovered = emptyList(),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 7_000,
        )

        merged shouldHaveSize 1
        merged[0].present shouldBe false
        merged[0].favourite shouldBe true
    }

    @Test
    fun `a credential that reappears becomes present again`() {
        val existing = listOf(stored("/ext/nfc/a.nfc", present = false, favourite = true))

        val merged = CredentialMerge.merge(
            existing = existing,
            discovered = listOf(remote("/ext/nfc/a.nfc")),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 8_000,
        ).single()

        merged.present shouldBe true
        merged.favourite shouldBe true
    }

    /** A scan from one backend must not mark another backend's cards absent. */
    @Test
    fun `a scan only touches its own device kind`() {
        val chameleon = StoredCredential(
            id = CredentialMerge.idFor(DeviceKind.CHAMELEON_ULTRA, CredentialLocation.ChameleonSlot(1)),
            deviceKind = DeviceKind.CHAMELEON_ULTRA,
            protocol = Protocol.NFC,
            location = CredentialLocation.ChameleonSlot(1),
            discoveredName = "slot 1",
            lastSeenAtMillis = 1_000,
        )

        val merged = CredentialMerge.merge(
            existing = listOf(chameleon),
            discovered = emptyList(),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 3_000,
        ).single()

        merged.present shouldBe true
    }

    /**
     * Renaming the file on the device is genuinely a different file. Guessing
     * that it is "the same card moved" would silently transplant a user's
     * favourite onto something else.
     */
    @Test
    fun `renaming on the device yields a separate entry`() {
        val existing = listOf(stored("/ext/nfc/old.nfc", favourite = true))

        val merged = CredentialMerge.merge(
            existing = existing,
            discovered = listOf(remote("/ext/nfc/new.nfc")),
            deviceKind = DeviceKind.FLIPPER_ZERO,
            nowMillis = 6_000,
        )

        merged shouldHaveSize 2
        merged.single { it.discoveredName == "old" }.present shouldBe false
        merged.single { it.discoveredName == "new" }.favourite shouldBe false
    }

    @Test
    fun `ids are stable and distinguish device kinds`() {
        val path = CredentialLocation.FlipperFile("/ext/nfc/Opatov_karta.nfc")
        CredentialMerge.idFor(DeviceKind.FLIPPER_ZERO, path) shouldBe
            CredentialId("FLIPPER_ZERO:/ext/nfc/Opatov_karta.nfc")
        CredentialMerge.idFor(DeviceKind.CHAMELEON_ULTRA, CredentialLocation.ChameleonSlot(3)) shouldBe
            CredentialId("CHAMELEON_ULTRA:slot/3")
    }

    @Test
    fun `ids survive awkward filenames`() {
        listOf(
            "/ext/nfc/My Office Card.nfc",
            "/ext/nfc/karta-Opatov_2.nfc",
            "/ext/nfc/Zámek.nfc",
            "/ext/subghz/Tesla/charge port.sub",
        ).forEach { path ->
            val id = CredentialMerge.idFor(DeviceKind.FLIPPER_ZERO, CredentialLocation.FlipperFile(path))
            id shouldBe CredentialId("FLIPPER_ZERO:$path")
        }
    }

    @Test
    fun `merging is idempotent`() {
        val discovered = listOf(remote("/ext/nfc/a.nfc"), remote("/ext/nfc/b.nfc"))
        val once = CredentialMerge.merge(emptyList(), discovered, DeviceKind.FLIPPER_ZERO, 1_000)
        val twice = CredentialMerge.merge(once, discovered, DeviceKind.FLIPPER_ZERO, 1_000)
        twice shouldBe once
    }
}
