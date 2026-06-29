package it.g03.orders

import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.events.Topics
import it.g03.orders.gateway.AuthorizationResult
import it.g03.orders.gateway.InventoryGateway
import it.g03.orders.gateway.PaymentsGateway
import it.g03.orders.gateway.ReservationResult
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saga orchestration tests: real Spring context + Testcontainers Postgres + REAL state machine/outbox, but the
 * sibling services are FAKES (the gateways are interfaces precisely for this). The HTTP integration itself is
 * exercised in-cluster; here we prove the ORCHESTRATION LOGIC: branching, compensation, terminal-state
 * idempotency and the outbox trail.
 */
@SpringBootTest(  // boot the full Spring app context to test saga + state machine + outbox wiring
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",     // no broker in tests
        "app.kafka-listeners-enabled=false", // saga is driven directly, not via Kafka
        "app.outbox.enabled=false",          // no background relay racing the assertions
        "app.recovery.enabled=false",        // no recovery sweep racing the assertions
    ],
)
@Import(TestcontainersConfiguration::class)
class OrderSagaTest {

    private val trip = "FR9592-20260629"

    // Mutable fakes the tests steer; @Primary overrides the HTTP gateway beans
    class FakeInventory : InventoryGateway {
        var reserveStatus = "RESERVED"
        var confirmStatus = "CONFIRMED"
        var reserveCalls = 0
        val released = mutableListOf<String>()
        override fun reserve(orderId: String, tripCode: String, qty: Int): ReservationResult {
            reserveCalls++
            return ReservationResult(reserveStatus, false)
        }
        override fun confirm(orderId: String, tripCode: String) = ReservationResult(confirmStatus, false)
        override fun release(orderId: String, tripCode: String): ReservationResult {
            released += orderId
            return ReservationResult("RELEASED", false)
        }
    }

    class FakePayments : PaymentsGateway {
        var status = "AUTHORIZED"
        var voidStatus = "VOIDED"
        var authorizeCalls = 0
        val voided = mutableListOf<String>()
        override fun authorize(orderId: String, amountCents: Long): AuthorizationResult {
            authorizeCalls++
            return AuthorizationResult(status, false)
        }
        override fun void(orderId: String): AuthorizationResult {
            voided += orderId
            return AuthorizationResult(voidStatus, false)
        }
    }

    @TestConfiguration // test-only beans: swap the HTTP gateways for the steerable fakes
    class Fakes {
        @Bean @Primary fun fakeInventory() = FakeInventory() // @Primary makes this win over the HTTP bean
        @Bean @Primary fun fakePayments() = FakePayments()   // @Primary makes this win over the HTTP bean
    }

    // Injects the saga under test
    @Autowired lateinit var saga: OrderSaga
    // Injects the service used to place orders (sets up the PENDING starting state)
    @Autowired lateinit var orderService: OrderService
    // Injects the order repository so the test can inspect/clean order state directly
    @Autowired lateinit var orders: OrderRepository
    // Injects the outbox repository so the test can assert the emitted event trail
    @Autowired lateinit var outbox: OutboxEventRepository
    // Injects the fake Inventory gateway the test steers
    @Autowired lateinit var inventory: FakeInventory
    // Injects the fake Payments gateway the test steers
    @Autowired lateinit var payments: FakePayments

    @BeforeEach  // runs before each test method to start from a known state
    fun cleanSlate() {
        outbox.deleteAll()
        orders.deleteAll()
        inventory.reserveStatus = "RESERVED"
        inventory.confirmStatus = "CONFIRMED"
        inventory.reserveCalls = 0
        inventory.released.clear()
        payments.status = "AUTHORIZED"
        payments.voidStatus = "VOIDED"
        payments.authorizeCalls = 0
        payments.voided.clear()
    }

    // Helper: a freshly placed order for a test buyer
    private fun placePending(qty: Int = 1) =
        orderService.placeOrder(trip, qty, buyerSub = "buyer-1", buyerEmail = "buyer@example.org")

    // Helper: the sorted list of topics currently sitting in the outbox (the emitted event trail)
    private fun topics() = outbox.findAll().map { it.topic }.sorted()

