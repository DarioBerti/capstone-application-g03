package it.g03.orders.service

import it.g03.orders.entities.OrderStatus
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

// the resumable saga states; a terminal order is never swept
private val NON_TERMINAL = listOf(OrderStatus.PENDING, OrderStatus.RESERVED, OrderStatus.AUTHORIZED)

/**
 * Re-drives the orders STUCK in a non-terminal state (a reply was lost — Kafka partition, a consumer crash, a
 * dropped DLT message). This is the liveness backstop of the async saga (Pillar C): the reply-driven pipeline
 * has no redelivery once an offset is committed, so without this a wedged order would sit non-terminal forever.
 * Every poll, an order whose `updatedAt` is older than the deadline has the command for its CURRENT state
 * re-emitted via [OrderStateMachine.redrive] (PENDING -> reserve-seats, RESERVED -> authorize-payment,
 * AUTHORIZED -> confirm-seats). Commands are idempotent downstream, so a re-send is always safe; the re-drive
 * bumps `updatedAt` so the same order is not swept again on the next poll.
 *
 * Async lifecycle (same shape as the outbox relay / Inventory's reconciler):
 * - SupervisorJob scope: one failed sweep never stops the loop;
 * - per-order isolation: one failing order (e.g. the loser of a @Version race against an in-flight reply) does
 *   not skip the rest;
 * - cooperative shutdown: on SIGTERM Spring calls [destroy], scope.cancel() ends the loop at the next delay. An
 *   iteration already in flight finishes; since every re-drive commits independently and is idempotent, an
 *   abandoned iteration is safely resumed on restart;
 * - runs on Dispatchers.IO because [OrderStateMachine.redrive] does BLOCKING JPA work, which belongs on the
 *   elastic IO pool, not the CPU-bound Default pool (deck 06 blocking-vs-suspending).
 *
 * Gated by app.timeout-sweeper.enabled so tests (which drive the state machine directly) keep the loop OFF.
 */
@Component // auto-detected by component scanning by Spring
class OrderTimeoutSweeper(
    private val orders: OrderRepository,
    private val stateMachine: OrderStateMachine,
    @Value("\${app.timeout-sweeper.enabled}") private val enabled: Boolean,
    @Value("\${app.timeout-sweeper.poll-interval}") private val pollInterval: Duration,
    @Value("\${app.timeout-sweeper.deadline}") private val deadline: Duration,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // starts the sweep loop once the app context is fully up (skips it entirely when disabled, e.g. in tests)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) {
            log.info("order timeout sweeper disabled (app.timeout-sweeper.enabled=false)")
            return
        }
        scope.launch {
            log.info("order timeout sweeper started (every {}, deadline {})", pollInterval, deadline)
            while (isActive) {
                // the only suspension point is this delay, OUTSIDE sweepOnce — cooperative-shutdown cancellation ends the loop here, never mid-order
                delay(pollInterval.toMillis())
                sweepOnce()
            }
        }
    }

    /**
     * One sweep pass. Package-visible so it can be driven directly by a test. Each order is wrapped in its own
     * try/catch so one failure (a lost @Version race, a transient DB fault) is a per-order no-op, not a
     * batch-truncating event.
     */
    internal fun sweepOnce() {
        val cutoff = Instant.now().minus(deadline)
        val stuck = try {
            orders.findByStatusInAndUpdatedAtBefore(NON_TERMINAL, cutoff)
        } catch (e: Exception) {
            log.warn("order timeout sweep query failed, will retry: {}", e.message)
            return
        }
        if (stuck.isEmpty()) return
        log.info("re-driving {} stuck order(s) older than the {} deadline", stuck.size, deadline)
        for (order in stuck) {
            try {
                stateMachine.redrive(order)
            } catch (e: Exception) {
                log.warn("re-drive of order {} failed, will retry next sweep: {}", order.id, e.message)
            }
        }
    }

    // Called by Spring on context shutdown (SIGTERM): cancels the scope for a clean, cooperative stop
    override fun destroy() {
        log.info("cancelling order timeout sweeper (cooperative shutdown)")
        scope.cancel()
    }
}
