package it.g03.users

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// Minimal smoke test to verify the Spring context starts (against Testcontainers PostgreSQL).
// The JWT decoder is built lazily (jwk-set-uri), so context startup does not need Keycloak.
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UsersApplicationTests {

    @Test
    // no assertions needed (successful startup is the assertion)
    fun contextLoads() {
    }
}
