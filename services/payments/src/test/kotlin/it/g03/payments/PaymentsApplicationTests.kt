package it.g03.payments

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

// Minimal smoke test to verify the Spring context starts (against Testcontainers PostgreSQL, real Flyway).
@SpringBootTest(
	properties = [
		"spring.kafka.bootstrap-servers=localhost:9092",
		"events.publish.enabled=false",
		"app.kafka-listeners-enabled=false",   // keep the command listeners off (no broker)
	],
)
@Import(TestcontainersConfiguration::class)
class PaymentsApplicationTests {

	@Test
	// no assertions needed (successful startup is the assertion)
	fun contextLoads() {
	}
}
