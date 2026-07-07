package it.g03.inventory

import it.g03.inventory.entities.TripSeats
import it.g03.inventory.repository.TripSeatsRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Only the seat-availability projection is public (the storefront browse overlay); the raw-entity /items debug
 * reads stay authenticated. Verifies the SecurityConfig split and the per-trip lookup's 404.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "inventory.reservation.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InventorySecurityTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var items: TripSeatsRepository

    @BeforeEach
    fun seed() {
        items.deleteAll()
        items.save(TripSeats(tripCode = "FR9592-20260629", totalSeats = 100, available = 42))
    }

    @Test
    fun `availability list is publicly readable without a token`() {
        mockMvc.perform(get("/api/inventory/availability")).andExpect(status().isOk)
    }

    @Test
    fun `per-trip availability is public and returns the projection`() {
        mockMvc.perform(get("/api/inventory/availability/FR9592-20260629"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.available").value(42))
            .andExpect(jsonPath("$.totalSeats").value(100))
    }

    @Test
    fun `per-trip availability for an unknown trip is 404`() {
        mockMvc.perform(get("/api/inventory/availability/NO-SUCH-TRIP")).andExpect(status().isNotFound)
    }

    @Test
    fun `the raw items debug read still requires a token`() {
        mockMvc.perform(get("/api/inventory/items")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the availability republish op requires a token`() {
        mockMvc.perform(post("/api/inventory/republish-availability")).andExpect(status().isUnauthorized)
    }
}
