package it.g03.orders

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.OrderStatus
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import it.g03.orders.service.OrderTimeoutSweeper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [OrderTimeoutSweeper] is the RECOVER-within-the-window liveness backstop: a POST-PAY order (PAYING or
 * AUTHORIZED) stuck past the deadline has the command for its current state RE-EMITTED (idempotent re-drive),
 * while a fresh order — and, crucially, a HELD booking still awaiting the USER — is left alone (the latter is the
 * checkout-expiry sweeper's job, not this one). Orders are aged deterministically (no wall-clock wait).
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false", // we call sweepOnce() directly; no background loop racing the assertions
        "app.timeout-sweeper.deadline=PT30S",
        "app.checkout.expiry-sweeper.enabled=false",
    ],
)
@Tag("chaos-04") // anticipates chaos #4 (kafka-partition): a lost reply wedges a post-pay order; the sweeper re-drives it
@Import(TestcontainersConfiguration::class)
class OrderTimeoutSweeperTest {

    private val trip = "FR9592-20260629"
    private val buyer = "buyer-1"

    @Autowired lateinit var sweeper: OrderTimeoutSweeper
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

    private fun count(topic: String) = outbox.findAll().count { it.topic == topic }

    // Drives a booking to PAYING (one reserved outbound leg, pay initiated).
    private fun payingOrder(): UUID {
        val order = orderService.createDraft(buyer, "buyer@example.org", null).order
        orderService.addLeg(order.id, buyer, LegDirection.OUTBOUND, trip, 1)
        saga.onReservationResult(order.id, trip, "RESERVED")
        orderService.pay(order.id, buyer)
        return order.id
    }

    // Ages an order's updatedAt so it is deterministically past the deadline.
    private fun age(id: UUID, secondsAgo: Long): Instant {
        val order = orders.findById(id).orElseThrow()
        val stale = Instant.now().minusSeconds(secondsAgo)
        order.updatedAt = stale
        orders.saveAndFlush(order)
        return stale
    }

    @Test
    fun `a PAYING order past the deadline re-emits authorize-payment and bumps updatedAt`() {
        val id = payingOrder()
        val authBefore = count(Topics.AUTHORIZE_PAYMENT)
        val stale = age(id, secondsAgo = 60)

        sweeper.sweepOnce()

        val swept = orders.findById(id).orElseThrow()
        assertEquals(OrderStatus.PAYING, swept.status, "re-drive does not change state, only re-emits the command")
        assertEquals(authBefore + 1, count(Topics.AUTHORIZE_PAYMENT), "the PAYING command is re-emitted")
        assertTrue(swept.updatedAt.isAfter(stale), "updatedAt is bumped so the order is not swept again next poll")
    }

    @Test
    fun `an AUTHORIZED order past the deadline re-emits confirm-seats for its reserved legs`() {
        val id = payingOrder()
        saga.onPaymentResult(id, "AUTHORIZED") // fans out one confirm-seats; the leg is still RESERVED
        val confirmBefore = count(Topics.CONFIRM_SEATS)
        age(id, secondsAgo = 60)

        sweeper.sweepOnce()

        assertEquals(OrderStatus.AUTHORIZED, orders.findById(id).orElseThrow().status)
        assertEquals(confirmBefore + 1, count(Topics.CONFIRM_SEATS), "confirm-seats is re-emitted for the still-reserved leg")
    }

    @Test
    fun `a HELD booking awaiting the user is NOT re-driven by the timeout sweeper`() {
        val order = orderService.createDraft(buyer, "buyer@example.org", null).order
        orderService.addLeg(order.id, buyer, LegDirection.OUTBOUND, trip, 1) // HELD, awaiting the user's pay
        val reserveBefore = count(Topics.RESERVE_SEATS)
        age(order.id, secondsAgo = 600)

        sweeper.sweepOnce()

        assertEquals(OrderStatus.HELD, orders.findById(order.id).orElseThrow().status)
        assertEquals(reserveBefore, count(Topics.RESERVE_SEATS), "a HELD order is the checkout sweeper's job, not this one")
    }

    @Test
    fun `a fresh PAYING order is not swept`() {
        val id = payingOrder() // fresh updatedAt, well within the deadline
        val authBefore = count(Topics.AUTHORIZE_PAYMENT)

        sweeper.sweepOnce()

        assertEquals(authBefore, count(Topics.AUTHORIZE_PAYMENT), "a fresh order must not be re-driven")
    }

    @Test
    fun `re-drive is idempotent across polls - the updatedAt bump stops an immediate re-sweep`() {
        val id = payingOrder()
        age(id, secondsAgo = 60)

        sweeper.sweepOnce()
        val afterFirst = count(Topics.AUTHORIZE_PAYMENT)
        sweeper.sweepOnce() // updatedAt now fresh -> nothing to sweep

        assertEquals(afterFirst, count(Topics.AUTHORIZE_PAYMENT), "the bumped updatedAt prevents an immediate re-drive")
    }

    @Test
    fun `a terminal order is never re-driven, even if stale`() {
        val id = payingOrder()
        saga.onPaymentResult(id, "DECLINED") // -> REJECTED (terminal)
        age(id, secondsAgo = 600)
        val before = outbox.count()

        sweeper.sweepOnce()

        assertEquals(OrderStatus.REJECTED, orders.findById(id).orElseThrow().status)
        assertEquals(before, outbox.count(), "a terminal order is excluded from the sweep query")
    }
}
