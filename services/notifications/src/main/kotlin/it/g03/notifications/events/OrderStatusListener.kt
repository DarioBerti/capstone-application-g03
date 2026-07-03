package it.g03.notifications.events

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import it.g03.notifications.service.OrderStreamHub
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Minimal routing view of Orders' order-status-changed snapshot: we only need the orderId (to find the open
 * streams) and the status (to know when to close them). Every other field is relayed to the browser verbatim,
 * so we ignore unknowns instead of mirroring the full OrderResponse shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderStatusRouting(val orderId: String, val status: String)

/**
 * Consumes order-status-changed (Orders' per-transition booking snapshot) and pushes it to any browser
 * streaming that order over SSE. A PURE SINK: it relays the event, never calls back into Orders. It runs on its
 * OWN consumer group with auto.offset.reset=latest, so a fresh pod ignores history (a browser gets its initial
 * state from the Orders HTTP response, not a Kafka replay). Same never-throw contract as the mail sink: a
 * malformed payload is logged and skipped rather than stalling the partition.
 */
@Component
class OrderStatusListener(
    private val objectMapper: ObjectMapper,
    private val hub: OrderStreamHub,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["order-status-changed"],
        groupId = "notifications-sse",
        autoStartup = "\${app.kafka-listeners-enabled:true}",
        properties = ["auto.offset.reset=latest"],
    )
    fun onOrderStatusChanged(payload: String) {
        val routing = try {
            objectMapper.readValue(payload, OrderStatusRouting::class.java)
        } catch (e: Exception) {
            log.warn("skipping unparseable order-status-changed payload: {}", e.message)
            return
        }
        hub.push(routing.orderId, payload) // relay the FULL snapshot JSON verbatim to the browser
        if (routing.status == STATUS_CONFIRMED || routing.status == STATUS_REJECTED) {
            hub.complete(routing.orderId) // terminal: close the streams so the browser stops
        }
    }

    private companion object {
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_REJECTED = "REJECTED"
    }
}
