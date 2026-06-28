package it.g03.payments.controller

import it.g03.payments.dto.AuthorizationResponse
import it.g03.payments.dto.AuthorizeRequest
import it.g03.payments.dto.toResponse
import it.g03.payments.service.PaymentsService
import org.springframework.web.bind.annotation.*

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
}
