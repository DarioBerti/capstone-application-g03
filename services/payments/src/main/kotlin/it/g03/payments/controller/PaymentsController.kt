package it.g03.payments.controller

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.entities.PaymentAuthorization
import it.g03.payments.service.PaymentsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AuthorizeRequest(val orderId: String, val amountCents: Long)

data class AuthorizationResponse(
    val authorizationId: UUID,
    val orderId: String,
    val amountCents: Long,
    val status: AuthorizationStatus,
    // true when this call hit the idempotent replay path (retry/duplicate)
    val replayed: Boolean,
)

private fun PaymentAuthorization.toResponse(replayed: Boolean = false) =
    AuthorizationResponse(id, orderId, amountCents, status, replayed)

/**
 * Internal synchronous API, called by Orders inside its async pipeline ("an authorization decision must be made now"). Not exposed through the public ingress
 *
 * As in Inventory: DECLINED is a 200 + status field, not an HTTP error -> it is a business answer the saga branches on; HTTP errors would trip the caller's retries and circuit breaker for something that is not a failure
 */

@RestController   // exposes this class as a REST controller (will return values as HTTP responses)

@RequestMapping("/api/payments") // sets the base path for every endpoint defined in this controller

class PaymentsController(private val service: PaymentsService) {

    // POST /api/payments/authorizations -> authorizes (or replays) a payment for an order
    @PostMapping("/authorizations")
    fun authorize(@RequestBody request: AuthorizeRequest): AuthorizationResponse {
        val outcome = service.authorize(request.orderId, request.amountCents)
        return outcome.authorization.toResponse(replayed = outcome.replayed)
    }

    // Inspection endpoint (demo / chaos verification: prove there is ONE charge per order)
    // GET /api/payments/authorizations/{orderId} -> looks up the authorization for a single order
    @GetMapping("/authorizations/{orderId}")
    fun byOrder(@PathVariable orderId: String): AuthorizationResponse =
        service.find(orderId).toResponse()

    // Converts a missing authorization into a 404 response with a small JSON error body
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    // Converts an invalid request into a 400 response with a small JSON error body
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
}
