package it.g03.orders.repository

import it.g03.orders.entities.OrderLeg
import it.g03.orders.entities.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

// One repository per file (root convention). Legs are loaded by their parent order id (no lazy collection graph).
interface OrderLegRepository : JpaRepository<OrderLeg, UUID> {

    /**
     * All legs of a booking (outbound + optional return): the fan-out/fan-in saga and the order view read this.
     * Ordered by `createdAt` (the outbound is always added first) so the leg order is deterministic — without an
     * explicit ORDER BY, Postgres returns rows in an unspecified order that flips after updates (outbound/return
     * would render in either order in the UI).
     */
    @Query("select l from OrderLeg l where l.orderId = :orderId order by l.createdAt asc, l.id asc")
    fun findByOrderId(orderId: UUID): List<OrderLeg>

    /** The single leg for a (order, trip): maps a reservation-result / seats-confirmed reply to its leg. */
    fun findByOrderIdAndTripCode(orderId: UUID, tripCode: String): OrderLeg?

    /** Batch-load legs for a page of bookings (the my-bookings list): one query instead of N. Same deterministic
     *  createdAt ordering as [findByOrderId] (groupBy downstream preserves per-order encounter order). */
    @Query("select l from OrderLeg l where l.orderId in :orderIds order by l.createdAt asc, l.id asc")
    fun findByOrderIdIn(orderIds: Collection<UUID>): List<OrderLeg>

    /** Housekeeping ([AbandonedDraftSweeper]): remove the (RELEASED) legs of DRAFT bookings abandoned before the cutoff — run BEFORE the orders are deleted, because the order_legs -> orders FK has no ON DELETE CASCADE. */
    @Modifying
    @Query("delete from OrderLeg l where l.orderId in (select o.id from Order o where o.status = :status and o.updatedAt < :cutoff)")
    fun deleteByOrderStatusAndUpdatedAtBefore(status: OrderStatus, cutoff: Instant): Int
}
