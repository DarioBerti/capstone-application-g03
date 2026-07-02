package it.g03.orders.events

import it.g03.orders.service.OrderSaga
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Kafka entry points for the saga replies from Inventory/Payments. Each listener deserialises the JSON reply
 * and dispatches to the matching [OrderSaga] handler, which advances the @Transactional state machine.
 *
 * Offset semantics: enable-auto-commit=false + the default container ack mode -> the offset is committed only
 * after the method returns WITHOUT throwing. A crash/exception mid-handle is at-least-once redelivery, which is
 * safe (every transition is guarded + idempotent). An exception that survives the bounded consumer retry is
 * routed to `orders-dlt` by [KafkaErrorHandler] — we deliberately let it propagate here rather than swallow it.
 *
 * autoStartup is property-driven so tests (no broker) keep the listeners off.
 */
@Component // registers this class as a Spring-managed bean
class SagaReplyListeners(
    private val saga: OrderSaga,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.RESERVATION_RESULT], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onReservationResult(payload: String) {
        val event = objectMapper.readValue(payload, ReservationResultEvent::class.java)
        saga.onReservationResult(UUID.fromString(event.orderId), event.status)
    }

    @KafkaListener(topics = [Topics.PAYMENT_RESULT], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onPaymentResult(payload: String) {
        val event = objectMapper.readValue(payload, PaymentResultEvent::class.java)
        saga.onPaymentResult(UUID.fromString(event.orderId), event.status)
    }

    @KafkaListener(topics = [Topics.SEATS_CONFIRMED], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onSeatsConfirmed(payload: String) {
        val event = objectMapper.readValue(payload, SeatsConfirmedEvent::class.java)
        saga.onSeatsConfirmed(UUID.fromString(event.orderId), event.status)
    }

    @KafkaListener(topics = [Topics.PAYMENT_CAPTURED], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onPaymentCaptured(payload: String) {
        val event = objectMapper.readValue(payload, PaymentCapturedEvent::class.java)
        saga.onPaymentCaptured(UUID.fromString(event.orderId))
    }
}
