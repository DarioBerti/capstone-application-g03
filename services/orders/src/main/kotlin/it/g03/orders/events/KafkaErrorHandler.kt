package it.g03.orders.events

import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Bounded consumer retry + dead-letter sink — the resilience layer that REPLACES the retired Orders->Payments
 * circuit breaker now that the money path is async (Pillar C). A reply that keeps failing is retried a fixed
 * number of times, then published to a single fixed dead-letter topic (`orders-dlt`) instead of
 * head-of-line-blocking the partition.
 *
 * Boot auto-wires the single [org.springframework.kafka.listener.CommonErrorHandler] bean into the listener
 * container factory. Inert in tests: the listeners do not start (autoStartup=false) so the recoverer never
 * touches the (dummy) broker.
 */
@Configuration
class KafkaErrorHandler {

    @Bean
    fun ordersDltErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        // Fixed destination: every dead letter goes to orders-dlt (partition -1 = let the producer choose by key).
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ -> TopicPartition(DLT_TOPIC, -1) }
        // 3 retries, 500ms apart, then recover to the DLT (bounded — no retry storm).
        return DefaultErrorHandler(recoverer, FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES))
    }

    companion object {
        const val DLT_TOPIC = "orders-dlt"
        private const val RETRY_INTERVAL_MS = 500L
        private const val MAX_RETRIES = 3L
    }
}
