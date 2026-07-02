package it.g03.payments.service

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import it.g03.payments.entities.OutboxEvent
import it.g03.payments.repository.OutboxEventRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Appends a reply event to the outbox table.
 * MUST be called inside an active transaction (it participates in the caller's @Transactional): that is the
 * whole point of the pattern — the business change and its reply commit or roll back TOGETHER.
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

    // The app does not produce to Kafka (Debezium CDC does): persisting the W3C context with the reply is
    // the only way the trace crosses the CDC hop (the EventRouter turns the column into a Kafka header).
    // With listener observation enabled, the reply joins the trace of the command being handled.
    private fun currentTraceparent(): String? {
        val context = tracer?.currentSpan()?.context() ?: return null
        if (propagator == null) return null
        val carrier = mutableMapOf<String, String>()
        propagator.inject(context, carrier) { c, key, value -> c?.put(key, value) }
        return carrier["traceparent"]
    }
}
