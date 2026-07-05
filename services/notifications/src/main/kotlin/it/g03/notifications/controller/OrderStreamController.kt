package it.g03.notifications.controller

import it.g03.notifications.gateway.OrdersClient
import it.g03.notifications.service.OrderStreamHub
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * SSE surface: the browser opens ONE stream per order and receives a booking snapshot whenever the saga
 * advances (pushed by [OrderStreamHub] as order-status-changed events arrive off Kafka), replacing the SPA's
 * old status polling. Before opening, it verifies the caller OWNS the order (via [OrdersClient], forwarding the
 * caller's token) so a user can't subscribe to someone else's booking — a foreign/unknown order answers 404,
 * not leaking existence. Relay logic lives in the hub + listener.
 */
@RestController
@RequestMapping("/api/notifications")
class OrderStreamController(
    private val hub: OrderStreamHub,
    private val ordersClient: OrdersClient,
) {

    // GET /api/notifications/orders/{orderId}/events -> a text/event-stream of the booking's status snapshots.
    @GetMapping("/orders/{orderId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable orderId: String, @AuthenticationPrincipal jwt: Jwt): SseEmitter {
        if (!ordersClient.isOwner(orderId, jwt.tokenValue)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown order: $orderId")
        }
        return hub.open(orderId)
    }
}
