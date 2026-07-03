package it.g03.notifications.controller

import it.g03.notifications.service.OrderStreamHub
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * SSE surface: the browser opens ONE stream per order and receives a booking snapshot whenever the saga
 * advances (pushed by [OrderStreamHub] as order-status-changed events arrive off Kafka), replacing the SPA's
 * old status polling. HTTP-only — it just hands back the emitter; all relay logic lives in the hub + listener.
 */
@RestController
@RequestMapping("/api/notifications")
class OrderStreamController(private val hub: OrderStreamHub) {

    // GET /api/notifications/orders/{orderId}/events -> a text/event-stream of the booking's status snapshots.
    @GetMapping("/orders/{orderId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable orderId: String): SseEmitter = hub.open(orderId)
}
