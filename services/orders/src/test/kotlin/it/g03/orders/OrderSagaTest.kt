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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saga orchestration tests: real Spring context + H2 + REAL state machine/outbox, but the sibling services are FAKES (the gateways are interfaces precisely for this)
 * The HTTP integration itself is exercised in-cluster; here we prove the ORCHESTRATION LOGIC: branching, compensation, terminal-state idempotency and the outbox trail
 */

@SpringBootTest(  // boot the full Spring app context to test saga + state machine + outbox wiring
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",     // no broker in tests
        "app.kafka-listeners-enabled=false", // saga is driven directly, not via Kafka
        "app.outbox.enabled=false",          // no background relay racing the assertions
    ],
)

class OrderSagaTest {

    // Mutable fakes the tests steer; @Primary overrides the HTTP gateway beans
    class FakeInventory : InventoryGateway {
        var reserveStatus = "RESERVED"
        var confirmStatus = "CONFIRMED"
        var reserveCalls = 0
        val released = mutableListOf<String>()
        override fun reserve(orderId: String, productId: String, qty: Int): ReservationResult {
            reserveCalls++
            return ReservationResult(reserveStatus, false)
        }
        override fun confirm(orderId: String, productId: String) = ReservationResult(confirmStatus, false)
        override fun release(orderId: String, productId: String): ReservationResult {
            released += orderId
            return ReservationResult("RELEASED", false)
        }
    }

    class FakePayments : PaymentsGateway {
        var status = "AUTHORIZED"
        var authorizeCalls = 0
        override fun authorize(orderId: String, amountCents: Long): AuthorizationResult {
            authorizeCalls++
            return AuthorizationResult(status, false)
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
        payments.authorizeCalls = 0
    }

    // Helper: the sorted list of topics currently sitting in the outbox (the emitted event trail)
    private fun topics() = outbox.findAll().map { it.topic }.sorted()

    // Verifies happy-path: placeOrder writes the order AND its order-placed event in one transaction
    @Test
    fun `placeOrder persists the order AND its order-placed event in one transaction`() {
        val order = orderService.placeOrder("TRENO-TEST", 2)

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(2_000, order.amountCents)
        val events = outbox.findAll()
        assertEquals(1, events.size)
        assertEquals(Topics.ORDER_PLACED, events[0].topic)
        assertEquals(order.id.toString(), events[0].aggregateId)
        assertTrue(events[0].payload.contains(order.id.toString()), "payload carries the orderId")
    }

    // Verifies happy-path: a full saga run reaches CONFIRMED and emits confirmation + notification events
    @Test
    fun `happy path - order is CONFIRMED with confirmation + notification events`() {
        val order = orderService.placeOrder("TRENO-TEST", 1)

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
        val order = orderService.placeOrder("TRENO-TEST", 1)

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status)
        assertEquals(RejectionReason.SOLD_OUT, saved.reason)
        assertEquals(0, payments.authorizeCalls, "no payment attempt for a failed reservation")
        assertTrue(inventory.released.isEmpty(), "no hold to release")
    }

    // Verifies error-path: a declined payment triggers the compensation (release) and rejects with PAYMENT_DECLINED
    @Test
    fun `payment declined - reservation is RELEASED (compensation) and order REJECTED`() {
        payments.status = "DECLINED"
        val order = orderService.placeOrder("TRENO-TEST", 1)

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status)
        assertEquals(RejectionReason.PAYMENT_DECLINED, saved.reason)
        assertEquals(listOf(order.id.toString()), inventory.released, "the hold must be given back")
    }

    // Verifies error-path: a hold that expired before confirm rejects with RESERVATION_EXPIRED
    @Test
    fun `hold expired before confirm (slow payment) - order REJECTED(RESERVATION_EXPIRED)`() {
        inventory.confirmStatus = "EXPIRED"
        val order = orderService.placeOrder("TRENO-TEST", 1)

        saga.handle(order.id)

        val saved = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, saved.status)
        assertEquals(RejectionReason.RESERVATION_EXPIRED, saved.reason)
    }

    // Verifies happy-path: re-running the saga on a terminal order is a no-op (no duplicate events, no new calls)
    @Test
    fun `redelivery on a terminal order is a no-op - no duplicate events, no new calls`() {
        val order = orderService.placeOrder("TRENO-TEST", 1)
        saga.handle(order.id)
        val eventsAfterFirstRun = outbox.count()
        val reserveCallsAfterFirstRun = inventory.reserveCalls

        saga.handle(order.id) // duplicated order-placed delivery

        assertEquals(eventsAfterFirstRun, outbox.count(), "no duplicate outbox events")
        assertEquals(reserveCallsAfterFirstRun, inventory.reserveCalls, "no downstream calls on replay")
        assertEquals(OrderStatus.CONFIRMED, orders.findById(order.id).orElseThrow().status)
    }
}
