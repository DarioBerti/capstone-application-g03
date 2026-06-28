package it.g03.orders.service

import io.micrometer.core.instrument.MeterRegistry
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.gateway.PaymentsGateway
import it.g03.orders.repository.OrderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Re-drives the orders PARKED in PAYMENT_PENDING by the circuit-breaker fallback (see [OrderSaga] stage 2). The Kafka
 * listener already committed its offset when it parked the order, so there is NO redelivery to lean on: this sweep is
 * what turns PAYMENT_PENDING from a dead-end into a recoverable state. Once Payments is reachable again the breaker
 * moves OPEN -> HALF_OPEN, a retried authorize succeeds, and [OrderSaga] advances the order to CONFIRMED
 *
 * Money-safety (Pillar B/C): an order parked LONGER than the inventory hold TTL has had its seats restocked by
 * Inventory's reconciler, so re-authorizing it would CHARGE the card and then fail confirm (EXPIRED) with no refund
 * until #4. Such orders are rejected (RESERVATION_EXPIRED) WITHOUT re-authorizing. `app.recovery.max-park` MUST be
 * <= the inventory hold TTL for this to hold. This also caps the otherwise-unbounded retry of a wedged order
 *
 * Async lifecycle (same shape as Inventory's ReservationReconciler):
 * - SupervisorJob scope: one failed sweep never stops the loop;
 * - per-order isolation: one failing order (e.g. the loser of a multi-pod @Version race) does not skip the rest;
 * - cooperative shutdown: on SIGTERM Spring calls [destroy], scope.cancel() ends the loop at the next delay. An
 *   iteration already in flight finishes its blocking gateway calls (not joined); since every transition commits
 *   independently and is idempotent/resumable, an abandoned iteration is safely resumed on restart;
 * - runs on Dispatchers.IO because [OrderSaga.handle] does BLOCKING work (HTTP gateway calls + JPA). This deliberately
 *   diverges from the older ReservationReconciler/OutboxPublisher (which use Dispatchers.Default): blocking I/O belongs
 *   on the elastic IO pool, not the CPU-bound Default pool (deck 06 blocking-vs-suspending / Kotlin dispatcher guidance).
 *
 * Gated by app.recovery.enabled so tests (which steer the fake gateways directly) keep the background loop OFF
 */

@Component // auto-detected by component scanning by Spring
class OrderRecoverySweeper(
    private val orders: OrderRepository,
    private val saga: OrderSaga,
    private val stateMachine: OrderStateMachine,
    private val payments: PaymentsGateway,
    private val meterRegistry: MeterRegistry,
    @Value("\${app.recovery.enabled}") private val enabled: Boolean,
    @Value("\${app.recovery.poll-interval}") private val pollInterval: Duration,
    @Value("\${app.recovery.max-park}") private val maxPark: Duration,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Outcome-tagged recovery counter (Pillar D, same idiom as InventoryService.outcomes): outcome = retried | expired
    private fun recovery(outcome: String) = meterRegistry.counter("orders_recovery_total", "outcome", outcome)

    // Compensation counter, same series as OrderSaga (Fase 2 #4): incremented when the expiry path reverses a live charge
    private fun compensations(type: String) = meterRegistry.counter("orders_compensations_total", "type", type)

    // starts the sweep loop once the app context is fully up (skips it entirely when disabled, e.g. in tests)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) {
            log.info("order recovery sweep disabled (app.recovery.enabled=false)")
            return
        }
        scope.launch {
            log.info("order recovery sweep started (every {}, max-park {})", pollInterval, maxPark)
            while (isActive) {
                // the only suspension point is this delay, OUTSIDE sweepOnce — cooperative-shutdown cancellation ends the loop here, never mid-order
                delay(pollInterval.toMillis())
                sweepOnce()
            }
        }
    }

    /**
     * One sweep pass. Package-visible so it can be driven directly by a test. Each order is wrapped in its own
     * try/catch so one failure (a lost @Version race, a transient DB/HTTP fault) is a per-order no-op, not a
     * batch-truncating event
     */
    internal fun sweepOnce() {
        val parked = try {
            orders.findByStatus(OrderStatus.PAYMENT_PENDING)
        } catch (e: Exception) {
            log.warn("order recovery sweep query failed, will retry: {}", e.message)
            return
        }
        if (parked.isEmpty()) return
        log.info("recovering {} PAYMENT_PENDING order(s)", parked.size)
        val now = Instant.now()
        for (order in parked) {
            try {
                if (Duration.between(order.updatedAt, now) > maxPark) {
                    // Past max-park the hold is surely gone (seats restocked by Inventory). Before any terminal reject,
                    // attempt an idempotent best-effort void (Fase 2 #4): a prior sweep may have re-authorized THEN crashed
                    // before voiding, so a live charge may exist. void only reverses, never charges, so money-safety holds
                    val voided = payments.void(order.id.toString())
                    when (voided.status) {
                        "VOIDED" -> {
                            // a live charge was reversed -> safe to finish the rejection
                            if (!voided.replayed) compensations("refund").increment()
                            recovery("expired").increment()
                            log.warn("order {} parked beyond {} — charge VOIDED, REJECTED(RESERVATION_EXPIRED)", order.id, maxPark)
                            stateMachine.reject(order.id, RejectionReason.RESERVATION_EXPIRED)
                        }
                        "NOT_FOUND", "DECLINED" -> {
                            // money state resolved: no charge ever existed -> reject money-safely WITHOUT re-authorizing
                            recovery("expired").increment()
                            log.warn("order {} parked beyond {} — no charge to reverse ({}), REJECTED(RESERVATION_EXPIRED)", order.id, maxPark, voided.status)
                            stateMachine.reject(order.id, RejectionReason.RESERVATION_EXPIRED)
                        }
                        else -> {
                            // UNAVAILABLE / FAILED: Payments unreachable -> we cannot confirm whether a charge is live, so do
                            // NOT go terminal and abandon it. Keep the order PARKED and retry the void next sweep (Fase 2 #4,
                            // Option B, team decision: money-safety over bounded retention). It drains once Payments recovers
                            recovery("void_retry").increment()
                            log.warn("order {} parked beyond {} but void was {} — keeping PARKED to retry the reversal (charge not abandoned)", order.id, maxPark, voided.status)
                        }
                    }
                } else {
                    recovery("retried").increment()
                    saga.handle(order.id)
                }
            } catch (e: Exception) {
                log.warn("recovery of order {} failed, will retry next sweep: {}", order.id, e.message)
            }
        }
    }

    // Called by Spring on context shutdown (SIGTERM): cancels the scope for a clean, cooperative stop
    override fun destroy() {
        log.info("cancelling order recovery sweep (cooperative shutdown)")
        scope.cancel()
    }
}
