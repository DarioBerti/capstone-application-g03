package it.g03.payments.service

import it.g03.payments.entities.PaymentAuthorization

// Outcome of an authorize/void call; "replayed = true" means the idempotent replay path fired
data class AuthorizeOutcome(val authorization: PaymentAuthorization, val replayed: Boolean)

/**
 * Payments business-layer contract (Controller -> Service -> Repository). The controller depends on this
 * interface; [PaymentsServiceImpl] is the single implementation.
 */
interface PaymentsService {
    fun authorize(orderId: String, amountCents: Long): AuthorizeOutcome
    fun void(orderId: String): AuthorizeOutcome
    fun find(orderId: String): PaymentAuthorization
}
