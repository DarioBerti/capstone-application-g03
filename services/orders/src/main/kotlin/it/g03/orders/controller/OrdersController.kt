package it.g03.orders.controller

import it.g03.orders.dto.OrderResponse
import it.g03.orders.dto.PlaceOrderRequest
import it.g03.orders.dto.toResponse
import it.g03.orders.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * PUBLIC API (exposed through the ingress at /api/orders).
 *
 * POST returns **202 Accepted**: the user gets an immediate, durable acknowledgement (order + outbox event
 * committed) while reservation/payment/confirmation proceed through the Kafka-driven pipeline. The buyer
 * identity is taken from the authenticated JWT (never the request body); an optional `Idempotency-Key`
 * header makes a retried POST replay the same order instead of creating a duplicate.
 *
 * GET lets clients (and the demo) observe the order reaching a terminal state.
 */
@RestController
@RequestMapping("/api/orders") // sets the base path for every endpoint defined in this controller
class OrdersController(private val service: OrderService) {

    // POST /api/orders -> places an order and returns 202 Accepted (the saga proceeds asynchronously)
    @PostMapping
    fun place(
        @RequestBody request: PlaceOrderRequest,
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<OrderResponse> {
        // Buyer identity comes from the validated token, not the client body (realm-import gotcha: guard a
        // missing `sub` with a 400 instead of an NPE). `email` may be absent — that is fine, we snapshot null.
        val buyerSub = jwt.subject ?: throw IllegalArgumentException("token is missing the subject (sub) claim")
        val buyerEmail = jwt.getClaimAsString("email")
        val order = service.placeOrder(request.tripCode, request.qty, buyerSub, buyerEmail, idempotencyKey)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(order.toResponse())
    }

    // GET /api/orders/{orderId} -> looks up a single order by its id (lets clients observe the terminal state)
    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: UUID): OrderResponse = service.get(orderId).toResponse()
}
