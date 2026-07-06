package it.g03.orders

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import it.g03.orders.entities.LegDirection
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import it.g03.orders.service.OrderTimeoutSweeper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fase 4 saga-completion SLI + cross-Kafka tracing, app side.
 *
 * The intake SLO only sees the 202; these are the signals that see the rest: `orders_confirmed_total` /
 * `orders_rejected_total{reason}` count TERMINAL outcomes (the alert ratio is terminal/placed — a rejection
 * is a completed saga, not a failure), `orders_overdue_nonterminal` (sweeper gauge) is the direct stuck-order
 * symptom, and the outbox row's `traceparent` column is how a trace crosses the Debezium hop (the app never
 * produces to Kafka itself). Counters are asserted as DELTAS: the meters are cumulative across tests sharing
 * this cached Spring context.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false", // sweepOnce() is driven directly
        "app.timeout-sweeper.deadline=PT30S",
        "app.checkout.expiry-sweeper.enabled=false",
        "management.otlp.tracing.export.enabled=false", // real Tracer, no OTLP endpoint in tests
    ],
)
@Import(TestcontainersConfiguration::class)
class SagaCompletionMetricsTest {

    private val trip = "FR9592-20260629"
    private val buyer = "buyer-1"
    private val email = "buyer@example.org"

    @Autowired lateinit var saga: OrderSaga
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var sweeper: OrderTimeoutSweeper
    @Autowired lateinit var meterRegistry: MeterRegistry
    @Autowired lateinit var tracer: Tracer

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        legs.deleteAll() // FK -> orders: delete children first
        orders.deleteAll()
    }

    // A HELD one-way booking with a single PENDING outbound leg (the reserve-seats row is the outbox write the
    // tracing tests inspect). The reservation-result is simulated per test by driving the saga directly.
    private fun place() = orderService.createDraft(buyer, email, null).order
        .also { orderService.addLeg(it.id, buyer, LegDirection.OUTBOUND, trip, 1, TestPriceQuotes.sign(trip)) }

    private fun counter(name: String, vararg tags: String) =
        meterRegistry.find(name).tags(*tags).counter()?.count() ?: 0.0

    private fun overdueGauge() = meterRegistry.get("orders_overdue_nonterminal").gauge().value()

    private fun driveToConfirmed(id: UUID) {
        saga.onReservationResult(id, trip, "RESERVED")
        orderService.pay(id, buyer)
        saga.onPaymentResult(id, "AUTHORIZED")
        saga.onSeatsConfirmed(id, trip, "CONFIRMED")
        saga.onPaymentCaptured(id)
    }

    private fun age(id: UUID, secondsAgo: Long) {
        val order = orders.findById(id).orElseThrow()
        order.updatedAt = Instant.now().minusSeconds(secondsAgo)
        orders.saveAndFlush(order)
    }

    @Test
    fun `a confirmed order increments orders_confirmed_total exactly once, replay included`() {
        val before = counter("orders_confirmed_total")
        val order = place()

        driveToConfirmed(order.id)
        saga.onPaymentCaptured(order.id) // redelivered terminal reply: guard makes it a no-op

        assertEquals(before + 1.0, counter("orders_confirmed_total"), "one confirmation = one count, replay-safe")
    }

    @Test
    fun `each rejection reason increments orders_rejected_total under its own reason tag`() {
        val declinedBefore = counter("orders_rejected_total", "reason", "PAYMENT_DECLINED")
        val expiredBefore = counter("orders_rejected_total", "reason", "RESERVATION_EXPIRED")

        val declined = place()
        saga.onReservationResult(declined.id, trip, "RESERVED")
        orderService.pay(declined.id, buyer)
        saga.onPaymentResult(declined.id, "DECLINED")

        val expired = place()
        saga.onReservationResult(expired.id, trip, "RESERVED")
        orderService.pay(expired.id, buyer)
        saga.onPaymentResult(expired.id, "AUTHORIZED")
        saga.onSeatsConfirmed(expired.id, trip, "EXPIRED")

        assertEquals(declinedBefore + 1.0, counter("orders_rejected_total", "reason", "PAYMENT_DECLINED"))
        assertEquals(expiredBefore + 1.0, counter("orders_rejected_total", "reason", "RESERVATION_EXPIRED"))
    }

    @Test
    fun `the overdue gauge tracks stuck orders found by the last sweep and returns to zero`() {
        val order = place()
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer) // PAYING: a resumable post-pay state the sweeper re-drives
        age(order.id, secondsAgo = 60)

        sweeper.sweepOnce()
        assertEquals(1.0, overdueGauge(), "one order past the deadline must be visible on the gauge")

        // the re-drive bumped updatedAt, so the next sweep finds nothing and the gauge self-clears
        sweeper.sweepOnce()
        assertEquals(0.0, overdueGauge())
    }

    @Test
    fun `an outbox row written inside an active span carries its W3C traceparent`() {
        val span = tracer.nextSpan().name("test-intake").start()
        val order = try {
            tracer.withSpan(span).use { place() }
        } finally {
            span.end()
        }

        val row = outbox.findAll().single { it.aggregateId == order.id.toString() }
        assertNotNull(row.traceparent, "the trace context must be persisted with the event (Debezium hop)")
        assertTrue(
            row.traceparent!!.contains(span.context().traceId()),
            "traceparent '${row.traceparent}' must carry the active traceId ${span.context().traceId()}",
        )
    }

    @Test
    fun `an outbox row written with no active span has a null traceparent (fresh trace downstream)`() {
        val order = place()

        val row = outbox.findAll().single { it.aggregateId == order.id.toString() }
        assertNull(row.traceparent, "no active span (e.g. a sweeper re-drive) must not invent a context")
    }
}
