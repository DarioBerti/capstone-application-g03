package it.g03.payments.controller

import it.g03.payments.dto.PayPalSessionRequest
import it.g03.payments.dto.PayPalSessionResponse
import it.g03.payments.gateway.LineItem
import it.g03.payments.service.PaymentSessionService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Internal (in-cluster only) endpoint Orders calls to open a PayPal Card-Fields session for a booking. NOT exposed
 * through the public ingress — Orders relays the buyer's Keycloak token so the resource-server filter authorizes
 * it. Only present when payments.provider=paypal (the session service is PayPal-specific).
 */
@RestController
@RequestMapping("/internal/payments")
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "paypal")
class PayPalSessionController(private val service: PaymentSessionService) {

    @PostMapping("/{orderId}/paypal-session")
    fun createSession(@PathVariable orderId: String, @RequestBody request: PayPalSessionRequest): PayPalSessionResponse {
        val session = service.createPayPalSession(
            orderId,
            request.amountCents,
            request.currency,
            request.items.map { LineItem(it.name, it.quantity, it.unitAmountCents) },
        )
        return PayPalSessionResponse(session.clientId, session.clientToken, session.payPalOrderId)
    }
}
