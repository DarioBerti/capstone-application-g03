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
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * The OUTBOX RELAY = the second half of the transactional-outbox pattern, implemented as a Kotlin **Flow** pipeline (Pillar A): a ticker Flow drives periodic drains inside a supervised CoroutineScope (the failure domain), cancelled cooperatively on SIGTERM
 *
 * Delivery guarantee: AT-LEAST-ONCE. Mark-as-published happens only AFTER the broker ack (publish throws otherwise); a crash between send and mark causes a re-send, which consumers absorb because they are idempotent
 * On a failed send we STOP the batch (not skip): publishing the next event first would break per-order event ordering!
 */

@Component // registers this class as a Spring-managed bean
class OutboxPublisher(
    private val outbox: OutboxEventRepository,
    private val events: EventPublisher,
    @Value("\${app.outbox.poll-interval}") private val pollInterval: Duration,
    @Value("\${app.outbox.enabled}") private val enabled: Boolean,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    // Dispatchers.IO: each drain does BLOCKING work (JPA findTop50/save + the Kafka ack wait .get(5s)), which belongs on
    // the elastic IO pool, not the CPU-bound Default pool (deck 06 blocking-vs-suspending). Matches OrderRecoverySweeper
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cold ticker Flow: emits Unit every [pollInterval] until the scope is cancelled
    private fun ticker(interval: Duration) = flow {
        while (currentCoroutineContext().isActive) {
            delay(interval.toMillis())
            emit(Unit)
        }
    }

    @EventListener(ApplicationReadyEvent::class) // runs once the app is fully started (skips the relay if disabled)
    
    // Launches the periodic drain loop on the supervised scope
    fun start() {
        if (!enabled) {
            log.info("outbox relay disabled (app.outbox.enabled=false)")
            return
        }
        log.info("outbox relay started (poll every {})", pollInterval)
        ticker(pollInterval)
            .onEach {
                // Supervisor semantics: a failed drain logs and waits for the next tick
                // The only suspension point (delay) is inside the ticker, so cooperative cancellation is never swallowed by this runCatching
                runCatching { drainOnce() }
                    .onFailure { log.warn("outbox drain failed, will retry: {}", it.message) }
            }
            .launchIn(scope)
    }

    /**
     * One drain pass; also called directly by tests. Returns how many events were published and marked
     */
    fun drainOnce(): Int {
        val batch = outbox.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()
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
        return published
    }

    // DisposableBean hook: cooperatively cancels the relay scope on SIGTERM/shutdown
    override fun destroy() {
        log.info("cancelling outbox relay (cooperative shutdown)")
        scope.cancel()
    }
}
