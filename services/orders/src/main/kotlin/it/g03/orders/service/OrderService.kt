package it.g03.orders.service

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderLeg
import java.util.UUID

/** An order together with its legs — the unit the controller maps to the API response (order + outbound/return). */
data class OrderView(val order: Order, val legs: List<OrderLeg>)

/**
 * Orders business-layer contract (Controller -> Service -> Repository). A booking is created empty (DRAFT), then
 * the user adds an outbound leg (required) and optionally a return leg — each add reserves seats and, on the
 * first leg, starts the backend-owned hold window — and finally pays within that window. The controller depends
 * on this interface; [OrderServiceImpl] is the single implementation.
 */
interface OrderService {
    /** Create an empty DRAFT booking for the authenticated buyer (idempotent on the client key). */
    fun createDraft(buyerSub: String, buyerEmail: String?, idempotencyKey: String?): OrderView

    /** Add a leg (reserve-on-add). The first leg starts the hold window and moves the order DRAFT -> HELD. */
    fun addLeg(orderId: UUID, buyerSub: String, direction: LegDirection, tripCode: String, qty: Int): OrderView

    /** Remove a leg (releasing its hold); if no active leg remains the order reverts to DRAFT and the window clears. */
    fun removeLeg(orderId: UUID, buyerSub: String, legId: UUID): OrderView

    /** Pay for the booking — backend re-checks the window, then authorizes the summed amount over the reserved legs. */
    fun pay(orderId: UUID, buyerSub: String): OrderView

    /** Read side: the order plus its legs (lets clients observe legs, the window, and the terminal state). */
    fun get(orderId: UUID): OrderView
}
