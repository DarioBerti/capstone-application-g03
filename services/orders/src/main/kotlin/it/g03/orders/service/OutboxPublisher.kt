package it.g03.orders.service

import it.g03.orders.events.EventPublisher
import it.g03.orders.repository.OutboxEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * OUTBOX RELAY — the background half of the transactional-outbox pattern (Pillar A).
 *
 * THE PROBLEM IT SOLVES (the "dual-write" problem): when Orders accepts an order it must do two things —
 * save the order row in its own database AND announce that order to the rest of the system over Kafka.
 * Doing both directly is unsafe: if the process crashes in between (DB committed but Kafka send lost, or
 * the reverse), the two stores disagree forever — an order with no event, or an event for an order that
 * rolled back.
 *
 * THE FIX: the order-placing code never publishes to Kafka inline. Instead it writes the event as a row in
 * the `outbox_events` table inside the SAME database transaction as the order, so the business change and
 * its "still-to-be-published" event commit atomically — both, or neither.
 *
 * WHAT THIS CLASS DOES: it is the second half of that pattern — a background loop that periodically reads
 * the not-yet-published rows out of `outbox_events`, does the actual Kafka publish, and marks each row
 * published. It is built as a Kotlin Flow pipeline: a ticker Flow emits on a fixed interval, every tick runs
 * one drain pass ([drainOnce]), and the whole thing lives in a supervised CoroutineScope that is cancelled
 * cleanly on shutdown/SIGTERM (in-flight work finishes or is cancelled — never left orphaned).
 *
 * THE THREE GUARANTEES IT UPHOLDS:
 *  - AT-LEAST-ONCE delivery: a row is marked published only AFTER the broker acknowledges the send, so a
 *    crash in between re-sends the event on the next pass. Consumers are idempotent, so a duplicate is
 *    harmless — we never risk losing an event (at-most-once).
 *  - ORDERING preserved: if one send fails mid-batch we STOP the batch instead of skipping ahead, so two
 *    events for the same order can never be published out of order.
 *  - SAFE ACROSS REPLICAS: each drain claims its batch with FOR UPDATE SKIP LOCKED
 *    ([OutboxEventRepository.lockUnpublishedBatch]) inside one transaction, so if two relay replicas run at
 *    once the second one skips the locked rows rather than double-publishing them.
 */

@Component
class OutboxPublisher(
    private val outbox: OutboxEventRepository,
    private val events: EventPublisher,
    transactionManager: PlatformTransactionManager,
    @Value("\${app.outbox.poll-interval}") private val pollInterval: Duration,
    @Value("\${app.outbox.enabled}") private val enabled: Boolean,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val transactionTemplate = TransactionTemplate(transactionManager)

    private fun ticker(interval: Duration) = flow {
        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            emit(Unit)
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) {
            log.info("outbox relay disabled (app.outbox.enabled=false)")
            return
        }
        log.info("outbox relay started (poll every {})", pollInterval)
        ticker(pollInterval)
            .onEach {
                runCatching { drainOnce() }
                    .onFailure { log.warn("outbox drain failed, will retry: {}", it.message) }
            }
            .launchIn(scope)
    }

    /**
     * One drain pass; also called directly by tests. Returns how many events were published and marked.
     *
     * The whole pass runs in ONE transaction: [OutboxEventRepository.lockUnpublishedBatch] takes a FOR UPDATE SKIP
     * LOCKED lock on each returned row, so a second relay replica draining concurrently SKIPS those rows instead
     * of re-publishing them (the fix for double-publish under >1 replica). Locks release on commit, by which point
     * the rows are marked published. Still at-least-once: a crash after send but before commit re-sends the batch.
     */
    fun drainOnce(): Int = transactionTemplate.execute { _ ->
        val batch = outbox.lockUnpublishedBatch(PageRequest.of(0, BATCH_SIZE))
        var published = 0
        for (event in batch) {
            try {
                events.publish(event.topic, event.aggregateId, event.payload)
            } catch (e: Exception) {
                log.warn("publish of {} ({}) failed, stopping batch to preserve ordering: {}",
                    event.topic, event.aggregateId, e.message)
                break
            }
            event.publishedAt = Instant.now()
            outbox.save(event)
            published++
        }
        if (published > 0) log.debug("outbox relay published {} event(s)", published)
        published
    } ?: 0

    override fun destroy() {
        log.info("cancelling outbox relay (cooperative shutdown)")
        scope.cancel()
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}
