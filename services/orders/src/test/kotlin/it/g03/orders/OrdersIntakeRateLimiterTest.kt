package it.g03.orders

import io.github.resilience4j.ratelimiter.RequestNotPermitted
import it.g03.orders.controller.GlobalExceptionHandler
import it.g03.orders.controller.OrdersController
import it.g03.orders.dto.PlaceOrderRequest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the load-shedding BEHAVIOUR (not just that a bean exists): once the intake budget for the
 * window is spent, the @RateLimiter aspect refuses the call, and the GlobalExceptionHandler maps that
 * to a controlled 429 + Retry-After while counting the shed.
 *
 * The autowired controller IS the AOP proxy, so calling place() directly exercises the @RateLimiter aspect
 * (this also proves the annotation weaves on Spring Boot 4). The exception->HTTP mapping lives in the shared
 * advice, so we drive it through the real GlobalExceptionHandler. Determinism without wall-clock waits: budget
 * shrunk to 1 permit over a long (1 min) window via test properties, so the SECOND call is guaranteed to shed.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",       // no broker in tests
        "app.kafka-listeners-enabled=false",  // intake is driven via the controller, not Kafka
        "app.timeout-sweeper.enabled=false",  // no timeout sweeper racing the assertions
        // shrink the intake budget so the 2nd call deterministically sheds (no timing dependency)
        "resilience4j.ratelimiter.instances.orders-intake.limit-for-period=1",
        "resilience4j.ratelimiter.instances.orders-intake.limit-refresh-period=PT1M",
        "resilience4j.ratelimiter.instances.orders-intake.timeout-duration=PT0S",
    ],
)
@Import(TestcontainersConfiguration::class)
@Tag("chaos-03") // anticipates chaos #3 (node-disruption): under reduced capacity the intake sheds 429 instead of collapsing
class OrdersIntakeRateLimiterTest {

    // The proxied controller under test: a direct call goes through the @RateLimiter aspect.
    @Autowired lateinit var controller: OrdersController
    // The shared advice that maps the rejection to 429 + Retry-After and counts the shed.
    @Autowired lateinit var exceptionHandler: GlobalExceptionHandler
    // Injects the meter registry to assert the shedding metric actually incremented.
    @Autowired lateinit var meterRegistry: MeterRegistry

    private fun sheds(): Double = meterRegistry.find("orders_intake_shed_total").counter()?.count() ?: 0.0

    // A minimal validated principal — the controller reads sub/email off it (no security filter when called directly).
    private fun buyerJwt(): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject("kc-sub-1")
            .claim("email", "buyer@example.org")
            .build()

    // Verifies #3 end-to-end at the controller: within budget -> 202; over budget -> aspect rejects -> 429 + Retry-After (counted)
    @Test
    fun `intake sheds with 429 + Retry-After once the per-window budget is exhausted`() {
        // 1st request consumes the only permit -> accepted (the saga proceeds asynchronously)
        val accepted = controller.place(PlaceOrderRequest("FR9592-20260629", 1), buyerJwt(), null)
        assertEquals(HttpStatus.ACCEPTED, accepted.statusCode)

        val shedBefore = sheds()

        // 2nd request is over budget -> the @RateLimiter aspect refuses it with RequestNotPermitted
        val rejected = assertFailsWith<RequestNotPermitted> {
            controller.place(PlaceOrderRequest("FR9592-20260629", 1), buyerJwt(), null)
        }

        // the advice maps that rejection to HTTP 429 + a positive, window-derived Retry-After, and counts the shed
        val response = exceptionHandler.overloaded(rejected)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)

        val retryAfter = response.headers.getFirst(HttpHeaders.RETRY_AFTER)
        assertNotNull(retryAfter, "429 must carry Retry-After so the client knows when to come back")
        assertTrue(retryAfter.toLong() >= 1, "Retry-After must be a positive, window-derived hint")

        // the policy is observable: exactly one shed counted (Pillar D, low-cardinality counter)
        assertEquals(shedBefore + 1.0, sheds(), "the shed must increment orders_intake_shed_total")
    }
}
