package it.g03.orders.dto

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import java.util.UUID

/**
 * Boundary DTOs for the orders API. JPA entities are never exposed directly (root convention);
 * the controller returns these immutable value types and maps from the entity explicitly.
 */

// Request body for placing an order (qty defaults to 1).
data class PlaceOrderRequest(val productId: String, val qty: Int = 1)

// Response body exposed to clients (a flattened, API-facing view of the Order entity).
data class OrderResponse(
    val orderId: UUID,
    val productId: String,
    val qty: Int,
    val amountCents: Long,
    val status: OrderStatus,
    val reason: RejectionReason?,
)

// Maps the internal Order entity to the public OrderResponse DTO.
fun Order.toResponse() = OrderResponse(id, productId, qty, amountCents, status, reason)
