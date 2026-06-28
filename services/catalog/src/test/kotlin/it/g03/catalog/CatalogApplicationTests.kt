package it.g03.catalog

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// Minimal smoke test to verify the Spring context starts (against Testcontainers PostgreSQL).
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogApplicationTests {

    @Test
    // no assertions needed (successful startup is the assertion)
    fun contextLoads() {
    }
}
