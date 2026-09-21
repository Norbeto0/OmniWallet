package dev.omniwallet.core.domain

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test

/**
 * There is no GPS in the environment this project is developed in, any more
 * than there is a Bluetooth radio. These fixtures are the only way to know the
 * ranking is right before it reaches a phone.
 */
class ProximityRankingTest {

    private val now = 1_000_000L

    private fun credential(
        id: String,
        place: Place? = null,
        favourite: Boolean = false,
        hidden: Boolean = false,
        present: Boolean = true,
        name: String = id,
    ) = StoredCredential(
        id = CredentialId(id),
        deviceKind = DeviceKind.FLIPPER_ZERO,
        protocol = Protocol.NFC,
        location = CredentialLocation.FlipperFile("/ext/nfc/$id.nfc"),
        discoveredName = name,
        lastSeenAtMillis = 0,
        present = present,
        favourite = favourite,
        hidden = hidden,
        place = place,
    )

    private fun fix(
        latitude: Double,
        longitude: Double,
        accuracy: Double = 10.0,
        at: Long = now,
    ) = Fix(latitude, longitude, accuracy, at)

    // --- distance ---------------------------------------------------------

    @Test
    fun `distance between a point and itself is zero`() {
        ProximityRanking.distanceMetres(51.5, -0.12, 51.5, -0.12) shouldBe 0.0
    }

    @Test
    fun `one degree of latitude is about 111 km anywhere`() {
        // Meridians are great circles, so this holds at the equator and at the
        // pole alike -- a cheap check that the formula is not secretly planar.
        val atEquator = ProximityRanking.distanceMetres(0.0, 0.0, 1.0, 0.0)
        val atPole = ProximityRanking.distanceMetres(80.0, 30.0, 81.0, 30.0)

        atEquator shouldBeGreaterThan 111_000.0
        atEquator shouldBeLessThan 111_400.0
        atPole shouldBeGreaterThan 111_000.0
        atPole shouldBeLessThan 111_400.0
    }

    @Test
    fun `longitude converges towards the poles`() {
        // The bug a planar approximation produces: at 60 degrees north a degree
        // of longitude is half what it is at the equator, so a 150 m radius
        // would reach twice as far east-west as intended.
        val equator = ProximityRanking.distanceMetres(0.0, 0.0, 0.0, 1.0)
        val sixty = ProximityRanking.distanceMetres(60.0, 0.0, 60.0, 1.0)

        (sixty / equator) shouldBeGreaterThan 0.49
        (sixty / equator) shouldBeLessThan 0.51
    }

    @Test
    fun `antipodal points do not produce NaN`() {
        // asin of a value a hair over 1, reachable through rounding, returns
        // NaN and would silently drop every credential from the list.
        val d = ProximityRanking.distanceMetres(0.0, 0.0, 0.0, 180.0)
        d.isNaN() shouldBe false
        d shouldBeGreaterThan 20_000_000.0
    }

    @Test
    fun `distance across the antimeridian is short`() {
        val d = ProximityRanking.distanceMetres(0.0, 179.999, 0.0, -179.999)
        d shouldBeLessThan 300.0
    }

    // --- fix usability ----------------------------------------------------

    @Test
    fun `a fresh accurate fix is usable`() {
        ProximityRanking.isUsable(fix(51.5, -0.12), now) shouldBe true
    }

    @Test
    fun `a stale fix is not usable`() {
        val old = fix(51.5, -0.12, at = now - ProximityRanking.MAX_FIX_AGE_MILLIS - 1)
        ProximityRanking.isUsable(old, now) shouldBe false
    }

    @Test
    fun `a fix from the future fails closed`() {
        // Same call as the app lock: an age that cannot be believed is not a
        // reason to trust the reading, it is a reason to discard it.
        ProximityRanking.isUsable(fix(51.5, -0.12, at = now + 5_000), now) shouldBe false
    }

    @Test
    fun `a cell-tower-grade fix is not usable`() {
        val vague = fix(51.5, -0.12, accuracy = ProximityRanking.MAX_ACCURACY_METRES + 1)
        ProximityRanking.isUsable(vague, now) shouldBe false
    }

    @Test
    fun `a broken accuracy reading is not usable`() {
        ProximityRanking.isUsable(fix(51.5, -0.12, accuracy = -1.0), now) shouldBe false
        ProximityRanking.isUsable(fix(51.5, -0.12, accuracy = Double.NaN), now) shouldBe false
    }

