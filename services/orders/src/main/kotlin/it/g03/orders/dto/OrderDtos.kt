package it.g03.orders.dto

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.util.UUID

/**
 * Boundary DTOs for the orders API. JPA entities are never exposed directly (root convention);
 * the controller returns these immutable value types and maps from the entity explicitly.
 */

// Request body for placing an order (qty defaults to 1). The buyer identity comes from the JWT, not the body.
data class PlaceOrderRequest(
    @field:NotBlank val tripCode: String,
    @field:Positive val qty: Int = 1,
)

// Response body exposed to clients (a flattened, API-facing view of the Order entity).
data class OrderResponse(
    val orderId: UUID,
    val tripCode: String,
    val qty: Int,
    val amountCents: Long,
    val currency: String,
    val status: OrderStatus,
    val rejectionReason: RejectionReason?,
)

// Maps the internal Order entity to the public OrderResponse DTO.
fun Order.toResponse() = OrderResponse(id, tripCode, qty, amountCents, currency, status, rejectionReason)
