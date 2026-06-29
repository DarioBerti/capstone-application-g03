package it.g03.orders

import it.g03.orders.gateway.HttpPaymentsGateway
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * END-TO-END proof that the PROGRAMMATIC circuit breaker in [HttpPaymentsGateway] actually works on Spring Boot 4 —
 * this closes the open concern in docs/agent-log.md #2 (whether the R4j integration engages on Boot 4). With Payments
 * pointed at a refused port, the REAL gateway bean's authorize() returns the "UNAVAILABLE" fallback on every call and
 * the breaker opens. No fakes here: the production HttpPaymentsGateway is exercised
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
        "app.payments-base-url=http://localhost:1", // unused/privileged port -> every call fails fast (connection refused)
    ],
)
@Tag("chaos-01") // anticipates chaos #1 (payments-latency): real gateway opens the breaker + fast-fails to UNAVAILABLE
@Import(TestcontainersConfiguration::class)
class PaymentsGatewayBreakerIntegrationTest {

    @Autowired
    private lateinit var gateway: HttpPaymentsGateway

    @Autowired
    private lateinit var registry: CircuitBreakerRegistry

    @BeforeEach
    fun reset() {
        registry.circuitBreaker("payments").reset()
    }

    @AfterEach
    fun leaveClosed() {
        registry.circuitBreaker("payments").reset()
    }

    @Test
    fun `unreachable Payments yields UNAVAILABLE fallback and opens the breaker`() {
        // drive minimum-number-of-calls (5) failing calls: each returns the fallback, never throws
        repeat(5) {
            val result = gateway.authorize("order-$it", 1_000)
            assertEquals("UNAVAILABLE", result.status, "a refused Payments must map to the fallback, not throw")
        }

        assertEquals(
            CircuitBreaker.State.OPEN,
            registry.circuitBreaker("payments").state,
            "five transport faults must open the programmatic breaker",
        )

        // once OPEN the call is short-circuited and STILL returns the fallback (no exception escapes the gateway)
        assertEquals("UNAVAILABLE", gateway.authorize("order-after-open", 1_000).status)
        // prove that post-open call was SHORT-CIRCUITED (Payments not re-dispatched) rather than a fresh connection-refused mapped to the same string
        assertTrue(
            registry.circuitBreaker("payments").metrics.numberOfNotPermittedCalls >= 1,
            "an OPEN breaker must reject calls (numberOfNotPermittedCalls > 0), proving fast-fail",
        )
    }
}
