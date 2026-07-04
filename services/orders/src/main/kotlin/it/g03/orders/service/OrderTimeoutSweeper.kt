package it.g03.orders.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import it.g03.orders.entities.OrderStatus
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

// The resumable POST-PAY saga states. A DRAFT/HELD order is awaiting the USER (not "stuck") and is handled by the
// checkout-window sweeper, not re-driven here; a terminal order is never swept.
private val RESUMABLE_POST_PAY = listOf(OrderStatus.PAYING, OrderStatus.AUTHORIZED)

/**
 * Re-drives the post-pay orders STUCK in a resumable state (a reply was lost — Kafka partition, a consumer crash,
 * a dropped DLT message). This is the liveness backstop of the async saga (Pillar C): the reply-driven pipeline
 * has no redelivery once an offset is committed, so without this a wedged order would sit non-terminal forever.
 * Every poll, an order whose `updatedAt` predates the deadline has the command for its CURRENT state re-emitted
 * via [OrderStateMachine.redrive] (PAYING -> authorize-payment; AUTHORIZED -> confirm-seats for each still-held
 * leg, or capture-payment if all legs are already confirmed). Commands are idempotent downstream, so a re-send is
 * always safe; the re-drive bumps `updatedAt` so the same order is not swept again on the next poll.
 *
 * This is the RECOVER-within-the-window half of the two-timer design; [CheckoutExpirySweeper] is the GIVE-UP-at-
 * the-window half. Driven by Spring `@Scheduled` — a plain blocking DB poll; gated by `app.timeout-sweeper.enabled`
 * (tests set it false and drive the state machine directly). Each order is re-driven in its own try/catch so one
 * failure never truncates the batch.
 */
@Component
class OrderTimeoutSweeper(
    private val orders: OrderRepository,
    private val stateMachine: OrderStateMachine,
    meterRegistry: MeterRegistry,
    @Value("\${app.timeout-sweeper.enabled}") private val enabled: Boolean,
    @Value("\${app.timeout-sweeper.deadline}") private val deadline: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Saga-completion SLI: stuck orders found by the LAST sweep. Non-zero sustained = re-driving is not
    // resolving them (something on the money path is down) — the paged symptom even at zero traffic,
    // where the terminal-ratio SLI is NaN.
    private val overdue = AtomicInteger(0)

    init {
        Gauge.builder("orders_overdue_nonterminal", overdue) { it.get().toDouble() }
            .description("Non-terminal orders past the saga deadline found by the last sweep")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${app.timeout-sweeper.poll-interval}")
    fun sweep() {
        if (!enabled) return
        sweepOnce()
    }

    /**
     * One sweep pass. Package-visible so it can be driven directly by a test. Each order is wrapped in its own
     * try/catch so one failure (a lost @Version race, a transient DB fault) is a per-order no-op, not a
     * batch-truncating event.
     */
    internal fun sweepOnce() {
        val cutoff = Instant.now().minus(deadline)
        val stuck = try {
            orders.findByStatusInAndUpdatedAtBefore(RESUMABLE_POST_PAY, cutoff)
        } catch (e: Exception) {
            log.warn("order timeout sweep query failed, will retry: {}", e.message)
            return
        }
        overdue.set(stuck.size)
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
}
