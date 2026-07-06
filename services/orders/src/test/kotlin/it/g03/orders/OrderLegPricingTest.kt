package it.g03.orders

import it.g03.orders.entities.LegDirection
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.BadPriceQuoteException
import it.g03.orders.service.OrderService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression for the "search shows the right price, the order leg shows a flat price" bug: a leg must be priced
 * from the SELECTED trip's signed browse-time quote (per trip), not a single hardcoded constant — and a quote
 * for a different trip must be rejected. Drives the real [OrderService]; quotes are minted with [TestPriceQuotes]
 * (the DEV key Orders verifies with by default).
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
class OrderLegPricingTest {

    private val buyer = "buyer-1"
    private val email = "buyer@example.org"

    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository
    @Autowired lateinit var outbox: OutboxEventRepository

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        legs.deleteAll() // FK -> orders: delete children first
        orders.deleteAll()
    }

    @Test
    fun `leg is priced from the trip's signed quote, not a flat constant`() {
        val trip = "FR9592-20260629"
        val quote = TestPriceQuotes.sign(trip, priceCents = 4_250) // distinct from the old flat 1000

        val draftId = orderService.createDraft(buyer, email, null).order.id
        val view = orderService.addLeg(draftId, buyer, LegDirection.OUTBOUND, trip, 2, quote)

        val leg = view.legs.single()
        assertEquals(4_250, leg.unitPriceCents, "leg carries the quoted price")
        assertEquals(8_500, leg.qty * leg.unitPriceCents, "line amount = qty × the quoted price")
    }

    @Test
    fun `distinct trips on one booking are priced independently`() {
        val outbound = "FR9592-20260629"
        val ret = "FR9593-20260630"

        val id = orderService.createDraft(buyer, email, null).order.id
        orderService.addLeg(id, buyer, LegDirection.OUTBOUND, outbound, 1, TestPriceQuotes.sign(outbound, 3_000))
        val view = orderService.addLeg(id, buyer, LegDirection.RETURN, ret, 1, TestPriceQuotes.sign(ret, 5_000))

        val byTrip = view.legs.associateBy { it.tripCode }
        assertEquals(3_000, byTrip.getValue(outbound).unitPriceCents, "outbound priced from its own quote")
        assertEquals(5_000, byTrip.getValue(ret).unitPriceCents, "return priced from its own quote")
    }

    @Test
    fun `a quote for a different trip is rejected`() {
        val id = orderService.createDraft(buyer, email, null).order.id
        // A cheap quote minted for another trip must not price this (dearer) trip's leg.
        val quoteForOtherTrip = TestPriceQuotes.sign("FR0001-20260629", priceCents = 1)

        assertFailsWith<BadPriceQuoteException> {
            orderService.addLeg(id, buyer, LegDirection.OUTBOUND, "FR9592-20260629", 1, quoteForOtherTrip)
        }
    }
}