    // --- nearby -----------------------------------------------------------

    private val office = Place("Office", 51.5007, -0.1246)

    /** ~90 m north of [office]. */
    private val justOutside = Place("Cafe", 51.5015, -0.1246)

    /** Several kilometres away. */
    private val home = Place("Home", 51.5400, -0.1400)

    @Test
    fun `returns tagged credentials within reach, nearest first`() {
        val result = ProximityRanking.nearby(
            credentials = listOf(
                credential("cafe", justOutside),
                credential("office", office),
                credential("home", home),
                credential("untagged"),
            ),
            fix = fix(office.latitude, office.longitude),
            nowElapsedRealtimeMillis = now,
        )

        result.map { it.credential.id.value } shouldBe listOf("office", "cafe")
    }

    @Test
    fun `no fix means no section, not an empty-handed guess`() {
        ProximityRanking.nearby(
            credentials = listOf(credential("office", office)),
            fix = null,
            nowElapsedRealtimeMillis = now,
        ) shouldBe emptyList()
    }

    @Test
    fun `an unusable fix produces nothing`() {
        ProximityRanking.nearby(
            credentials = listOf(credential("office", office)),
            fix = fix(office.latitude, office.longitude, accuracy = 5_000.0),
            nowElapsedRealtimeMillis = now,
        ) shouldBe emptyList()
    }

    @Test
    fun `a poor but usable fix widens the net rather than narrowing it`() {
        // The whole point of adding accuracy to the radius. With a 400 m fix,
        // a place 300 m away is plausibly right here, and omitting it would
        // cost the feature its reason to exist.
        val threeHundredMetresNorth = Place("Gate", office.latitude + 0.0027, office.longitude)
        val credentials = listOf(credential("gate", threeHundredMetresNorth))

        val precise = ProximityRanking.nearby(
            credentials,
            fix(office.latitude, office.longitude, accuracy = 5.0),
            now,
        )
        val vague = ProximityRanking.nearby(
            credentials,
            fix(office.latitude, office.longitude, accuracy = 400.0),
            now,
        )

        precise shouldBe emptyList()
        vague.map { it.credential.id.value } shouldBe listOf("gate")
    }

    @Test
    fun `hidden and absent credentials are never suggested`() {
        val result = ProximityRanking.nearby(
            credentials = listOf(
                credential("hidden", office, hidden = true),
                credential("absent", office, present = false),
                credential("usable", office),
            ),
            fix = fix(office.latitude, office.longitude),
            nowElapsedRealtimeMillis = now,
        )

        result.map { it.credential.id.value } shouldBe listOf("usable")
    }

    @Test
    fun `cards at the same place have a stable order`() {
        // Two keys tagged at one door are exactly equidistant, and a sort that
        // fell back on list order would let them swap on every recomposition.
        val credentials = listOf(
            credential("b", office, name = "Back door"),
            credential("a", office, name = "Ahead door"),
            credential("f", office, name = "Zed door", favourite = true),
        )
        val here = fix(office.latitude, office.longitude)

        val once = ProximityRanking.nearby(credentials, here, now)
        val again = ProximityRanking.nearby(credentials.reversed(), here, now)

        once.map { it.credential.id.value } shouldBe listOf("f", "a", "b")
        again.map { it.credential.id.value } shouldBe once.map { it.credential.id.value }
    }

    @Test
    fun `distance is reported, not just membership`() {
        val result = ProximityRanking.nearby(
            credentials = listOf(credential("cafe", justOutside)),
            fix = fix(office.latitude, office.longitude),
            nowElapsedRealtimeMillis = now,
        )

        result.single().distanceMetres shouldBeGreaterThan 80.0
        result.single().distanceMetres shouldBeLessThan 100.0
    }

    @Test
    fun `distance formatting stays readable at every scale`() {
        ProximityRanking.formatDistance(3.0) shouldBe "here"
        ProximityRanking.formatDistance(87.4) shouldBe "87 m away"
        // Not an exact match: the decimal separator follows the locale, which
        // is the point. Asserting "2.5" here would pass in London and fail in
        // Berlin, testing the test machine rather than the code.
        ProximityRanking.formatDistance(2_500.0) shouldEndWith " km away"
        ProximityRanking.formatDistance(2_500.0) shouldStartWith "2"
    }
}
