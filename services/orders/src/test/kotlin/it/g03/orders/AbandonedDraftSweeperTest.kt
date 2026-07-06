package it.g03.orders

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.LegStatus
import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderLeg
import it.g03.orders.entities.OrderStatus
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.service.AbandonedDraftSweeper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [AbandonedDraftSweeper] hard-deletes DRAFT bookings idle past the max-age (abandoned empty carts) together
 * with any leftover leg rows — the order_legs FK has no ON DELETE CASCADE, so legs must be removed first — while
 * leaving a still-fresh draft and any non-DRAFT order untouched. `updatedAt` is aged deterministically (no wait).
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false",
        "app.checkout.expiry-sweeper.enabled=false",
        "app.draft-sweeper.enabled=false", // we call sweepOnce() directly
    ],
)
@Import(TestcontainersConfiguration::class)
class AbandonedDraftSweeperTest {

    private val buyer = "buyer-1"
    private val trip = "FR9592-20260629"

    @Autowired lateinit var sweeper: AbandonedDraftSweeper
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository

    @BeforeEach
    fun cleanSlate() {
        legs.deleteAll()
        orders.deleteAll()
    }

    // Persists an order in the given status and forces its last-activity stamp `idleDays` into the past.
    private fun order(status: OrderStatus, idleDays: Long): UUID {
        val o = orders.save(Order(buyerSub = buyer, status = status))
        o.updatedAt = Instant.now().minus(Duration.ofDays(idleDays))
        return orders.saveAndFlush(o).id
    }

    private fun exists(id: UUID) = orders.findById(id).isPresent

    @Test
    fun `a DRAFT idle past the max-age is discarded`() {
        val stale = order(OrderStatus.DRAFT, idleDays = 4)

        sweeper.sweepOnce()

        assertFalse(exists(stale), "an empty cart abandoned past the max-age is removed")
    }

    @Test
    fun `discarding a stale DRAFT also removes its leftover legs (the FK has no cascade)`() {
        val stale = order(OrderStatus.DRAFT, idleDays = 4)
        legs.saveAndFlush(
            OrderLeg(orderId = stale, direction = LegDirection.OUTBOUND, tripCode = trip, qty = 1, unitPriceCents = 1000, status = LegStatus.RELEASED),
        )

        sweeper.sweepOnce()

        assertFalse(exists(stale))
        assertTrue(legs.findByOrderId(stale).isEmpty(), "legs are deleted before the order, so the FK never blocks the delete")
    }

    @Test
    fun `a DRAFT still within the max-age is left alone`() {
        val fresh = order(OrderStatus.DRAFT, idleDays = 0)

        sweeper.sweepOnce()

        assertTrue(exists(fresh), "a recently-touched cart is not abandoned yet")
        assertEquals(OrderStatus.DRAFT, orders.findById(fresh).orElseThrow().status)
    }

    @Test
    fun `a non-DRAFT order idle past the max-age is never discarded`() {
        val held = order(OrderStatus.HELD, idleDays = 4)

        sweeper.sweepOnce()

        assertTrue(exists(held), "only DRAFT is swept — a booking in flight is untouched regardless of age")
    }
}
