package it.g03.payments.events

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

/**
 * Outbound eventing port -> payment-authorized is an INFORMATIONAL event (observability / eventing requirement): the saga decision travels in the synchronous response to Orders, so losing 1 of these events can never corrupt the money path. That is why a simple after-commit publish is enough here, while Orders (whose events ARE load-bearing) uses the transactional outbox instead
 */
interface EventPublisher {
    fun publish(topic: String, key: String, payload: Any)
}

@Configuration   // marks this class as a source of Spring bean definitions
class EventPublishingConfig {

    /**
     * Real Kafka publisher. Disabled with "events.publish.enabled=false" (used by tests, which have no broker: KafkaTemplate.send would block on metadata otherwise)
     */
    @Bean   // registers the returned object as a Spring-managed bean
    @ConditionalOnProperty(name = ["events.publish.enabled"], havingValue = "true", matchIfMissing = true)
    fun kafkaEventPublisher(kafka: KafkaTemplate<String, String>, om: ObjectMapper): EventPublisher =
        object : EventPublisher {
            private val log = LoggerFactory.getLogger("it.g03.payments.KafkaEventPublisher")
            override fun publish(topic: String, key: String, payload: Any) {
                // Bounded wait (no unbounded hang, Pillar C); failures are logged, not propagated because informational events must never fail the business call
                runCatching { kafka.send(topic, key, om.writeValueAsString(payload)).get(5, TimeUnit.SECONDS) }
                    .onFailure { log.warn("failed to publish {} for key {}: {}", topic, key, it.message) }
            }
        }

    /** No-op fallback when publishing is disabled (tests / broker-less local runs) */
    @Bean   // registers the returned object as a Spring-managed bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun noopEventPublisher(): EventPublisher = object : EventPublisher {
        private val log = LoggerFactory.getLogger("it.g03.payments.NoopEventPublisher")
        override fun publish(topic: String, key: String, payload: Any) {
            log.debug("event publishing disabled, dropping {} for key {}", topic, key)
        }
    }
}
