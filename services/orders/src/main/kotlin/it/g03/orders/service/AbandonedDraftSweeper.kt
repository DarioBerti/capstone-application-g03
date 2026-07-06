package it.g03.orders.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Housekeeping sweep that hard-deletes DRAFT bookings abandoned for longer than `app.draft-sweeper.max-age`:
 * empty carts that never became a booking (no seats held, no money, outside the Debezium publication, so removing
 * them emits nothing and touches no money-path invariant). Unlike [CheckoutExpirySweeper] and [OrderTimeoutSweeper]
 * this reclaims STORAGE, not correctness, so it runs on a slow cadence and does its work in one bulk delete
 * ([OrderStateMachine.discardAbandonedDrafts]) rather than per-order.
 *
 * Driven by Spring `@Scheduled`; gated by `app.draft-sweeper.enabled` (tests set it false and drive [sweepOnce]
 * directly). The poll interval is kept far shorter than the max-age on purpose: `@Scheduled(fixedDelay)` starts
 * its clock at application startup, so on a cluster that redeploys often a multi-day interval could otherwise
 * never fire.
 */
@Component
class AbandonedDraftSweeper(
    private val stateMachine: OrderStateMachine,
    @Value("\${app.draft-sweeper.enabled}") private val enabled: Boolean,
    @Value("\${app.draft-sweeper.max-age}") private val maxAge: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.draft-sweeper.poll-interval}")
    fun sweep() {
        if (!enabled) return
        sweepOnce()
    }

    // One sweep pass. Package-visible so a test can drive it directly.
    internal fun sweepOnce() {
        val cutoff = Instant.now().minus(maxAge)
        val removed = try {
            stateMachine.discardAbandonedDrafts(cutoff)
        } catch (e: Exception) {
            log.warn("abandoned-draft sweep failed, will retry next poll: {}", e.message)
            return
        }
        if (removed > 0) log.info("discarded {} abandoned draft booking(s) older than {}", removed, maxAge)
    }
}
