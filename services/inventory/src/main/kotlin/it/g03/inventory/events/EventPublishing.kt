package it.g03.inventory.events

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.TimeUnit

/**
 * Raw publisher used by the outbox relay. Takes pre-serialised JSON (the outbox stores payloads already serialised inside
 * the business transaction) and PROPAGATES failures: the relay must NOT mark an event as published if the send was not
 * acked. This REPLACES the earlier best-effort informational publisher — the saga replies are load-bearing now, not telemetry.
 */
interface EventPublisher {
    /** Publish and wait for the broker ack (bounded); THROWS on failure. */
    fun publish(topic: String, key: String, jsonPayload: String)
}

@Configuration // declares this class as a source of Spring bean definitions
class EventPublishingConfig {

    // Real Kafka-backed publisher (active unless events.publish.enabled is explicitly false, as always)
    @Bean // exposes the returned object as a Spring-managed bean
    @ConditionalOnProperty(name = ["events.publish.enabled"], havingValue = "true", matchIfMissing = true) // only when publishing is enabled (default true)
    fun kafkaEventPublisher(kafka: KafkaTemplate<String, String>): EventPublisher =
        object : EventPublisher {
            override fun publish(topic: String, key: String, jsonPayload: String) {
                // .get() = wait for the "acks=all" acknowledgement, bounded (no unbounded hang, Pillar C)
                // Exceptions propagate to the relay, which stops the batch and retries on the next tick (at-least-once)
                kafka.send(topic, key, jsonPayload).get(5, TimeUnit.SECONDS)
            }
        }

    /** No-op fallback when publishing is disabled (tests / broker-less local runs) [registered only if no other EventPublisher exists]. */
    @Bean
    @ConditionalOnMissingBean(EventPublisher::class) // only when no other EventPublisher bean exists (es. Kafka one is off)
    fun noopEventPublisher(): EventPublisher = object : EventPublisher {
        private val log = LoggerFactory.getLogger("it.g03.inventory.NoopEventPublisher")
        override fun publish(topic: String, key: String, jsonPayload: String) {
            log.debug("event publishing disabled, dropping {} for key {}", topic, key)
        }
    }
}
