package it.g03.users

import it.g03.users.dto.UpdateProfileRequest
import it.g03.users.dto.UserClaims
import it.g03.users.repository.UserProfileRepository
import it.g03.users.service.UserProfileService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// Behaviour tests for just-in-time provisioning against a real PostgreSQL (Testcontainers).
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UserProfileServiceTest {

    @Autowired lateinit var service: UserProfileService
    @Autowired lateinit var profiles: UserProfileRepository

    @BeforeEach
    fun clean() {
        profiles.deleteAll()
    }

    private fun claims(
        sub: String = "kc-sub-1",
        email: String = "ada@eurotransit.test",
        given: String? = "Ada",
        family: String? = "Lovelace",
        username: String? = "ada",
    ) = UserClaims(sub, email, given, family, username)

    // First contact creates the row from the token claims, with a surrogate UUID id and the sub as natural key.
    @Test
    fun `provisions a profile on first contact`() {
        val profile = service.getOrProvision(claims())

        assertNotNull(profile.id, "a surrogate UUID id is assigned")
        assertEquals("kc-sub-1", profile.keycloakSub)
        assertEquals("ada@eurotransit.test", profile.email)
        assertEquals("Ada", profile.givenName)
        assertEquals(1, profiles.count())
    }

    // Re-provisioning with identical claims is idempotent — no duplicate rows (keyed by the Keycloak sub).
    @Test
    fun `provisioning is idempotent for the same subject`() {
        val first = service.getOrProvision(claims())
        val second = service.getOrProvision(claims())

        assertEquals(first.id, second.id, "same sub must resolve to the same row")
        assertEquals(1, profiles.count())
    }

    // When the token's identity fields change, the local copy is refreshed.
    @Test
    fun `refreshes token-owned fields when they change`() {
        service.getOrProvision(claims(email = "old@eurotransit.test"))

        val refreshed = service.getOrProvision(claims(email = "new@eurotransit.test", given = "Augusta"))

        assertEquals("new@eurotransit.test", refreshed.email)
        assertEquals("Augusta", refreshed.givenName)
        assertEquals(1, profiles.count())
    }

    // Editable display fields are updated; null request fields are left unchanged.
    @Test
    fun `updates editable fields and leaves nulls untouched`() {
        service.getOrProvision(claims())

        val updated = service.update("kc-sub-1", UpdateProfileRequest(givenName = "Ada B."))

        assertEquals("Ada B.", updated.givenName)
        assertEquals("Lovelace", updated.familyName)
    }

    // The new preference fields (locale + currency) round-trip; currency is normalised to uppercase.
    @Test
    fun `updates locale and preferred currency`() {
        service.getOrProvision(claims())

        val updated = service.update("kc-sub-1", UpdateProfileRequest(locale = "it-IT", preferredCurrency = "eur"))

        assertEquals("it-IT", updated.locale)
        assertEquals("EUR", updated.preferredCurrency)
    }

    // A malformed currency is rejected at the boundary (mapped to 400).
    @Test
    fun `rejects a malformed preferred currency`() {
        service.getOrProvision(claims())

        assertFailsWith<IllegalArgumentException> {
            service.update("kc-sub-1", UpdateProfileRequest(preferredCurrency = "euro"))
        }
    }

    // Updating a subject that was never provisioned is a domain not-found (mapped to 404).
    @Test
    fun `update of unknown subject is a not-found`() {
        assertFailsWith<NoSuchElementException> {
            service.update("nobody", UpdateProfileRequest(givenName = "X"))
        }
    }
}
