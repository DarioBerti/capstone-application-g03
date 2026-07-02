package it.g03.payments.events

import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Bounded consumer retry + dead-letter routing (Pillar C) — the resilience layer that replaces the retired
 * Orders->Payments circuit breaker now the surface is async. A command that keeps throwing is retried a few
 * times with backoff, then parked on `payments-dlt` instead of blocking the partition forever.
 *
 * Spring Boot auto-wires this single CommonErrorHandler bean into the Kafka listener container factory. It is
 * inert in tests (listeners are off, no broker).
 */
@Configuration // declares this class as a source of Spring bean definitions
class KafkaErrorHandler {

    @Bean
    fun errorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        // Route every exhausted record to a fixed DLT topic; partition -1 lets Kafka pick the partition.
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { _, _ ->
            TopicPartition(Topics.PAYMENTS_DLT, -1)
        }
        // 3 retries, 500ms apart, then recover to the DLT (bounded — no retry storm, no unbounded park).
        return DefaultErrorHandler(recoverer, FixedBackOff(500L, 3L))
    }
}
