package it.g03.orders

import it.g03.orders.dto.OrderResponse
import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.LegStatus
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.events.AuthorizePaymentCommand
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderLegRepository
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
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Saga orchestration tests for the two-phase, human-gated booking: real Spring context + Testcontainers Postgres
 * + the REAL service/state-machine/outbox. The user-driven half (create -> add legs -> pay) runs through
 * [OrderService]; the reply-driven half runs through [OrderSaga] (reservation-result / payment-result /
 * seats-confirmed / payment-captured). Each step asserts BOTH the order/leg status AND that the correct next
 * command row was appended to the outbox. Covers the one-way and round-trip happy paths, the fan-out/fan-in
 * (confirm every leg, capture once), the compensations (declined, partial-expiry), the one-way fallback, and
 * idempotency (a redelivered/terminal reply appends no duplicate command).
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
@Import(TestcontainersConfiguration::class)
class OrderSagaTest {

    private val trip = "FR9592-20260629"        // outbound
    private val tripReturn = "FR9593-20260630"  // return (a distinct train)
    private val buyer = "buyer-1"
    private val email = "buyer@example.org"

    @Autowired lateinit var saga: OrderSaga
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        legs.deleteAll() // FK -> orders: delete children first
        orders.deleteAll()
    }

    private fun draft() = orderService.createDraft(buyer, email, null).order
    private fun addOutbound(id: UUID, code: String = trip, qty: Int = 1) = orderService.addLeg(id, buyer, LegDirection.OUTBOUND, code, qty)
    private fun addReturn(id: UUID, code: String = tripReturn, qty: Int = 1) = orderService.addLeg(id, buyer, LegDirection.RETURN, code, qty)
    private fun statusOf(id: UUID) = orders.findById(id).orElseThrow().status
    private fun rejectionOf(id: UUID) = orders.findById(id).orElseThrow().rejectionReason
    private fun legStatus(id: UUID, code: String) = legs.findByOrderIdAndTripCode(id, code)?.status
    private fun count(topic: String) = outbox.findAll().count { it.topic == topic }
    private fun statusEvents() = outbox.findAll().filter { it.topic == Topics.ORDER_STATUS_CHANGED }
        .map { objectMapper.readValue(it.payload, OrderResponse::class.java) }

    // --- draft + legs ------------------------------------------------------------------------------------------

    @Test
    fun `createDraft persists a DRAFT with no legs, no window, and no reserve-seats`() {
        val order = draft()

        assertEquals(OrderStatus.DRAFT, order.status)
        assertNull(order.expiresAt)
        assertEquals(0, legs.findByOrderId(order.id).size)
        assertEquals(0, count(Topics.RESERVE_SEATS))
    }

    @Test
    fun `createDraft with the same idempotency key replays the existing draft`() {
        val first = orderService.createDraft(buyer, email, "key-1").order
        val replay = orderService.createDraft(buyer, email, "key-1").order

        assertEquals(first.id, replay.id, "same key must not create a second order")
        assertEquals(1, orders.count().toInt())
    }

    @Test
    fun `first addLeg stamps the window, sets HELD, and emits reserve-seats`() {
        val order = draft()

        addOutbound(order.id, qty = 2)

        val reloaded = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.HELD, reloaded.status)
        assertNotNull(reloaded.expiresAt, "the hold window starts on the first leg")
        assertEquals(LegStatus.PENDING, legStatus(order.id, trip))
        assertEquals(1, count(Topics.RESERVE_SEATS))
    }

    @Test
    fun `adding a return leg emits a second reserve-seats without restarting the window`() {
        val order = draft()
        addOutbound(order.id)
        val windowAfterFirst = orders.findById(order.id).orElseThrow().expiresAt

        addReturn(order.id)

        assertEquals(windowAfterFirst, orders.findById(order.id).orElseThrow().expiresAt, "a second leg must not restart the window")
        assertEquals(2, count(Topics.RESERVE_SEATS))
    }

    @Test
    fun `adding a second leg of the same direction is rejected`() {
        val order = draft()
        addOutbound(order.id)

        assertFailsWith<IllegalStateException> { addOutbound(order.id, code = "FR0000-20260629") }
    }

    @Test
    fun `reservation-result maps to the correct leg by tripCode and a sold-out return keeps the booking HELD`() {
        val order = draft()
        addOutbound(order.id)
        addReturn(order.id)

        saga.onReservationResult(order.id, trip, "RESERVED")
        saga.onReservationResult(order.id, tripReturn, "SOLD_OUT")

        assertEquals(LegStatus.RESERVED, legStatus(order.id, trip))
        assertEquals(LegStatus.SOLD_OUT, legStatus(order.id, tripReturn))
        assertEquals(OrderStatus.HELD, statusOf(order.id), "a sold-out return is a one-way fallback, not a rejection")
    }

    // --- happy paths -------------------------------------------------------------------------------------------

    @Test
    fun `one-way booking walks reserve-pay-authorize-confirm-capture to CONFIRMED`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        assertEquals(LegStatus.RESERVED, legStatus(order.id, trip))

        orderService.pay(order.id, buyer)
        assertEquals(OrderStatus.PAYING, statusOf(order.id))
        assertEquals(1, count(Topics.AUTHORIZE_PAYMENT))

        saga.onPaymentResult(order.id, "AUTHORIZED")
        assertEquals(OrderStatus.AUTHORIZED, statusOf(order.id))
        assertEquals(1, count(Topics.CONFIRM_SEATS))

        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED")
        assertEquals(OrderStatus.AUTHORIZED, statusOf(order.id), "stays AUTHORIZED awaiting payment-captured")
        assertEquals(1, count(Topics.CAPTURE_PAYMENT))

        saga.onPaymentCaptured(order.id)
        assertEquals(OrderStatus.CONFIRMED, statusOf(order.id))
        assertEquals(1, count(Topics.ORDER_CONFIRMED))
        assertEquals(1, count(Topics.NOTIFICATION_REQUESTED))
    }

    @Test
    fun `round-trip fans out confirm-seats to both legs and captures exactly once`() {
        val order = draft()
        addOutbound(order.id)
        addReturn(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        saga.onReservationResult(order.id, tripReturn, "RESERVED")

        val view = orderService.pay(order.id, buyer)
        assertEquals(2_000, view.order.amountCents, "both legs are billed (2 × unit price)")

        saga.onPaymentResult(order.id, "AUTHORIZED")
        assertEquals(2, count(Topics.CONFIRM_SEATS), "confirm-seats fans out to every reserved leg")

        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED")
        assertEquals(0, count(Topics.CAPTURE_PAYMENT), "not captured until every leg is confirmed")
        saga.onSeatsConfirmed(order.id, tripReturn, "CONFIRMED")
        assertEquals(1, count(Topics.CAPTURE_PAYMENT), "captured exactly once, when the last leg confirms")

        saga.onPaymentCaptured(order.id)
        assertEquals(OrderStatus.CONFIRMED, statusOf(order.id))
    }

    @Test
    fun `pay emits authorize-payment carrying a line item per reserved leg (for the PSP to record the products)`() {
        val order = draft()
        addOutbound(order.id, qty = 2)
        addReturn(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        saga.onReservationResult(order.id, tripReturn, "RESERVED")

        orderService.pay(order.id, buyer)

        val command = outbox.findAll().first { it.topic == Topics.AUTHORIZE_PAYMENT }
            .let { objectMapper.readValue(it.payload, AuthorizePaymentCommand::class.java) }
        assertEquals(2, command.items.size, "one line item per reserved leg")
        assertEquals(command.amountCents, command.items.sumOf { it.quantity * it.unitAmountCents }, "items sum to the authorized amount")
        assertEquals(setOf("OUTBOUND $trip", "RETURN $tripReturn"), command.items.map { it.name }.toSet())
    }

    @Test
    fun `one-way fallback bills only the reserved outbound when the return is sold out`() {
        val order = draft()
        addOutbound(order.id)
        addReturn(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        saga.onReservationResult(order.id, tripReturn, "SOLD_OUT")

        val view = orderService.pay(order.id, buyer)
        assertEquals(OrderStatus.PAYING, statusOf(order.id))
        assertEquals(1_000, view.order.amountCents, "only the reserved outbound is billed")

        saga.onPaymentResult(order.id, "AUTHORIZED")
        assertEquals(1, count(Topics.CONFIRM_SEATS), "only the reserved leg is confirmed")
    }

    // --- compensations -----------------------------------------------------------------------------------------

    @Test
    @Tag("chaos-02")
    fun `declined payment releases the reserved legs then rejects with PAYMENT_DECLINED`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer)

        saga.onPaymentResult(order.id, "DECLINED")

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(RejectionReason.PAYMENT_DECLINED, rejectionOf(order.id))
        assertEquals(1, count(Topics.RELEASE_SEATS), "the hold must be given back")
        assertEquals(1, count(Topics.ORDER_REJECTED))
    }

    @Test
    @Tag("chaos-02")
    fun `one expired leg releases the confirmed sibling, cancels the authorization, and rejects RESERVATION_EXPIRED`() {
        val order = draft()
        addOutbound(order.id)
        addReturn(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        saga.onReservationResult(order.id, tripReturn, "RESERVED")
        orderService.pay(order.id, buyer)
        saga.onPaymentResult(order.id, "AUTHORIZED")

        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED")     // outbound becomes a firm sale
        saga.onSeatsConfirmed(order.id, tripReturn, "EXPIRED") // the return's hold lapsed -> can't fulfil the booking

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(RejectionReason.RESERVATION_EXPIRED, rejectionOf(order.id))
        assertEquals(1, count(Topics.CANCEL_AUTHORIZATION), "the funds hold is cancelled (no capture, so no refund)")
        assertEquals(0, count(Topics.CAPTURE_PAYMENT), "never captured — not all legs were confirmed")
        assertEquals(1, count(Topics.RELEASE_SEATS), "the already-confirmed outbound leg is un-sold")
        assertEquals(LegStatus.RELEASED, legStatus(order.id, trip))
    }

    // --- idempotency / out-of-order ----------------------------------------------------------------------------

    @Test
    fun `a redelivered seats-confirmed on an already-confirmed leg does not capture twice`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer)
        saga.onPaymentResult(order.id, "AUTHORIZED")

        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED")
        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED") // redelivery: the leg is no longer RESERVED -> no-op

        assertEquals(1, count(Topics.CAPTURE_PAYMENT), "no duplicate capture on redelivery")
    }

    @Test
    fun `payment-captured before AUTHORIZED is a no-op - never confirms early`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer) // PAYING, not yet AUTHORIZED

        saga.onPaymentCaptured(order.id)

        assertEquals(OrderStatus.PAYING, statusOf(order.id), "must not confirm before AUTHORIZED")
        assertEquals(0, count(Topics.ORDER_CONFIRMED))
        assertNull(rejectionOf(order.id))
    }

    @Test
    fun `replies on a terminal order are no-ops - no state flip, no command rows`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer)
        saga.onPaymentResult(order.id, "DECLINED") // -> REJECTED
        val rejectedBefore = count(Topics.ORDER_REJECTED)
        val confirmBefore = count(Topics.CONFIRM_SEATS)

        // late/duplicate replies on a now-terminal order: every guard fails -> no-op
        saga.onPaymentResult(order.id, "AUTHORIZED")
        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED")
        saga.onPaymentCaptured(order.id)

        assertEquals(OrderStatus.REJECTED, statusOf(order.id))
        assertEquals(rejectedBefore, count(Topics.ORDER_REJECTED), "no duplicate terminal event")
        assertEquals(confirmBefore, count(Topics.CONFIRM_SEATS))
        assertEquals(0, count(Topics.CAPTURE_PAYMENT))
        assertEquals(0, count(Topics.ORDER_CONFIRMED))
    }

    // --- SSE status broadcast (order-status-changed -> Notifications relays each snapshot to the browser) -------

    @Test
    fun `each async transition emits a self-contained order-status-changed snapshot`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED") // leg RESERVED (unlocks Pay) — 1
        orderService.pay(order.id, buyer)                    // sync: the HTTP reply already carries the snapshot — 0
        saga.onPaymentResult(order.id, "AUTHORIZED")         // 2
        saga.onSeatsConfirmed(order.id, trip, "CONFIRMED")   // 3
        saga.onPaymentCaptured(order.id)                     // 4

        assertEquals(4, count(Topics.ORDER_STATUS_CHANGED), "one snapshot per async transition; the sync pay is excluded")

        val terminal = statusEvents().first { it.status == OrderStatus.CONFIRMED }
        assertEquals(order.id, terminal.orderId)
        // self-contained: the snapshot carries the legs, so Notifications never has to call back into Orders
        assertEquals(trip, terminal.legs.first().tripCode)
    }

    @Test
    fun `a rejection emits a terminal order-status-changed snapshot carrying the reason`() {
        val order = draft()
        addOutbound(order.id)
        saga.onReservationResult(order.id, trip, "RESERVED") // 1
        orderService.pay(order.id, buyer)
        saga.onPaymentResult(order.id, "DECLINED")           // reject -> 2

        assertEquals(2, count(Topics.ORDER_STATUS_CHANGED))
        val terminal = statusEvents().first { it.status == OrderStatus.REJECTED }
        assertEquals(RejectionReason.PAYMENT_DECLINED, terminal.rejectionReason)
    }
}
