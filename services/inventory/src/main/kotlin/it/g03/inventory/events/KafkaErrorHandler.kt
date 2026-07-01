package it.g03.inventory.events

import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Bounded retry + dead-letter for the command listeners — the async replacement for the retired Orders->Payments
 * circuit breaker (Pillar C). A command that keeps failing (a poison message, or a downstream that stays down past the
 * retries) is NOT retried forever: after [RETRIES] redeliveries the record is routed to the fixed [Topics.INVENTORY_DLT]
 * topic and the offset advances, so a single bad message can never head-of-line-block the partition.
 *
 * Boot auto-wires the single CommonErrorHandler bean into the listener container factory. Inert in tests: the listeners
 * do not start (app.kafka-listeners-enabled=false), so this handler is never exercised.
 */
@Configuration // declares this class as a source of Spring bean definitions
class KafkaErrorHandler {

    @Bean
    fun commandErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        // partition -1 = let the broker pick the DLT partition (no fixed mapping from the source partition)
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ -> TopicPartition(Topics.INVENTORY_DLT, -1) }
        return DefaultErrorHandler(recoverer, FixedBackOff(BACKOFF_MS, RETRIES))
    }

    private companion object {
        const val BACKOFF_MS = 500L
        const val RETRIES = 3L
    }
}
