package it.g03.orders

import io.github.resilience4j.retry.RetryRegistry
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FUNCTIONAL proof of the Payments retry (Fase 2 #2): a transient 5xx is retried up to max-attempts and then succeeds;
 * a permanent 4xx is NOT retried (it is not in retry-exceptions). Drives the configured `payments` retry instance
 * directly so the test is deterministic. Mirrors PaymentsCircuitBreakerTest's "drive the policy, don't mock it" style.
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
@Tag("chaos-01") // anticipates chaos #1 (payments-latency): a transient blip is absorbed by retry, never trips the breaker
@Import(TestcontainersConfiguration::class)
class PaymentsRetryTest {

    @Autowired private lateinit var retries: RetryRegistry

    @Test
    fun `a transient 5xx is retried and then succeeds within max-attempts`() {
        val retry = retries.retry("payments")
        val attempts = AtomicInteger(0)

        val result = retry.executeCallable(
            Callable {
                // first two attempts fault with a 5xx (a retry-exception), the third returns cleanly
                if (attempts.incrementAndGet() < 3) throw HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)
                "AUTHORIZED"
            },
        )

        assertEquals("AUTHORIZED", result)
        assertEquals(3, attempts.get(), "two 5xx faults are retried, the third attempt succeeds (max-attempts=3)")
    }

    @Test
    fun `a permanent 4xx is NOT retried`() {
        val retry = retries.retry("payments")
        val attempts = AtomicInteger(0)

        assertFailsWith<HttpClientErrorException> {
            retry.executeCallable(
                Callable<String> {
                    attempts.incrementAndGet()
                    throw HttpClientErrorException(HttpStatus.BAD_REQUEST)
                },
            )
        }
        assertEquals(1, attempts.get(), "a 4xx is not in retry-exceptions -> exactly one attempt, no retry")
    }
}
