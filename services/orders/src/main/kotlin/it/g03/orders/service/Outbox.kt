package it.g03.orders.service

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import it.g03.orders.entities.OutboxEvent
import it.g03.orders.repository.OutboxEventRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Appends an event to the outbox table.
 * MUST be called inside an active transaction (it participates in the caller's @Transactional): that is the
 * whole point of the pattern — the business change and its event commit or roll back TOGETHER.
 */
@Component // registers this class as a Spring-managed bean
class Outbox(
    private val repository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    // nullable: tracing is optional wiring — a context without the tracing auto-config still boots
    private val tracer: Tracer? = null,
    private val propagator: Propagator? = null,
) {
    // Serialises the payload to JSON and saves an outbox row in the CALLER's transaction.
    // event_type = the payload class name (cheap per-type metrics/queries downstream).
    fun append(topic: String, aggregateId: String, payload: Any) {
        repository.save(
            OutboxEvent(
                topic = topic,
                aggregateId = aggregateId,
                eventType = payload::class.simpleName,
                payload = objectMapper.writeValueAsString(payload),
                traceparent = currentTraceparent(),
            ),
        )
    }

    // The app does not produce to Kafka (Debezium CDC does): persisting the W3C context with the event is
    // the only way the trace crosses the CDC hop (the EventRouter turns the column into a Kafka header).
    // Null when no span is active (e.g. a timeout-sweeper re-drive) -> the consumer starts a fresh trace.
    private fun currentTraceparent(): String? {
        val context = tracer?.currentSpan()?.context() ?: return null
        if (propagator == null) return null
        val carrier = mutableMapOf<String, String>()
        propagator.inject(context, carrier) { c, key, value -> c?.put(key, value) }
        return carrier["traceparent"]
    }
}
