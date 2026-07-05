package it.g03.orders

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.micrometer.core.instrument.MeterRegistry
import it.g03.orders.controller.GlobalExceptionHandler
import it.g03.orders.repository.OrderRepository
import it.g03.orders.service.OrderService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertTrue

/**
 * §7.3 regression guard — "low cardinality": *"high-cardinality labels destroy metrics
 * systems"*. The convention (only `outcome`/`type`/`reason`/`dependency`/`status` labels, never `orderId`/
 * `traceId`/`userId`) is already followed; this test makes a future violation BUILD-BREAKING instead of a
 * silent prod cardinality explosion.
 *
 * It triggers the two surviving custom-metric families deterministically (no flaky async saga): the intake
 * shed (`orders_intake_shed_total`) and the draft-creation counter (`orders_created_total`), then asserts EVERY
 * registered `orders_*` meter carries only allowed tag keys and no id-shaped tag value. (The gateway's
 * `orders_payment_failures_total{outcome}` is gone with the sync gateways.)
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false",
        // Shrink the intake budget so a shed is deterministic. NB: limit-for-period=2 (NOT 1) is deliberately
        // DISTINCT from OrdersIntakeRateLimiterTest's property set so Spring gives this class its OWN cached
        // context — otherwise the two would share one context and this test consuming the rate-limiter permit
        // would leak into the sibling's "first call is ACCEPTED" assertion (shared-singleton leak).
        "resilience4j.ratelimiter.instances.orders-intake.limit-for-period=2",
        "resilience4j.ratelimiter.instances.orders-intake.limit-refresh-period=PT1M",
        "resilience4j.ratelimiter.instances.orders-intake.timeout-duration=PT0S",
    ],
)
@Import(TestcontainersConfiguration::class)
class MetricCardinalityGuardTest {

    @Autowired private lateinit var meterRegistry: MeterRegistry
    @Autowired private lateinit var rateLimiters: RateLimiterRegistry
    @Autowired private lateinit var handler: GlobalExceptionHandler
    @Autowired private lateinit var orderService: OrderService
    @Autowired private lateinit var orders: OrderRepository

    // The low-cardinality label vocabulary the team committed to (FASE2 §7.3). Anything else is a regression.
    private val allowedTagKeys = setOf("outcome", "type", "reason", "dependency", "status")

    // Forbidden, explicitly named in the spec: these blow up cardinality (one series per order/user/trace)
    private val forbiddenTagKeys = setOf("orderid", "traceid", "spanid", "userid", "sessionid")

    // id-shaped VALUES (UUID or >=16-hex) catch a high-cardinality value smuggled in under an innocent key name
    private val idShaped = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*|^[0-9a-fA-F]{16,}$")

    @BeforeEach
    fun cleanSlate() {
        orders.deleteAll()
    }

    @Test
    fun `every custom orders metric stays low-cardinality`() {
        // 1) register orders_intake_shed_total via a real shed: exhaust the 2-permit budget, then map the
        //    rejection through the handler (the same path a 429 takes), which increments the shed counter.
        val limiter = rateLimiters.rateLimiter("orders-intake")
        assertTrue(limiter.acquirePermission())
        assertTrue(limiter.acquirePermission())
        assertTrue(!limiter.acquirePermission(), "3rd call must be over the 2-permit budget")
        handler.overloaded(RequestNotPermitted.createRequestNotPermitted(limiter))

        // 2) register orders_created_total via a real draft creation
        orderService.createDraft(buyerSub = "buyer-1", buyerEmail = "buyer@example.org", idempotencyKey = null)

        // sanity: the two custom families we just exercised are actually present (the scan below is non-trivial)
        val custom = meterRegistry.meters.filter { it.id.name.startsWith("orders_") }
        val names = custom.map { it.id.name }.toSet()
        assertTrue("orders_intake_shed_total" in names, "expected the shed counter to be registered")
        assertTrue("orders_created_total" in names, "expected the draft-creation counter to be registered")

        // 3) the guard: no custom orders_* meter may carry a high-cardinality label
        custom.forEach { meter ->
            meter.id.tags.forEach { tag ->
                val key = tag.key.lowercase()
                assertTrue(
                    key !in forbiddenTagKeys,
                    "metric ${meter.id.name} carries forbidden high-cardinality label '${tag.key}'",
                )
                assertTrue(
                    tag.key in allowedTagKeys,
                    "metric ${meter.id.name} has unexpected label '${tag.key}' — allowed: $allowedTagKeys",
                )
                assertTrue(
                    !idShaped.matches(tag.value),
                    "metric ${meter.id.name} label '${tag.key}' has id-shaped value '${tag.value}' (cardinality risk)",
                )
            }
        }
    }
}
