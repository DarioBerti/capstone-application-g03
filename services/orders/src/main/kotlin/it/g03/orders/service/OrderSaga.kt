package it.g03.orders.service

import io.micrometer.core.instrument.MeterRegistry
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.gateway.InventoryGateway
import it.g03.orders.gateway.PaymentsGateway
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * THE ORCHESTRATED SAGA (design decision #3): Orders is the coordinator; the stages are driven by Kafka (order-placed triggers this handler) but each decision is taken via a SYNCHRONOUS call ("a decision must be made now")!
 *
 * Crash/redelivery semantics (at-least-once, chaos #2/#4):
 * - this method deliberately holds NO database transaction across remote calls; each transition commits separately via [OrderStateMachine]
 * - every downstream operation is idempotent (Inventory/Payments replay stored outcomes), so re-running the saga after a crash resumes from where it left off: PENDING → re-runs everything; RESERVED/PAYMENT_PENDING → skip reserve; terminal → no-op
 *
 * Resilience (Pillar C, Phase 2): the Payments call is wrapped in a circuit breaker (see [HttpPaymentsGateway]). A transport fault no longer THROWS out of the saga — it returns "UNAVAILABLE", which PARKS the order in PAYMENT_PENDING for [OrderRecoverySweeper] to retry. A lost optimistic-lock race on a concurrent terminal transition (two sweeper pods, or sweep + redelivery) surfaces as [ObjectOptimisticLockingFailureException] and is absorbed here as a benign no-op (the other driver already advanced the order)
 */

@Component // registers this class as a Spring-managed bean
class OrderSaga(
    private val orders: OrderRepository,
    private val stateMachine: OrderStateMachine,
    private val inventory: InventoryGateway,
    private val payments: PaymentsGateway,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Compensation counter (Pillar D, low-cardinality {type}): type = refund — the storno/void of a charge whose hold
    // expired post-authorize (Fase 2 #4). "refund" here denotes the void primitive (the mock PSP authorizes, never captures)
    private fun compensations(type: String) = meterRegistry.counter("orders_compensations_total", "type", type)

    // Drives one order through the saga; resumable on redelivery/recovery and a no-op once terminal. A concurrent terminal transition that loses the @Version race rolls back (no duplicate events) and is swallowed here as a clean no-op
    fun handle(orderId: UUID) {
        try {
            handleInternal(orderId)
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.info("order {} advanced concurrently (optimistic-lock race lost) — no-op", orderId)
        }
    }

    private fun handleInternal(orderId: UUID) {
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

        // Stage 2 -> authorize the payment (strictly idempotent on the Payments side). The call is wrapped in a circuit breaker (see HttpPaymentsGateway): three outcomes now, not two
        val authorization = payments.authorize(id, order.amountCents)
        when (authorization.status) {
            "DECLINED" -> {
                // Business decline: compensation — give the seats back, then reject (the customer is told why)
                inventory.release(id, order.productId)
                stateMachine.reject(orderId, RejectionReason.PAYMENT_DECLINED)
                return
            }
            "UNAVAILABLE" -> {
                // Breaker open / transport fault: do NOT reject (the card may be fine) and do NOT propagate the fault. PARK the order — the seats stay held within their TTL — and let OrderRecoverySweeper retry once the breaker closes (Pillar C: contain, don't propagate)
                stateMachine.markPaymentPending(orderId)
                return
            }
            "VOIDED" -> {
                // Crash-recovery replay: a prior pass already compensated (voided) this charge but crashed before the
                // terminal reject. The replayed authorize now returns VOIDED — there is nothing left to do but finish the
                // rejection (Fase 2 #4). Without this branch the order would mis-park here and spam "unexpected status"
                stateMachine.reject(orderId, RejectionReason.RESERVATION_EXPIRED)
                return
            }
            "AUTHORIZED" -> { /* fall through to the confirm stage below */ }
            else -> {
                // Defensive: never CONFIRM on an unexpected status — park and retry rather than confirm a seat we may not have paid for
                log.warn("unexpected authorization status '{}' for order {} — parking PAYMENT_PENDING", authorization.status, id)
                stateMachine.markPaymentPending(orderId)
                return
            }
        }

        // Stage 3 -> confirm the hold (guards the slow-payment-vs-TTL race, refinement #3): Inventory re-checks the hold is still valid; if it expired meanwhile we must NOT confirm a seat we no longer hold
        val confirmation = inventory.confirm(id, order.productId)
        if (confirmation.status != "CONFIRMED") {
            // The hold lapsed before confirm, but payments.authorize already CHARGED on this pass. COMPENSATE: void the
            // charge before rejecting, so the saga invariant "no charge without a seat" holds (Fase 2 #4, decision #3). The
            // void is idempotent on Payments and wrapped in the same breaker/retry as authorize. [OrderRecoverySweeper]
            // bounds re-authorization by park age, so this branch is rare (the sub-second authorize→confirm window)
            val voided = payments.void(id)
            when (voided.status) {
                "VOIDED" -> {
                    // charge reversed -> finish the rejection. Count the compensation only on a REAL reversal (not a
                    // replay): a concurrent driver (sweeper, second pod) may also void and get VOIDED(replayed=true) —
                    // the @Version reject lets only one terminal win, so gating on !replayed keeps the counter accurate
                    if (!voided.replayed) compensations("refund").increment()
                    log.info("order {} confirm returned {} after authorize — charge VOIDED, REJECTED(RESERVATION_EXPIRED)", id, confirmation.status)
                    stateMachine.reject(orderId, RejectionReason.RESERVATION_EXPIRED)
                }
                "NOT_FOUND", "DECLINED" -> {
                    // Nothing was ever charged (defensive: unreachable from this branch in the real flow, which only gets here after a 200 AUTHORIZED) -> no compensation needed, just reject
                    log.info("order {} confirm returned {} after authorize — no charge to reverse ({}), REJECTED(RESERVATION_EXPIRED)", id, confirmation.status, voided.status)
                    stateMachine.reject(orderId, RejectionReason.RESERVATION_EXPIRED)
                }
                else -> {
                    // UNAVAILABLE / FAILED: the void could not complete now (Payments down / breaker open). Do NOT go terminal
                    // with a live charge — PARK the order so OrderRecoverySweeper re-drives the saga and RETRIES the void until
                    // it succeeds (Fase 2 #4, Option B: money-safe, reuses the existing recovery loop, no new state)
                    log.warn("order {} confirm returned {} but void was {} — parking PAYMENT_PENDING to retry the reversal", id, confirmation.status, voided.status)
                    stateMachine.markPaymentPending(orderId)
                }
            }
            return
        }

        stateMachine.confirm(orderId)
    }
}
