package it.g03.payments.events

import it.g03.payments.gateway.LineItem
import it.g03.payments.service.PaymentsService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Kafka entry points of the async pipeline: consume the saga commands Orders emits and call the matching
 * service method. Each method, inside its @Transactional, appends its reply to the outbox.
 *
 * Offset semantics: enable-auto-commit=false + default container ack mode -> the offset is committed only after
 * the method returns WITHOUT throwing. A crash mid-handling means redelivery (at-least-once), which is safe
 * because every service method is idempotent and re-emits its reply on replay. Business exceptions are NOT
 * swallowed here — they propagate to the DefaultErrorHandler (bounded retry -> payments-dlt).
 *
 * autoStartup is property-driven so tests (no broker) can keep the listeners off.
 */
@Component // registers this class as a Spring-managed bean
class PaymentCommandListeners(
    private val service: PaymentsService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.AUTHORIZE_PAYMENT], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onAuthorizePayment(payload: String) {
        val command = objectMapper.readValue(payload, AuthorizePaymentCommand::class.java)
        service.authorize(command.orderId, command.amountCents, command.items.map { LineItem(it.name, it.quantity, it.unitAmountCents) })
    }

    @KafkaListener(topics = [Topics.CAPTURE_PAYMENT], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onCapturePayment(payload: String) {
        val command = objectMapper.readValue(payload, CapturePaymentCommand::class.java)
        service.capture(command.orderId)
    }

    @KafkaListener(topics = [Topics.CANCEL_AUTHORIZATION], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onCancelAuthorization(payload: String) {
        val command = objectMapper.readValue(payload, CancelAuthorizationCommand::class.java)
        service.cancelAuthorization(command.orderId)
    }
}
