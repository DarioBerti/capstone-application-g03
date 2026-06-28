package it.g03.orders.controller

import it.g03.orders.dto.OrderResponse
import it.g03.orders.dto.PlaceOrderRequest
import it.g03.orders.dto.toResponse
import it.g03.orders.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * PUBLIC API (exposed through the ingress at /api/orders)
 *
 * POST returns **202 Accepted**: the user gets an immediate, durable acknowledgement (order + outbox event committed) while reservation/payment/confirmation proceed through the Kafka-driven pipeline
 *
 * GET lets clients (and the demo) observe the order reaching a terminal state
 */

@RestController   // exposes this class as a REST controller (will return values as HTTP responses)

@RequestMapping("/api/orders") // sets the base path for every endpoint defined in this controller
class OrdersController(private val service: OrderService) {

    // POST /api/orders -> places an order and returns 202 Accepted (the saga proceeds asynchronously)
    @PostMapping
    fun place(@RequestBody request: PlaceOrderRequest): ResponseEntity<OrderResponse> {
        val order = service.placeOrder(request.productId, request.qty)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(order.toResponse())
    }

    // GET /api/orders/{orderId} -> looks up a single order by its id (lets clients observe the terminal state)
    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: UUID): OrderResponse = service.get(orderId).toResponse()
}
