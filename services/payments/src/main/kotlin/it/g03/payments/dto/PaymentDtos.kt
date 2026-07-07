package it.g03.payments.dto

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.entities.PaymentAuthorization
import java.util.UUID

/**
 * Boundary DTOs for the payments API. JPA entities are never exposed directly (root convention);
 * the controller returns these immutable value types and maps from the entity explicitly.
 */

data class AuthorizationResponse(
    val authorizationId: UUID,
    val orderId: String,
    val amountCents: Long,
    val currency: String,
    val status: AuthorizationStatus,
    val pspReference: String?,
    val declineReason: String?,
    // true when this call hit the idempotent replay path (retry/duplicate)
    val replayed: Boolean,
)

fun PaymentAuthorization.toResponse(replayed: Boolean = false) =
    AuthorizationResponse(id, orderId, amountCents, currency, status, pspReference, declineReason, replayed)

// --- PayPal Card-Fields session (internal, called by Orders) -------------------------------------------------

// Orders asks Payments to open a PayPal Card-Fields session for a booking about to be paid. Items describe the
// legs so PayPal shows the products (they sum to amountCents, as elsewhere on the money path).
data class PayPalSessionRequest(
    val amountCents: Long,
    val currency: String,
    val items: List<PayPalSessionLineItem> = emptyList(),
)

data class PayPalSessionLineItem(val name: String, val quantity: Int, val unitAmountCents: Long)

// What the browser needs to render Card Fields: the public client-id (for the SDK URL), the per-session client
// token (SDK auth), and the PayPal order id the SDK approves. No secret or card data crosses this boundary.
data class PayPalSessionResponse(
    val clientId: String,
    val clientToken: String,
    val payPalOrderId: String,
)
