package it.g03.orders.service

import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.gateway.InventoryGateway
import it.g03.orders.gateway.PaymentsGateway
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * THE ORCHESTRATED SAGA (design decision #3): Orders is the coordinator; the stages are driven by Kafka (order-placed triggers this handler) but each decision is taken via a SYNCHRONOUS call ("a decision must be made now")!
 *
 * Crash/redelivery semantics (at-least-once, chaos #2/#4):
 * - this method deliberately holds NO database transaction across remote calls; each transition commits separately via [OrderStateMachine]
 * - every downstream operation is idempotent (Inventory/Payments replay stored outcomes), so re-running the saga after a crash resumes from where it left off: PENDING → re-runs everything; RESERVED → skips reserve; terminal → no-op
 * - a TRANSPORT failure (timeout, 5xx) THROWS: the Kafka listener's default error handler retries with backoff, then logs & skips. A skipped order stays PENDING/RESERVED and is visible on dashboards
 * 
 * Phase 2 adds the circuit breaker + PAYMENT_PENDING fallback and a recovery sweep for stranded orders
 */

@Component // registers this class as a Spring-managed bean
class OrderSaga(
    private val orders: OrderRepository,
    private val stateMachine: OrderStateMachine,
    private val inventory: InventoryGateway,
    private val payments: PaymentsGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Drives one order through the saga; resumable on redelivery and a no-op once terminal
    fun handle(orderId: UUID) {
        val order = orders.findById(orderId).orElse(null)
            ?: run { log.warn("order-placed for unknown order {} — skipping", orderId); return }

        if (order.status == OrderStatus.CONFIRMED || order.status == OrderStatus.REJECTED) {
            log.info("saga replay for terminal order {} ({}) — no-op", orderId, order.status)
            return
        }
        val id = orderId.toString()

        // Stage 1 -> reserve the contended resource (skipped when resuming from RESERVED)
        if (order.status == OrderStatus.PENDING) {
            val reservation = inventory.reserve(id, order.productId, order.qty)
            if (reservation.status == "REJECTED") {
                stateMachine.reject(orderId, RejectionReason.SOLD_OUT)
                return
            }
            stateMachine.markReserved(orderId)
        }

        // Stage 2 -> authorize the payment (strictly idempotent on the Payments side)
        val authorization = payments.authorize(id, order.amountCents)
        if (authorization.status == "DECLINED") {
            // Compensation: give the seats back, then reject.
            inventory.release(id, order.productId)
            stateMachine.reject(orderId, RejectionReason.PAYMENT_DECLINED)
            return
        }

        // Stage 3 -> confirm the hold (guards the slow-payment-vs-TTL race, refinement #3): Inventory re-checks the hold is still valid; if it expired meanwhile we must NOT confirm a seat we no longer hold
        val confirmation = inventory.confirm(id, order.productId)
        if (confirmation.status != "CONFIRMED") {
            // TODO Phase 2: compensate the charge (refund/void on Payments): tracked in
            // docs/design.md; for now the rejection is recorded and observable!
            stateMachine.reject(orderId, RejectionReason.RESERVATION_EXPIRED)
            return
        }

        stateMachine.confirm(orderId)
    }
}
