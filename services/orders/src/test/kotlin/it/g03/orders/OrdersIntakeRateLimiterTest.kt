package it.g03.orders

import it.g03.orders.controller.GlobalExceptionHandler
import it.g03.orders.controller.IntakeOverloadedException
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
import kotlin.test.assertTrue

/**
 * Proves the load-shedding BEHAVIOUR (not just that a bean exists): once the intake budget for the
 * window is spent, the intake refuses with a controlled 429 + Retry-After, and the shed is counted.
 *
 * Style note: like every other test here (OrderSagaTest) we drive the controller bean directly rather than
 * through MockMvc — passing a Jwt principal explicitly — and map the controlled-refusal signal through the real
 * GlobalExceptionHandler to assert the exact 429 + Retry-After. Determinism without wall-clock waits: budget
 * shrunk to 1 permit over a long (1 min) window via test properties, so the SECOND call is guaranteed to shed.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",       // no broker in tests
        "app.kafka-listeners-enabled=false",  // intake is driven via the controller, not Kafka
        "app.outbox.enabled=false",           // no relay racing the assertions
        "app.recovery.enabled=false",         // no recovery sweep racing the assertions
        // shrink the intake budget so the 2nd call deterministically sheds (no timing dependency)
        "resilience4j.ratelimiter.instances.orders-intake.limit-for-period=1",
        "resilience4j.ratelimiter.instances.orders-intake.limit-refresh-period=PT1M",
        "resilience4j.ratelimiter.instances.orders-intake.timeout-duration=PT0S",
    ],
)
@Import(TestcontainersConfiguration::class)
@Tag("chaos-03") // anticipates chaos #3 (node-disruption): under reduced capacity the intake sheds 429 instead of collapsing
class OrdersIntakeRateLimiterTest {

    // Injects the controller under test (driven directly, as in OrderSagaTest)
    @Autowired lateinit var controller: OrdersController
    // Injects the meter registry to assert the shedding metric actually incremented
    @Autowired lateinit var meterRegistry: MeterRegistry

    // The exception->HTTP mapping under test lives in the shared advice, not the controller.
    private val exceptionHandler = GlobalExceptionHandler()

    private fun sheds(): Double = meterRegistry.find("orders_intake_shed_total").counter()?.count() ?: 0.0

    // A minimal validated principal — the controller reads sub/email off it (no security filter when called directly).
    private fun buyerJwt(): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject("kc-sub-1")
            .claim("email", "buyer@example.org")
            .build()

    // Verifies #3 end-to-end at the controller: within budget -> 202; over budget -> shed (counted) -> 429 + Retry-After
    @Test
    fun `intake sheds with 429 + Retry-After once the per-window budget is exhausted`() {
        // 1st request consumes the only permit -> accepted (the saga proceeds asynchronously)
        val accepted = controller.place(PlaceOrderRequest("FR9592-20260629", 1), buyerJwt(), null)
        assertEquals(HttpStatus.ACCEPTED, accepted.statusCode)

        val shedBefore = sheds()

        // 2nd request is over budget -> controlled refusal signalled by IntakeOverloadedException
        val overloaded = assertFailsWith<IntakeOverloadedException> {
            controller.place(PlaceOrderRequest("FR9592-20260629", 1), buyerJwt(), null)
        }
        assertTrue(overloaded.retryAfterSeconds >= 1, "Retry-After must be a positive, window-derived hint")

        // the policy is observable: exactly one shed counted (Pillar D, low-cardinality counter)
        assertEquals(shedBefore + 1.0, sheds(), "the shed must increment orders_intake_shed_total")

        // the advice maps that signal to HTTP 429 + a Retry-After header equal to the hint
        val response = exceptionHandler.overloaded(overloaded)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals(
            overloaded.retryAfterSeconds.toString(),
            response.headers.getFirst(HttpHeaders.RETRY_AFTER),
            "429 must carry Retry-After so the client knows when to come back",
        )
    }
}
