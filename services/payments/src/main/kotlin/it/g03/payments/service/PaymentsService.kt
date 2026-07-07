package it.g03.payments.service

import it.g03.payments.entities.PaymentAuthorization
import it.g03.payments.gateway.LineItem

// Outcome of an authorize call; "replayed = true" means the idempotent replay path fired
data class AuthorizeOutcome(val authorization: PaymentAuthorization, val replayed: Boolean)

// Outcome of a capture call; "replayed = true" means no new capture happened (already captured / not capturable)
data class CaptureOutcome(val authorization: PaymentAuthorization, val replayed: Boolean)

// Outcome of a cancel-authorization (hold release) call; "replayed = true" means no new release happened
data class CancelOutcome(val authorization: PaymentAuthorization, val replayed: Boolean)

/**
 * Payments business-layer contract (Controller/Listener -> Service -> Repository). The two-phase money path is
 * authorize (place a hold) -> capture (take the funds), with cancelAuthorization releasing an un-captured hold.
 * [PaymentsServiceImpl] is the single implementation.
 */
interface PaymentsService {
    fun authorize(orderId: String, amountCents: Long, items: List<LineItem> = emptyList(), payPalOrderId: String? = null): AuthorizeOutcome
    fun capture(orderId: String): CaptureOutcome
    fun cancelAuthorization(orderId: String): CancelOutcome
    fun find(orderId: String): PaymentAuthorization
}
