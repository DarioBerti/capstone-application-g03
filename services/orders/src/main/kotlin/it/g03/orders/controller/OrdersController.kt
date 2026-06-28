package it.g03.orders.controller

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.service.OrderService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

// Request body for placing an order (qty defaults to 1)
data class PlaceOrderRequest(val productId: String, val qty: Int = 1)

// Response body exposed to clients (a flattened, API-facing view of the Order entity)
data class OrderResponse(
    val orderId: UUID,
    val productId: String,
    val qty: Int,
    val amountCents: Long,
    val status: OrderStatus,
    val reason: RejectionReason?,
)

// maps the internal Order entity to the public OrderResponse DTO
private fun Order.toResponse() = OrderResponse(id, productId, qty, amountCents, status, reason)

/**
 * PUBLIC API (exposed through the ingress at /api/orders)
 *
 * POST returns **202 Accepted**: the user gets an immediate, durable acknowledgement (order + outbox event committed) while reservation/payment/confirmation proceed through the Kafka-driven pipeline
 * 
 * GET lets clients (and the demo) observe the order reaching a terminal state
 */

@RestController   // exposes this class as a REST controller (will return values as HTTP responses)

@RequestMapping("/api/orders") // sets the base path for every endpoint defined in this controller
class OrdersController(
    private val service: OrderService,
    // Backpressure at the edge (Fase 2 #3): the intake is rate-limited; over budget we shed with 429
    private val intake: OrdersIntakeRateLimiter,
) {

    // POST /api/orders -> places an order and returns 202 Accepted (the saga proceeds asynchronously).
    // The whole intake is wrapped in the rate limiter: under budget it runs and returns 202; over budget "withPermit" throws IntakeOverloadedException BEFORE touching the DB/outbox -> mapped to 429 below (controlled refusal, not a queue)
    // The 429 lives ONLY here at the edge: internal saga calls are governed by the circuit breaker / bulkhead, never by a 429 (Fase 2 #3.5)
    @PostMapping
    fun place(@RequestBody request: PlaceOrderRequest): ResponseEntity<OrderResponse> =
        intake.withPermit {
            val order = service.placeOrder(request.productId, request.qty)
            ResponseEntity.status(HttpStatus.ACCEPTED).body(order.toResponse())
        }

    // GET /api/orders/{orderId} -> looks up a single order by its id (lets clients observe the terminal state)
    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: UUID): OrderResponse = service.get(orderId).toResponse()

    // converts a missing order into a 404 response with a small JSON error body
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    // converts an invalid request (blank productId / non-positive qty) into a 400 response with a small JSON error body
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))

    // Load shedding (Fase 2 #3): intake over budget -> HTTP 429 Too Many Requests + the standard "Retry-After" header (seconds, derived from the limiter window). This is controlled refusal: the client is told to come back, the money path is protected!
    @ExceptionHandler(IntakeOverloadedException::class)
    fun overloaded(e: IntakeOverloadedException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            .body(mapOf("error" to "orders intake overloaded, retry later"))
}
