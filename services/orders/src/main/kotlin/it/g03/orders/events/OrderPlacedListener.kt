package it.g03.orders.events

import it.g03.orders.service.OrderSaga
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Kafka entry point of the async pipeline: consumes order-placed (published by our own outbox relay -> "listen to yourself" via the outbox) and runs the saga
 *
 * Offset semantics: enable-auto-commit=false + default container ack mode -> the offset is committed only after this method returns WITHOUT throwing. A crash mid-saga means redelivery (at-least-once), which is safe because the saga is resumable and every downstream call is idempotent
 *
 * autoStartup is property-driven so tests (no broker) can keep the listener off
 */

@Component // registers this class as a Spring-managed bean
class OrderPlacedListener(
    private val saga: OrderSaga,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.ORDER_PLACED], autoStartup = "\${app.kafka-listeners-enabled:true}") // consumes order-placed; offset commits only on a clean return (at-least-once)
    
    // Deserialises the event and hands the orderId to the saga
    fun onOrderPlaced(payload: String) {
        val event = objectMapper.readValue(payload, OrderPlacedEvent::class.java)
        saga.handle(UUID.fromString(event.orderId))
    }
}
