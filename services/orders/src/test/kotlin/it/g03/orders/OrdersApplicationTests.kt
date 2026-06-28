package it.g03.orders

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.assertTrue

// Minimal smoke test to verify if the Spring context fails to start
@SpringBootTest(
	properties = [
		"spring.kafka.bootstrap-servers=localhost:9092", // dummy broker address (no real connection is made)
		"events.publish.enabled=false",                   // no broker in tests
		"app.kafka-listeners-enabled=false",              // keep the listener off (no broker)
		"app.outbox.enabled=false",                       // no background relay racing the assertions
		"app.recovery.enabled=false",                     // no recovery sweep racing the assertions
	],
)
class OrdersApplicationTests {

	// Injects the Spring context so the test can assert on the registered beans
	@Autowired
	private lateinit var context: ApplicationContext

	@Test
	// no assertions needed (successful startup is the assertion)
	fun contextLoads() {
	}

	// Verifies the Resilience4j auto-configuration actually bound the CircuitBreakerRegistry bean
	@Test
	fun `resilience4j auto-configuration actually binds on Spring Boot 4`() {
		// The starter is published for the Boot 3 line (see docs/agent-log.md #2): a context that starts is NOT proof the auto-configuration engaged; it could have backed off silently, leaving @CircuitBreaker annotations as inert no-ops. This assertion turns "probably works" into a build-breaking check
		assertTrue(
			context.getBeanNamesForType(CircuitBreakerRegistry::class.java).isNotEmpty(),
			"CircuitBreakerRegistry bean missing: Resilience4j auto-configuration did not bind",
		)
	}
}
