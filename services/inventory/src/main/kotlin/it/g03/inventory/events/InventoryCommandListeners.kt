package it.g03.inventory.events

import it.g03.inventory.service.InventoryService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Kafka entry point for the saga commands Orders sends Inventory. Each listener deserialises the String payload and calls
 * the matching [InventoryService] method, which appends its reply to the transactional outbox in the SAME transaction.
 *
 * Offset semantics: enable-auto-commit=false + default container ack mode -> the offset commits only on a clean return.
 * A redelivery (at-least-once) re-runs the handler, which is idempotent (dedup on the reservation row) and re-emits the
 * reply (at-least-once reply recovery; Orders' reply handlers are idempotent). Business failures propagate to the
 * DefaultErrorHandler -> bounded retry -> inventory-dlt.
 *
 * autoStartup is property-driven so tests (no broker) keep the listeners off.
 */
@Component // registers this class as a Spring-managed bean
class InventoryCommandListeners(
    private val service: InventoryService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.RESERVE_SEATS], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onReserveSeats(payload: String) {
        val cmd = objectMapper.readValue(payload, ReserveSeatsCommand::class.java)
        service.reserve(cmd.orderId, cmd.tripCode, cmd.qty)
    }

    @KafkaListener(topics = [Topics.CONFIRM_SEATS], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onConfirmSeats(payload: String) {
        val cmd = objectMapper.readValue(payload, ConfirmSeatsCommand::class.java)
        service.confirm(cmd.orderId, cmd.tripCode)
    }

    @KafkaListener(topics = [Topics.RELEASE_SEATS], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onReleaseSeats(payload: String) {
        val cmd = objectMapper.readValue(payload, ReleaseSeatsCommand::class.java)
        service.release(cmd.orderId, cmd.tripCode)
    }
}
