package it.g03.orders.dto

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.LegStatus
import it.g03.orders.entities.OrderLeg
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.service.OrderView
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Boundary DTOs for the orders API. JPA entities are never exposed directly (root convention); the controller
 * returns these immutable value types and maps from the entity/view explicitly.
 */

// Request to add a leg to a booking: the OUTBOUND leg (required, added first) or the optional RETURN leg. The
// buyer identity comes from the JWT, not the body.
data class AddLegRequest(
    @field:NotNull val direction: LegDirection,
    @field:NotBlank val tripCode: String,
    @field:Positive val qty: Int = 1,
)

// One leg of the booking in the API response.
data class OrderLegResponse(
    val legId: UUID,
    val direction: LegDirection,
    val tripCode: String,
    val qty: Int,
    val unitPriceCents: Long,
    val lineAmountCents: Long,
    val status: LegStatus,
)

/**
 * API-facing view of a booking: the order, its legs, and the backend-owned hold window. `remainingSeconds` is
 * computed server-side (max(0, expiresAt - now)) so a client can render a countdown WITHOUT ever being the
 * authority on expiry — the backend re-checks the window at pay and via the expiry sweeper.
 */
data class OrderResponse(
    val orderId: UUID,
    val status: OrderStatus,
    val rejectionReason: RejectionReason?,
    val currency: String,
    val amountCents: Long,
    val legs: List<OrderLegResponse>,
    val expiresAt: Instant?,
    val remainingSeconds: Long?,
    val paymentInitiatedAt: Instant?,
)

fun OrderLeg.toResponse() = OrderLegResponse(
    legId = id, direction = direction, tripCode = tripCode, qty = qty,
    unitPriceCents = unitPriceCents, lineAmountCents = qty * unitPriceCents, status = status,
)

// The displayed total is the sum over the legs the user still intends to buy (excludes SOLD_OUT/EXPIRED/RELEASED).
fun OrderView.toResponse(now: Instant = Instant.now()): OrderResponse {
    val total = legs.filter { it.status in ACTIVE_DISPLAY_LEG }.sumOf { it.qty * it.unitPriceCents }
    val remaining = order.expiresAt?.let { maxOf(0L, Duration.between(now, it).seconds) }
    return OrderResponse(
        orderId = order.id, status = order.status, rejectionReason = order.rejectionReason,
        currency = order.currency, amountCents = total, legs = legs.map { it.toResponse() },
        expiresAt = order.expiresAt, remainingSeconds = remaining, paymentInitiatedAt = order.paymentInitiatedAt,
    )
}

private val ACTIVE_DISPLAY_LEG = setOf(LegStatus.PENDING, LegStatus.RESERVED, LegStatus.CONFIRMED)
