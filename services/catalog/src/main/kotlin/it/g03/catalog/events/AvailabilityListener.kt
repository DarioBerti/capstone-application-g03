package it.g03.catalog.events

import it.g03.catalog.service.CatalogService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Consumes Inventory's `seat-availability` events and projects them into the local availability read-model.
 * Read-side only: this never touches the money path (checkout never blocks on Catalog or this consumer's lag).
 *
 * Offset semantics mirror the other services: enable-auto-commit=false + default container ack mode -> the
 * offset commits only on a clean return; a redelivery (at-least-once) re-applies, which is idempotent and
 * out-of-order-safe (the projection is guarded by event time). Failures propagate to the DefaultErrorHandler
 * -> bounded retry -> catalog-dlt. autoStartup is property-driven so tests (no broker) keep the listener off.
 */
@Component
class AvailabilityListener(
    private val service: CatalogService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.SEAT_AVAILABILITY], autoStartup = "\${app.kafka-listeners-enabled:true}")
    fun onAvailabilityChanged(payload: String) {
        val event = objectMapper.readValue(payload, AvailabilityChangedEvent::class.java)
        service.applyAvailability(event.tripCode, event.available, event.occurredAt)
    }
}
