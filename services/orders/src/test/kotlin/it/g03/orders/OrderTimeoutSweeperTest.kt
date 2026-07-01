package it.g03.orders

import it.g03.orders.entities.OrderStatus
import it.g03.orders.events.Topics
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
 * Proves [OrderTimeoutSweeper] is the liveness backstop of the async saga: an order stuck in a non-terminal
 * state past the deadline has the command for its current state RE-EMITTED (idempotent re-drive), while a
 * fresh order is left alone. The re-drive bumps `updatedAt`, so it is not swept again on the next poll — the
 * re-drive is self-throttling/idempotent across polls. Orders are aged deterministically (no wall-clock wait).
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.timeout-sweeper.enabled=false", // we call sweepOnce() directly; no background loop racing the assertions
        "app.timeout-sweeper.deadline=PT30S",
    ],
)
@Tag("chaos-04") // anticipates chaos #4 (kafka-partition): a lost reply leaves a wedged order; the sweeper re-drives it to a terminal state
@Import(TestcontainersConfiguration::class)
class OrderTimeoutSweeperTest {

    private val trip = "FR9592-20260629"

    @Autowired lateinit var sweeper: OrderTimeoutSweeper
    @Autowired lateinit var saga: OrderSaga
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var outbox: OutboxEventRepository

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        orders.deleteAll()
    }

    private fun count(topic: String) = outbox.findAll().count { it.topic == topic }

    // Ages an order's updatedAt so it is deterministically past the deadline.
    private fun age(id: UUID, secondsAgo: Long): Instant {
        val order = orders.findById(id).orElseThrow()
        val stale = Instant.now().minusSeconds(secondsAgo)
        order.updatedAt = stale
        orders.saveAndFlush(order)
        return stale
    }

    @Test
    fun `an order stuck in RESERVED past the deadline is re-driven (authorize-payment re-emitted, updatedAt bumped)`() {
        val order = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org")
        saga.start(order.id)
        saga.onReservationResult(order.id, "RESERVED") // -> RESERVED, emits authorize-payment
        val authBefore = count(Topics.AUTHORIZE_PAYMENT)
        val stale = age(order.id, secondsAgo = 60)

        sweeper.sweepOnce()

        val swept = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.RESERVED, swept.status, "re-drive does not change state, only re-emits the command")
        assertEquals(authBefore + 1, count(Topics.AUTHORIZE_PAYMENT), "the RESERVED command is re-emitted")
        assertTrue(swept.updatedAt.isAfter(stale), "updatedAt is bumped so the order is not swept again next poll")
    }

    @Test
    fun `a fresh order is not swept`() {
        val order = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org")
        saga.start(order.id) // PENDING, fresh updatedAt (well within the deadline)
        val reserveBefore = count(Topics.RESERVE_SEATS)

        sweeper.sweepOnce()

        assertEquals(reserveBefore, count(Topics.RESERVE_SEATS), "a fresh order must not be re-driven")
    }

    @Test
    fun `re-drive is idempotent across polls - the updatedAt bump stops an immediate re-sweep`() {
        val order = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org")
        saga.start(order.id)
        saga.onReservationResult(order.id, "RESERVED")
        age(order.id, secondsAgo = 60)

        sweeper.sweepOnce() // re-drives once
        val afterFirst = count(Topics.AUTHORIZE_PAYMENT)
        sweeper.sweepOnce() // updatedAt now fresh -> nothing to sweep

        assertEquals(afterFirst, count(Topics.AUTHORIZE_PAYMENT), "the bumped updatedAt prevents an immediate re-drive")
    }

    @Test
    fun `a terminal order is never re-driven, even if stale`() {
        val order = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org")
        saga.start(order.id)
        saga.onReservationResult(order.id, "SOLD_OUT") // -> REJECTED (terminal)
        age(order.id, secondsAgo = 600)
        val before = outbox.count()

        sweeper.sweepOnce()

        assertEquals(OrderStatus.REJECTED, orders.findById(order.id).orElseThrow().status)
        assertEquals(before, outbox.count(), "a terminal order is excluded from the sweep query")
    }
}
