package it.g03.orders

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.retry.RetryRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Fase 2 #2 Retry + Bulkhead instances to application.yml (NOT Resilience4j defaults), exactly as
 * PaymentsCircuitBreakerTest pins the breaker. Every assertion is on a NON-default value, so a green test proves the
 * yml binding actually engaged (asserting a value that equals the default would prove nothing).
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
@Import(TestcontainersConfiguration::class)
class RetryBulkheadConfigTest {

    @Autowired private lateinit var retries: RetryRegistry
    @Autowired private lateinit var bulkheads: BulkheadRegistry

    @Test
    fun `payments and inventory retry instances are configured from application_yml`() {
        for (name in listOf("payments", "inventory")) {
            val cfg = retries.retry(name).retryConfig
            assertEquals(3, cfg.maxAttempts, "$name retry max-attempts")
            // NON-default proof: the default predicate retries EVERY throwable. Ours retries ONLY transport faults
            // (the configured retry-exceptions whitelist) — never a 4xx / business outcome.
            val retryable = cfg.exceptionPredicate
            assertTrue(retryable.test(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)), "$name must retry a 5xx")
            assertFalse(retryable.test(HttpClientErrorException(HttpStatus.BAD_REQUEST)), "$name must NOT retry a 4xx")
            assertFalse(retryable.test(RuntimeException("boom")), "$name must NOT retry an arbitrary exception (whitelist binding engaged)")
        }
    }

    @Test
    fun `payments and inventory bulkheads have the configured non-default caps`() {
        // default max-concurrent-calls is 25; ours are 8 / 16 -> asserting them proves the yml binding engaged
        assertEquals(8, bulkheads.bulkhead("payments").bulkheadConfig.maxConcurrentCalls, "payments bulkhead cap")
        assertEquals(16, bulkheads.bulkhead("inventory").bulkheadConfig.maxConcurrentCalls, "inventory bulkhead cap")
    }
}
