package it.g03.payments.service

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
            ),
        )
    }
}
