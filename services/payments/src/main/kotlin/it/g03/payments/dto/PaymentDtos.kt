package it.g03.payments.dto

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.entities.PaymentAuthorization
import java.util.UUID

/**
 * Boundary DTOs for the payments API. JPA entities are never exposed directly (root convention);
 * the controller returns these immutable value types and maps from the entity explicitly.
 */

data class AuthorizeRequest(val orderId: String, val amountCents: Long)

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
