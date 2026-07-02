package it.g03.orders.service

import it.g03.orders.entities.Order
import java.util.UUID

/**
 * Orders business-layer contract (Controller -> Service -> Repository). The controller depends on this
 * interface; [OrderServiceImpl] is the single implementation.
 */
interface OrderService {
    fun placeOrder(
        tripCode: String,
        qty: Int,
        buyerSub: String,
        buyerEmail: String?,
        idempotencyKey: String? = null,
    ): Order

    fun get(orderId: UUID): Order
}
