package it.g03.orders

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.OrderStatus
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Proves the fan-in and the terminal transition are exactly-once under real concurrency:
 * - concurrent per-leg seats-confirmed replies (the round-trip fan-in) emit capture-payment ONCE — the
 *   pessimistic order lock serializes them so exactly one confirm sees "all legs confirmed";
 * - concurrent payment-captured replies (two pods / a re-drive racing the reply) emit order-confirmed ONCE —
 *   the @Version optimistic lock rolls back the losers instead of appending duplicate terminal events.
 * Mirrors InventoryConcurrencyTest's multi-thread style.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false",
        "app.checkout.expiry-sweeper.enabled=false",
    ],
)
@Tag("chaos-03") // anticipates chaos #3 (node-disruption): concurrent drivers of a transition emit exactly one event
@Import(TestcontainersConfiguration::class)
class OrderVersionConcurrencyTest {

    private val trip = "FR9592-20260629"
    private val tripReturn = "FR9593-20260630"
    private val buyer = "buyer-1"

    @Autowired lateinit var saga: OrderSaga
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository
    @Autowired lateinit var outbox: OutboxEventRepository

    private val pool = Executors.newFixedThreadPool(8)

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        legs.deleteAll()
        orders.deleteAll()
    }

    @AfterEach
    fun shutdownPool() {
        pool.shutdownNow()
    }

    private fun countFor(id: String, topic: String) =
        outbox.findAll().count { it.topic == topic && it.aggregateId == id }

    @Test
    fun `concurrent seats-confirmed replies for a round-trip capture exactly once`() {
        val order = orderService.createDraft(buyer, "buyer@example.org", null).order
        orderService.addLeg(order.id, buyer, LegDirection.OUTBOUND, trip, 1)
        orderService.addLeg(order.id, buyer, LegDirection.RETURN, tripReturn, 1)
        saga.onReservationResult(order.id, trip, "RESERVED")
        saga.onReservationResult(order.id, tripReturn, "RESERVED")
        orderService.pay(order.id, buyer)
        saga.onPaymentResult(order.id, "AUTHORIZED") // AUTHORIZED, confirm-seats fanned out to both legs
        val id = order.id.toString()
        val start = CountDownLatch(1)

        // fire both legs' CONFIRMED (plus a redelivered duplicate of each) all at once
        val tasks = listOf(trip, tripReturn, trip, tripReturn).map { code ->
            pool.submit(Callable { start.await(); runCatching { saga.onSeatsConfirmed(order.id, code, "CONFIRMED") } })
        }
        start.countDown()
        tasks.forEach { it.get() }

        assertEquals(OrderStatus.AUTHORIZED, orders.findById(order.id).orElseThrow().status, "stays AUTHORIZED awaiting capture")
        assertEquals(1, countFor(id, Topics.CAPTURE_PAYMENT), "exactly one capture despite concurrent confirms")
    }

    @Test
    fun `concurrent payment-captured emits exactly one order-confirmed`() {
        val order = orderService.createDraft(buyer, "buyer@example.org", null).order
        orderService.addLeg(order.id, buyer, LegDirection.OUTBOUND, trip, 1)
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer)
        saga.onPaymentResult(order.id, "AUTHORIZED")
        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED") // capture-payment emitted; order still AUTHORIZED
        val id = order.id.toString()
        val start = CountDownLatch(1)

        val tasks = (1..8).map {
            pool.submit(Callable { start.await(); runCatching { saga.onPaymentCaptured(order.id) } })
        }
        start.countDown()
        tasks.forEach { it.get() }

        assertEquals(OrderStatus.CONFIRMED, orders.findById(order.id).orElseThrow().status)
        assertEquals(1, countFor(id, Topics.ORDER_CONFIRMED), "exactly one order-confirmed despite concurrent captures")
        assertEquals(1, countFor(id, Topics.NOTIFICATION_REQUESTED), "exactly one confirmation notification")
    }
}
