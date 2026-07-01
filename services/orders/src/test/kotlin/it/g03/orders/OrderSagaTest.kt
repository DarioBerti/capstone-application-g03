package it.g03.orders

import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saga orchestration tests: real Spring context + Testcontainers Postgres + the REAL state machine/outbox.
 * The saga is reply-driven now — there are no sibling HTTP gateways to fake; the test drives the reply
 * dispatchers ([OrderSaga.onReservationResult] etc.) and asserts, at each step, BOTH the order's status AND
 * that the correct next-command row was appended to the outbox. It proves the branching, the compensations,
 * and terminal-state idempotency (a redelivered reply on an advanced/terminal order appends no duplicate command).
 */
@SpringBootTest( // boot the full Spring app context to test saga + state machine + outbox wiring
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",      // no broker in tests
        "app.kafka-listeners-enabled=false", // saga is driven directly, not via Kafka
        "app.timeout-sweeper.enabled=false", // no timeout sweeper racing the assertions
    ],
)
@Import(TestcontainersConfiguration::class)
class OrderSagaTest {

    private val trip = "FR9592-20260629"

    @Autowired lateinit var saga: OrderSaga
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var outbox: OutboxEventRepository

    @BeforeEach // start from a known state
    fun cleanSlate() {
        outbox.deleteAll()
        orders.deleteAll()
    }

    private fun placePending(qty: Int = 1) =
        orderService.placeOrder(trip, qty, buyerSub = "buyer-1", buyerEmail = "buyer@example.org")

    private fun statusOf(id: UUID) = orders.findById(id).orElseThrow().status
    private fun rejectionOf(id: UUID) = orders.findById(id).orElseThrow().rejectionReason
    private fun count(topic: String) = outbox.findAll().count { it.topic == topic }

    @Test
    fun `placeOrder persists the order AND its order-placed event in one transaction`() {
        val order = placePending(qty = 2)

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(2_000, order.amountCents)
        val events = outbox.findAll()
        assertEquals(1, events.size)
        assertEquals(Topics.ORDER_PLACED, events[0].topic)
        assertEquals(order.id.toString(), events[0].aggregateId)
    }

    @Test
    fun `placeOrder with the same idempotency key replays the existing order`() {
        val first = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org", idempotencyKey = "key-1")
        val replay = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org", idempotencyKey = "key-1")

        assertEquals(first.id, replay.id, "same key must not create a second order")
        assertEquals(1, orders.count().toInt())
        assertEquals(1, count(Topics.ORDER_PLACED), "no duplicate order-placed event")
    }

    @Test
    fun `start emits reserve-seats and the order stays PENDING (awaiting reservation-result)`() {
        val order = placePending()

        saga.start(order.id)

        assertEquals(OrderStatus.PENDING, statusOf(order.id))
        assertEquals(1, count(Topics.RESERVE_SEATS))
    }

    @Test
    fun `happy path walks reserve - authorize - confirm - capture to CONFIRMED with terminal events`() {
        val order = placePending()

        saga.start(order.id)
        assertEquals(OrderStatus.PENDING, statusOf(order.id))
        assertEquals(1, count(Topics.RESERVE_SEATS))

        saga.onReservationResult(order.id, "RESERVED")
        assertEquals(OrderStatus.RESERVED, statusOf(order.id))
        assertEquals(1, count(Topics.AUTHORIZE_PAYMENT))

        saga.onPaymentResult(order.id, "AUTHORIZED")
        assertEquals(OrderStatus.AUTHORIZED, statusOf(order.id))
        assertEquals(1, count(Topics.CONFIRM_SEATS))

        saga.onSeatsConfirmed(order.id, "CONFIRMED")
        assertEquals(OrderStatus.AUTHORIZED, statusOf(order.id), "stays AUTHORIZED awaiting payment-captured")
        assertEquals(1, count(Topics.CAPTURE_PAYMENT))

        saga.onPaymentCaptured(order.id)
        assertEquals(OrderStatus.CONFIRMED, statusOf(order.id))
        assertEquals(1, count(Topics.ORDER_CONFIRMED))
        assertEquals(1, count(Topics.NOTIFICATION_REQUESTED))
    }

