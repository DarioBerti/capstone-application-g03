package it.g03.inventory.events

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Outbound event publisher for informational events: "inventory-reserved" and "inventory-released"
 *
 * These events are for observability and notifications only. The main saga decision is returned synchronously to Orders, so publishing these events can be "best-effort" after commit. Critical order events use a transactional outbox instead
 */

// The port: the service depends on this abstraction, not on Kafka directly (swappable in tests)
interface EventPublisher {
    fun publish(topic: String, key: String, payload: Any)
}

/** "inventory-reserved" event payload */
data class InventoryReservedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val productId: String,
    val qty: Int,
    val occurredAt: Instant = Instant.now(),
)

/** "inventory-released" event payload ("reason": released | expired) */
data class InventoryReleasedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val productId: String,
    val qty: Int,
    val reason: String,
    val occurredAt: Instant = Instant.now(),
)

@Configuration  // declares Spring beans for event publishing
class EventPublishingConfig {

    /**
     * Real Kafka publisher. Disabled with "events.publish.enabled=false" (used by tests, which have no broker: KafkaTemplate.send would block on metadata otherwise) [so, registered only when events.publish.enabled is true (the default), i.e. in real runs]
     */
    @Bean
    @ConditionalOnProperty(name = ["events.publish.enabled"], havingValue = "true", matchIfMissing = true)
    fun kafkaEventPublisher(kafka: KafkaTemplate<String, String>, om: ObjectMapper): EventPublisher =
        object : EventPublisher {
            private val log = LoggerFactory.getLogger("it.g03.inventory.KafkaEventPublisher")
            override fun publish(topic: String, key: String, payload: Any) {
                // Wait max 5s for Kafka; do not fail the business call on error
                runCatching { kafka.send(topic, key, om.writeValueAsString(payload)).get(5, TimeUnit.SECONDS) }
                    .onFailure { log.warn("failed to publish {} for key {}: {}", topic, key, it.message) }
            }
        }

    /** No-op fallback when publishing is disabled (tests / local runs) [so, registered only if no other EventPublisher exists (i.e. when the Kafka one is disabled)] */
    @Bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun noopEventPublisher(): EventPublisher = object : EventPublisher {
        private val log = LoggerFactory.getLogger("it.g03.inventory.NoopEventPublisher")
        override fun publish(topic: String, key: String, payload: Any) {
            log.debug("event publishing disabled, dropping {} for key {}", topic, key)
        }
    }
}
