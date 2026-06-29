package it.g03.orders.repository

import it.g03.orders.entities.Order
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<Order, UUID> (the order id is a UUID). Spring Data generates the implementation at runtime.
interface OrderRepository : JpaRepository<Order, UUID> {

    /** Idempotency lookup: a retried POST with the same client key replays this order instead of duplicating it. */
    fun findByIdempotencyKey(idempotencyKey: String): Order?

    /** "My orders": paginated history scoped to the authenticated buyer (indexed on buyer_sub). */
    fun findByBuyerSub(buyerSub: String, pageable: Pageable): Page<Order>
}