    // Verifies happy-path: placeOrder writes the order AND its order-placed event in one transaction
    @Test
    fun `placeOrder persists the order AND its order-placed event in one transaction`() {
        val order = placePending(qty = 2)

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(2_000, order.amountCents)
        assertEquals("buyer-1", order.buyerSub)
        val events = outbox.findAll()
        assertEquals(1, events.size)
        assertEquals(Topics.ORDER_PLACED, events[0].topic)
        assertEquals(order.id.toString(), events[0].aggregateId)
        assertTrue(events[0].payload.contains(order.id.toString()), "payload carries the orderId")
    }

    // Verifies idempotency: a second placeOrder with the same Idempotency-Key replays the existing order
    @Test
    fun `placeOrder with the same idempotency key replays the existing order`() {
        val first = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org", idempotencyKey = "key-1")
        val replay = orderService.placeOrder(trip, 1, "buyer-1", "buyer@example.org", idempotencyKey = "key-1")

        assertEquals(first.id, replay.id, "same key must not create a second order")
        assertEquals(1, orders.count().toInt())
        assertEquals(1, outbox.findAll().count { it.topic == Topics.ORDER_PLACED }, "no duplicate order-placed event")
    }

    // Verifies happy-path: a full saga run reaches CONFIRMED and emits confirmation + notification events
    @Test
    fun `happy path - order is CONFIRMED with confirmation + notification events`() {
        val order = placePending()

        saga.handle(order.id)

        assertEquals(OrderStatus.CONFIRMED, orders.findById(order.id).orElseThrow().status)
        assertEquals(
            listOf(Topics.NOTIFICATION_REQUESTED, Topics.ORDER_CONFIRMED, Topics.ORDER_PLACED),
            topics(),
        )
    }

