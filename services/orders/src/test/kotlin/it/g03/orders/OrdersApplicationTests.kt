package it.g03.orders

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import kotlin.test.assertTrue

// Minimal smoke test to verify the Spring context starts (against Testcontainers PostgreSQL, real Flyway).
@SpringBootTest(
	properties = [
		"spring.kafka.bootstrap-servers=localhost:9092", // dummy broker address (no real connection is made)
		"events.publish.enabled=false",                   // no broker in tests
		"app.kafka-listeners-enabled=false",              // keep the listener off (no broker)
		"app.timeout-sweeper.enabled=false",              // no timeout sweeper racing the assertions
	],
)
@Import(TestcontainersConfiguration::class)
class OrdersApplicationTests {

	// Injects the Spring context so the test can assert on the registered beans
	@Autowired
	private lateinit var context: ApplicationContext

	@Test
	// no assertions needed (successful startup is the assertion)
	fun contextLoads() {
	}

	// Verifies the Resilience4j auto-configuration actually bound the RateLimiterRegistry bean (the breaker/retry/
	// bulkhead registries are gone with the sync gateways; only the @RateLimiter intake shed remains).
	@Test
	fun `resilience4j auto-configuration actually binds on Spring Boot 4`() {
		// The starter is published for the Boot 3 line (see docs/agent-log.md #2): a context that starts is NOT proof the auto-configuration engaged; it could have backed off silently, leaving @RateLimiter annotations as inert no-ops. This assertion turns "probably works" into a build-breaking check
		assertTrue(
			context.getBeanNamesForType(RateLimiterRegistry::class.java).isNotEmpty(),
			"RateLimiterRegistry bean missing: Resilience4j auto-configuration did not bind",
		)
	}
}
