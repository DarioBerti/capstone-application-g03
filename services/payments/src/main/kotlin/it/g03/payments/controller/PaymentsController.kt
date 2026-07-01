package it.g03.payments.controller

import it.g03.payments.dto.AuthorizationResponse
import it.g03.payments.dto.toResponse
import it.g03.payments.service.PaymentsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only inspection surface. The money path is now driven by Kafka commands (authorize/capture/cancel),
 * NOT by HTTP — so the POST command endpoints are gone. The single GET below is the chaos/demo proof that
 * there is exactly ONE charge per order. Not exposed through the public ingress.
 */
@RestController   // exposes this class as a REST controller (will return values as HTTP responses)
@RequestMapping("/api/payments") // sets the base path for every endpoint defined in this controller
class PaymentsController(private val service: PaymentsService) {

    // Inspection endpoint (demo / chaos verification: prove there is ONE charge per order)
    // GET /api/payments/authorizations/{orderId} -> looks up the authorization for a single order
    @GetMapping("/authorizations/{orderId}")
    fun byOrder(@PathVariable orderId: String): AuthorizationResponse =
        service.find(orderId).toResponse()
}
