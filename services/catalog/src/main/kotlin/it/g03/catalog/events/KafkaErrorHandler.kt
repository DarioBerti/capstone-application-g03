package it.g03.catalog.events

import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Bounded retry + dead-letter for the availability listener (Pillar C): a poison payload (or a bug in the
 * projection) is retried [RETRIES] times then parked on [Topics.CATALOG_DLT] so it can never head-of-line-block
 * the partition. The DLT publish is the only thing Catalog puts on Kafka — operational dead-lettering, not a
 * domain event — so the "Catalog produces no events / off the money path" invariant still holds.
 *
 * Boot auto-wires the single CommonErrorHandler bean into the listener container factory. Inert in tests: the
 * listener does not start (app.kafka-listeners-enabled=false), so this handler is never exercised.
 */
@Configuration
class KafkaErrorHandler {

    @Bean
    fun availabilityErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        // partition -1 = let the broker pick the DLT partition (no fixed mapping from the source partition)
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ -> TopicPartition(Topics.CATALOG_DLT, -1) }
        return DefaultErrorHandler(recoverer, FixedBackOff(BACKOFF_MS, RETRIES))
    }

    private companion object {
        const val BACKOFF_MS = 500L
        const val RETRIES = 3L
    }
}
