package it.g03.orders

import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.gateway.AuthorizationResult
import it.g03.orders.gateway.InventoryGateway
import it.g03.orders.gateway.PaymentsGateway
import it.g03.orders.gateway.ReservationResult
import it.g03.orders.repository.OrderRepository
import it.g03.orders.service.OrderRecoverySweeper
import it.g03.orders.service.OrderSaga
import it.g03.orders.service.OrderService
import it.g03.orders.service.OrderStateMachine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves OrderRecoverySweeper's money-safety: an order parked beyond app.recovery.max-park is rejected
 * (RESERVATION_EXPIRED) WITHOUT re-authorizing — so a card is never charged for a hold the inventory reconciler has
 * already restocked. max-park is set to PT0S so any parked order is deterministically "beyond" (no wall-clock wait)
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
        "app.recovery.max-park=PT0S",
    ],
)
@Tag("chaos-01") // anticipates chaos #1 (payments-latency): a parked order drains/bounds once Payments heals
class OrderRecoverySweeperTest {

    @TestConfiguration // swap the HTTP gateways for the steerable fakes (reused from OrderSagaTest)
    class Fakes {
        @Bean @Primary fun fakeInventory() = OrderSagaTest.FakeInventory()
        @Bean @Primary fun fakePayments() = OrderSagaTest.FakePayments()
    }

    @Autowired lateinit var sweeper: OrderRecoverySweeper
    @Autowired lateinit var stateMachine: OrderStateMachine
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var payments: OrderSagaTest.FakePayments

    @BeforeEach // the fakes are singleton beans shared across test methods — reset their steerable state each test
    fun cleanSlate() {
        orders.deleteAll()
        payments.status = "AUTHORIZED"
        payments.voidStatus = "VOIDED"
        payments.authorizeCalls = 0
        payments.voided.clear()
    }

    @Test
    fun `an order parked beyond max-park is REJECTED without charging`() {
        val order = orderService.placeOrder("TRENO-TEST", 1)
        stateMachine.markReserved(order.id)
        stateMachine.markPaymentPending(order.id)
        payments.authorizeCalls = 0

        sweeper.sweepOnce()

        val swept = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.REJECTED, swept.status, "a hold parked past its TTL must be rejected, not retried")
        assertEquals(RejectionReason.RESERVATION_EXPIRED, swept.reason)
        assertEquals(0, payments.authorizeCalls, "money-safe: the card is NOT charged for an expired hold")
    }

    // Verifies MAJOR-2 (Option B park-and-retry): at the max-park boundary, if the void cannot complete because Payments
    // is unreachable, the order must NOT go terminal (a possibly-live charge would be abandoned) — it stays PAYMENT_PENDING
    // and the void is retried next sweep. Money-safety over bounded retention; it drains once Payments recovers.
    @Test
    fun `parked order whose void is UNAVAILABLE stays PAYMENT_PENDING (retried, not abandoned)`() {
        val order = orderService.placeOrder("TRENO-TEST", 1)
        stateMachine.markReserved(order.id)
        stateMachine.markPaymentPending(order.id)
        payments.voidStatus = "UNAVAILABLE" // the storno cannot complete now

        sweeper.sweepOnce()

        val swept = orders.findById(order.id).orElseThrow()
        assertEquals(OrderStatus.PAYMENT_PENDING, swept.status, "an unreachable void must keep the order parked, never abandon a possible live charge")
        assertEquals(null, swept.reason, "still parked, not rejected")
        assertTrue(payments.voided.isNotEmpty(), "the void was attempted")
        assertEquals(0, payments.authorizeCalls, "money-safe: never re-authorizes at the expiry boundary")
    }
}
