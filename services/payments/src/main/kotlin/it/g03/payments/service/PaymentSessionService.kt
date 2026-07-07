package it.g03.payments.service

import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.paypal.PayPalSession
import it.g03.payments.gateway.paypal.PayPalSessionProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Opens a PayPal Card-Fields session for an order about to be paid. Only exists when payments.provider=paypal
 * (the [PayPalSessionProvider] it delegates to is PayPal-specific); on the mock provider the endpoint is absent.
 */
interface PaymentSessionService {
    fun createPayPalSession(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): PayPalSession
}

@Service
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "paypal")
class PaymentSessionServiceImpl(
    private val sessionProvider: PayPalSessionProvider,
) : PaymentSessionService {
    override fun createPayPalSession(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): PayPalSession {
        require(amountCents > 0) { "amountCents must be positive" }
        return sessionProvider.createSession(orderId, amountCents, currency, items)
    }
}
