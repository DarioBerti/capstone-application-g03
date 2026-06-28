package it.g03.orders

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FUNCTIONAL proof of the Payments circuit breaker (Pillar C): not "the bean exists" (OrdersApplicationTests already
 * covers that), but the actual configured `payments` instance opening under faults, short-circuiting while OPEN, and
 * recovering through HALF_OPEN. The breaker state machine is driven directly so the test is deterministic — no
 * wall-clock wait on wait-duration-in-open-state
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
    ],
)
class PaymentsCircuitBreakerTest {

    @Autowired
    private lateinit var registry: CircuitBreakerRegistry

    // a callable that always faults (stands in for a 5xx / timeout from Payments); typed String so executeCallable<T> infers cleanly
    private fun boom(): String = throw RuntimeException("payments 5xx / timeout")

    @BeforeEach
    fun reset() {
        // start from a known CLOSED state regardless of context caching or sibling-test ordering
        registry.circuitBreaker("payments").reset()
    }

    @AfterEach
    fun leaveClosed() {
        // return the shared singleton breaker to CLOSED so any sibling test sharing this cached context stays order-independent
        registry.circuitBreaker("payments").reset()
    }

    @Test
    fun `payments instance is configured from application_yml, not Resilience4j defaults`() {
        val cfg = registry.circuitBreaker("payments").circuitBreakerConfig
        // Assert the NON-default values — these prove the yml binding engaged. (Do NOT assert failureRateThreshold:
        // 50% IS the Resilience4j default, so it would prove nothing.)
        assertEquals(10, cfg.slidingWindowSize)
        assertEquals(5, cfg.minimumNumberOfCalls)
        assertEquals(2, cfg.permittedNumberOfCallsInHalfOpenState)
        assertEquals(Duration.ofSeconds(2), cfg.slowCallDurationThreshold)
        assertEquals(50f, cfg.slowCallRateThreshold)
    }

    @Tag("chaos-01") // anticipates chaos #1 (payments-latency): sustained slow calls open the breaker, fast-fail
    @Test
    fun `breaker opens after the configured failures, short-circuits, then recovers via half-open`() {
        val cb = registry.circuitBreaker("payments")

        // minimum-number-of-calls = 5, failure-rate-threshold = 50%: five faults take the window to 100% and trip it
        repeat(5) { runCatching { cb.executeCallable { boom() } } }
        assertEquals(CircuitBreaker.State.OPEN, cb.state, "five transport faults must open the breaker")

        // While OPEN the call is short-circuited (Payments is never hit): this is what the gateway maps to UNAVAILABLE
        assertFailsWith<CallNotPermittedException> { cb.executeCallable { boom() } }

        // Probe phase: HALF_OPEN admits permitted-number-of-calls-in-half-open-state (= 2) trial calls
        cb.transitionToHalfOpenState()
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state)

        // Two successful trials (Payments is back) close the breaker again
        repeat(2) { cb.executeCallable { "AUTHORIZED" } }
        assertEquals(CircuitBreaker.State.CLOSED, cb.state, "successful trials must close the breaker")
    }
}