    @Test
    fun `sold out reservation rejects with SOLD_OUT and emits no payment command`() {
        val order = placePending()
        saga.start(order.id)

        saga.onReservationResult(order.id, "SOLD_OUT")

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(RejectionReason.SOLD_OUT, rejectionOf(order.id))
        assertEquals(0, count(Topics.AUTHORIZE_PAYMENT), "no payment for a failed reservation")
        assertEquals(0, count(Topics.RELEASE_SEATS), "nothing to compensate")
        assertEquals(1, count(Topics.ORDER_REJECTED))
    }

    @Test
    @Tag("chaos-02")
    fun `declined payment releases seats (compensation) then rejects with PAYMENT_DECLINED`() {
        val order = placePending()
        saga.start(order.id)
        saga.onReservationResult(order.id, "RESERVED")

        saga.onPaymentResult(order.id, "DECLINED")

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(RejectionReason.PAYMENT_DECLINED, rejectionOf(order.id))
        assertEquals(1, count(Topics.RELEASE_SEATS), "the hold must be given back")
        assertEquals(1, count(Topics.ORDER_REJECTED))
    }

    @Test
    @Tag("chaos-02")
    fun `expired seats cancels the authorization (compensation) then rejects with RESERVATION_EXPIRED`() {
        val order = placePending()
        saga.start(order.id)
        saga.onReservationResult(order.id, "RESERVED")
        saga.onPaymentResult(order.id, "AUTHORIZED")

        saga.onSeatsConfirmed(order.id, "EXPIRED")

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(RejectionReason.RESERVATION_EXPIRED, rejectionOf(order.id))
        assertEquals(1, count(Topics.CANCEL_AUTHORIZATION), "the funds hold must be cancelled (no capture, no refund)")
        assertEquals(0, count(Topics.CAPTURE_PAYMENT), "an expired hold is never captured")
        assertEquals(1, count(Topics.ORDER_REJECTED))
    }

    @Test
    fun `a redelivered reservation-result on an advanced order is a no-op (no duplicate authorize-payment)`() {
        val order = placePending()
        saga.start(order.id)
        saga.onReservationResult(order.id, "RESERVED") // -> RESERVED, 1 authorize-payment
        saga.onReservationResult(order.id, "RESERVED") // redelivery; guard PENDING fails -> no-op

        assertEquals(OrderStatus.RESERVED, statusOf(order.id))
        assertEquals(1, count(Topics.AUTHORIZE_PAYMENT), "no duplicate command on redelivery")
    }

    @Test
    fun `a reply on a terminal order is a no-op - no state flip, no command rows`() {
        val order = placePending()
        saga.start(order.id)
        saga.onReservationResult(order.id, "SOLD_OUT") // -> REJECTED
        val rejectedBefore = count(Topics.ORDER_REJECTED)

        // late/duplicate replies on a now-terminal order: every guard fails -> no-op
        saga.onReservationResult(order.id, "RESERVED")
        saga.onPaymentResult(order.id, "AUTHORIZED")
        saga.onSeatsConfirmed(order.id, "CONFIRMED")
        saga.onPaymentCaptured(order.id)

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(rejectedBefore, count(Topics.ORDER_REJECTED), "no duplicate terminal event")
        assertEquals(0, count(Topics.AUTHORIZE_PAYMENT))
        assertEquals(0, count(Topics.CONFIRM_SEATS))
        assertEquals(0, count(Topics.CAPTURE_PAYMENT))
        assertEquals(0, count(Topics.ORDER_CONFIRMED))
    }

    @Test
    fun `payment-captured before AUTHORIZED is a no-op - never confirms early`() {
        val order = placePending()
        saga.start(order.id)
        saga.onReservationResult(order.id, "RESERVED") // RESERVED, not yet AUTHORIZED

        saga.onPaymentCaptured(order.id) // out-of-order: guard AUTHORIZED fails

        assertEquals(OrderStatus.RESERVED, statusOf(order.id), "must not confirm before AUTHORIZED")
        assertEquals(0, count(Topics.ORDER_CONFIRMED))
        assertTrue(rejectionOf(order.id) == null)
    }
}
