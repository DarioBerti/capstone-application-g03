package it.g03.orders.service

import it.g03.orders.entities.OutboxEvent
import it.g03.orders.repository.OutboxEventRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Appends an event to the outbox table.
 * MUST be called inside an active transaction (it participates in the caller's @Transactional): that is the whole point of the pattern, the business change and its event commit or roll back TOGETHER
 */

@Component // registers this class as a Spring-managed bean
class Outbox(
    private val repository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    // Serialises the payload to JSON and saves an outbox row in the CALLER's transaction
    fun append(topic: String, aggregateId: String, payload: Any) {
        repository.save(
            OutboxEvent(
                topic = topic,
                aggregateId = aggregateId,
                payload = objectMapper.writeValueAsString(payload),
            ),
        )
    }
}
