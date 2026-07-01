package it.g03.inventory

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// Minimal smoke test to verify the Spring context starts (against Testcontainers PostgreSQL, real Flyway).
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092", "events.publish.enabled=false", "app.kafka-listeners-enabled=false", "app.outbox.enabled=false", "inventory.reservation.enabled=false"])
@Import(TestcontainersConfiguration::class)
class InventoryApplicationTests {

	@Test
	// no assertions needed (successful startup is the assertion)
	fun contextLoads() {
	}
}