    // Verifies error-path: a sold-out reservation rejects with SOLD_OUT and never attempts payment/compensation
    @Test
    fun `sold out - order is REJECTED(SOLD_OUT), nothing to compensate`() {
        inventory.reserveStatus = "REJECTED"
        val order = placePending()

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status)
        assertEquals(RejectionReason.SOLD_OUT, saved.rejectionReason)
        assertEquals(0, payments.authorizeCalls, "no payment attempt for a failed reservation")
        assertTrue(inventory.released.isEmpty(), "no hold to release")
    }

    // Verifies error-path: a declined payment triggers the compensation (release) and rejects with PAYMENT_DECLINED
    @Test
    @Tag("chaos-02")
    fun `payment declined - reservation is RELEASED (compensation) and order REJECTED`() {
        payments.status = "DECLINED"
        val order = placePending()

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status)
        assertEquals(RejectionReason.PAYMENT_DECLINED, saved.rejectionReason)
        assertEquals(listOf(order.id.toString()), inventory.released, "the hold must be given back")
    }

    // Verifies error-path + compensation (Fase 2 #4, test 5.4): a hold that expired AFTER authorize must VOID the charge
    // BEFORE rejecting with RESERVATION_EXPIRED — closing the saga invariant "no charge without a seat"
    @Test
    @Tag("chaos-02")
    fun `hold expired after authorize - charge is VOIDED (compensation) then order REJECTED(RESERVATION_EXPIRED)`() {
        inventory.confirmStatus = "EXPIRED"
        val order = placePending()

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status)
        assertEquals(RejectionReason.RESERVATION_EXPIRED, saved.rejectionReason)
        assertEquals(listOf(order.id.toString()), payments.voided, "the charge must be voided before the reject")
    }

    // Verifies resilience (Fase 2 #4, Option B): if the VOID itself fails (Payments down / breaker open) the order must NOT
    // go terminal with a live charge — it is PARKED so the recovery sweep retries the void; once the void succeeds it REJECTS
    @Test
    @Tag("chaos-02")
    fun `void fails - order is PARKED (not rejected) then REJECTED(RESERVATION_EXPIRED) once the void succeeds`() {
        inventory.confirmStatus = "EXPIRED"
        payments.voidStatus = "UNAVAILABLE" // the storno cannot complete now
        val order = placePending()

        saga.handle(order.id)

        val parked = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.PAYMENT_PENDING, parked.status, "a failed void must park (retry), never leak a live charge")
        assertEquals(null, parked.rejectionReason, "parking is not a rejection")
        assertTrue(payments.voided.isNotEmpty(), "the void was attempted")

        // Payments recovers; the recovery sweep re-drives the saga -> the void now succeeds
        payments.voidStatus = "VOIDED"
        saga.handle(order.id)

        val rejected = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, rejected.status)
        assertEquals(RejectionReason.RESERVATION_EXPIRED, rejected.rejectionReason)
        assertEquals(1, inventory.reserveCalls, "reserve is not repeated across the recovery")
    }

    // Verifies the crash-window branch (Fase 2 #4): if a prior pass voided the charge but crashed before the reject, the
    // replayed authorize returns VOIDED — the saga must finish the rejection, NOT mis-park as an "unexpected status"
    @Test
    @Tag("chaos-02")
    fun `authorize replays VOIDED (crash after void) - order REJECTED(RESERVATION_EXPIRED), never PARKED`() {
        payments.status = "VOIDED" // a replayed authorize of an already-voided charge
        val order = placePending()

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status, "a VOIDED replay must finish the rejection, not park")
        assertEquals(RejectionReason.RESERVATION_EXPIRED, saved.rejectionReason)
    }

    // Verifies happy-path: re-running the saga on a terminal order is a no-op (no duplicate events, no new calls)
    @Test
    fun `redelivery on a terminal order is a no-op - no duplicate events, no new calls`() {
        val order = placePending()
        saga.handle(order.id)
        val eventsAfterFirstRun = outbox.count()
        val reserveCallsAfterFirstRun = inventory.reserveCalls

        saga.handle(order.id) // duplicated order-placed delivery

        assertEquals(eventsAfterFirstRun, outbox.count(), "no duplicate outbox events")
        assertEquals(reserveCallsAfterFirstRun, inventory.reserveCalls, "no downstream calls on replay")
        assertEquals(OrderStatus.CONFIRMED, orders.findById(order.id).orElseThrow().status)
    }

    // Verifies resilience (Pillar C): when Payments is UNAVAILABLE (circuit breaker open) the order is PARKED in PAYMENT_PENDING — not rejected, hold NOT released — and a later retry (breaker closed) drives it to CONFIRMED without repeating the reservation
    @Test
    @Tag("chaos-01")
    fun `payments unavailable - order is PARKED PAYMENT_PENDING then CONFIRMED on retry`() {
        payments.status = "UNAVAILABLE" // simulates the gateway's circuit-breaker fallback
        val order = placePending()

        saga.handle(order.id)

        val parked = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.PAYMENT_PENDING, parked.status, "an unavailable Payments must park, not reject")
        assertEquals(null, parked.rejectionReason, "parking is not a rejection")
        assertTrue(inventory.released.isEmpty(), "the seats stay held while parked (no compensation)")

        // Payments recovers; in production OrderRecoverySweeper re-drives the saga
        payments.status = "AUTHORIZED"
        saga.handle(order.id)

        val confirmed = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.CONFIRMED, confirmed.status)
        assertEquals(1, inventory.reserveCalls, "reserve is not repeated on the retry (resumed from PAYMENT_PENDING)")
    }

    // Verifies the recovery-loop reality: a SECOND consecutive UNAVAILABLE keeps the order PARKED (markPaymentPending is a no-op from PAYMENT_PENDING), reserve is never repeated, and a later AUTHORIZED still confirms
    @Test
    @Tag("chaos-01")
    fun `repeated UNAVAILABLE keeps the order PARKED, then CONFIRMS when payments returns`() {
        payments.status = "UNAVAILABLE"
        val order = placePending()

        saga.handle(order.id) // park
        saga.handle(order.id) // still unavailable -> stays parked (no-op re-park)

        assertEquals(OrderStatus.PAYMENT_PENDING, orders.findById(order.id).orElseThrow().status)
        assertEquals(1, inventory.reserveCalls, "reserve is not repeated while parked")

        payments.status = "AUTHORIZED"
        saga.handle(order.id)

        assertEquals(OrderStatus.CONFIRMED, orders.findById(order.id).orElseThrow().status)
        assertEquals(1, inventory.reserveCalls, "reserve still called exactly once across the whole recovery")
    }

    // Verifies the defensive branch: an UNEXPECTED authorization status must never CONFIRM — it parks (safe) rather than confirm a seat we may not have paid for
    @Test
    @Tag("chaos-01")
    fun `unexpected authorization status - order is PARKED, never CONFIRMED`() {
        payments.status = "WAT" // not AUTHORIZED / DECLINED / UNAVAILABLE
        val order = placePending()

        saga.handle(order.id)

        val parked = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.PAYMENT_PENDING, parked.status, "an unknown status must park, never confirm")
        assertTrue(inventory.released.isEmpty(), "nothing to compensate on a parked order")
    }
}
