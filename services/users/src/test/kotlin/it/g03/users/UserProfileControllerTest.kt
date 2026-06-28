package it.g03.users

import it.g03.users.repository.UserProfileRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

// Verifies the security wiring end-to-end through the filter chain: anonymous calls are rejected, and a
// valid (mocked) Keycloak token both authorizes the call and triggers just-in-time provisioning.
// The jwt() post-processor injects a pre-authenticated principal, so no real Keycloak/JWKS is needed.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UserProfileControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var profiles: UserProfileRepository

    @BeforeEach
    fun clean() {
        profiles.deleteAll()
    }

    // No bearer token → 401 Unauthorized; nothing is provisioned.
    @Test
    fun `me without a token is unauthorized`() {
        mockMvc.get("/api/users/me").andExpect {
            status { isUnauthorized() }
        }
        assertEquals(0, profiles.count())
    }

    // Valid token → 200, profile JIT-created from the token claims.
    @Test
    fun `me with a token provisions and returns the profile`() {
        mockMvc.get("/api/users/me") {
            with(
                jwt().jwt { builder ->
                    builder.subject("kc-sub-42")
                        .claim("email", "grace@eurotransit.test")
                        .claim("preferred_username", "grace")
                },
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.keycloakSub") { value("kc-sub-42") }
            jsonPath("$.email") { value("grace@eurotransit.test") }
        }
        assertEquals(1, profiles.count())
    }

    // Actuator readiness must stay open (probes run without a token).
    @Test
    fun `readiness probe is public`() {
        mockMvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
        }
    }
}
