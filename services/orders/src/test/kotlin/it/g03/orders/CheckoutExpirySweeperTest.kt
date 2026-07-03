package it.g03.orders

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.CheckoutExpirySweeper
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Proves [CheckoutExpirySweeper] is the GIVE-UP-at-the-window half of the backend-authoritative TTL, with the
 * dual cause: a HELD booking whose window elapsed with NO payment is rejected EXPIRED_NO_PAYMENT (user inaction);
 * a PAYING booking whose window elapsed AFTER pay was accepted is rejected SERVICE_UNAVAILABLE (system fault) and
 * additionally cancels the authorization. A booking still within its window, and a terminal one, are untouched.
 * Windows are aged deterministically (no wall-clock wait).
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false",
        "app.checkout.expiry-sweeper.enabled=false", // we call sweepOnce() directly
    ],
)
@Tag("chaos-01") // system-fault vs user-inaction at the checkout window: the two rejection causes are observable
@Import(TestcontainersConfiguration::class)
class CheckoutExpirySweeperTest {

    private val trip = "FR9592-20260629"
    private val buyer = "buyer-1"

    @Autowired lateinit var sweeper: CheckoutExpirySweeper
    @Autowired lateinit var saga: OrderSaga
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository
    @Autowired lateinit var outbox: OutboxEventRepository

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        legs.deleteAll()
        orders.deleteAll()
    }

    private fun statusOf(id: UUID) = orders.findById(id).orElseThrow().status
    private fun rejectionOf(id: UUID) = orders.findById(id).orElseThrow().rejectionReason
    private fun count(topic: String) = outbox.findAll().count { it.topic == topic }

    // A HELD booking with a reserved outbound leg (the user has not paid yet).
    private fun heldReserved(): UUID {
        val order = orderService.createDraft(buyer, "buyer@example.org", null).order
        orderService.addLeg(order.id, buyer, LegDirection.OUTBOUND, trip, 1)
        saga.onReservationResult(order.id, trip, "RESERVED")
        return order.id
    }

    // Forces the hold window into the past so the sweeper treats it as elapsed.
    private fun ageWindow(id: UUID, secondsAgo: Long) {
        val order = orders.findById(id).orElseThrow()
        order.expiresAt = Instant.now().minusSeconds(secondsAgo)
        orders.saveAndFlush(order)
    }

    @Test
    fun `a HELD booking past the window with no payment is rejected EXPIRED_NO_PAYMENT and releases its legs`() {
        val id = heldReserved()
        ageWindow(id, secondsAgo = 60)

        sweeper.sweepOnce()

        assertEquals(OrderStatus.REJECTED, statusOf(id))
        assertEquals(RejectionReason.EXPIRED_NO_PAYMENT, rejectionOf(id), "user inaction, not a system fault")
        assertEquals(1, count(Topics.RELEASE_SEATS), "the reserved leg is given back")
        assertEquals(0, count(Topics.CANCEL_AUTHORIZATION), "no authorization was ever made")
        assertEquals(1, count(Topics.ORDER_REJECTED))
    }

    @Test
    fun `a PAYING booking past the window is rejected SERVICE_UNAVAILABLE and cancels the authorization`() {
        val id = heldReserved()
        orderService.pay(id, buyer) // -> PAYING, paymentInitiatedAt stamped
        ageWindow(id, secondsAgo = 60)

        sweeper.sweepOnce()

        assertEquals(OrderStatus.REJECTED, statusOf(id))
        assertEquals(RejectionReason.SERVICE_UNAVAILABLE, rejectionOf(id), "pay was accepted but the pipeline stalled")
        assertEquals(1, count(Topics.RELEASE_SEATS), "the reserved leg is given back")
        assertEquals(1, count(Topics.CANCEL_AUTHORIZATION), "a maybe-existing hold is voided (tombstone-safe)")
    }

    @Test
    fun `a booking still within its window is not expired`() {
        val id = heldReserved() // expiresAt = now + PT15M, well in the future

        sweeper.sweepOnce()

        assertEquals(OrderStatus.HELD, statusOf(id), "within the window the booking is left for the user")
        assertEquals(0, count(Topics.ORDER_REJECTED))
    }

    @Test
    fun `expiry is idempotent across polls and never re-touches a terminal order`() {
        val id = heldReserved()
        ageWindow(id, secondsAgo = 60)

        sweeper.sweepOnce() // -> REJECTED(EXPIRED_NO_PAYMENT)
        val rejectedAfterFirst = count(Topics.ORDER_REJECTED)
        sweeper.sweepOnce() // terminal now -> excluded from the query

        assertEquals(OrderStatus.REJECTED, statusOf(id))
        assertEquals(rejectedAfterFirst, count(Topics.ORDER_REJECTED), "a terminal order is never expired again")
    }
}
