package it.g03.orders.repository

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

// JpaRepository<Order, UUID> (the order id is a UUID). Spring Data generates the implementation at runtime.
// JpaSpecificationExecutor adds findAll(Specification, Pageable) so the buyer's my-bookings list can be filtered
// (status, created-date range) and paginated via composable Specifications.
interface OrderRepository : JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /** Idempotency lookup: a retried POST with the same client key replays this order instead of duplicating it. */
    fun findByIdempotencyKey(idempotencyKey: String): Order?

    /** Timeout sweep ([OrderTimeoutSweeper]): the post-pay orders stuck in a resumable state whose last update predates the deadline — re-driven (the command for their state is re-emitted). */
    fun findByStatusInAndUpdatedAtBefore(statuses: Collection<OrderStatus>, cutoff: Instant): List<Order>

    /** Checkout-window expiry ([CheckoutExpirySweeper]): HELD/PAYING orders whose backend-owned hold window has elapsed — rejected (EXPIRED_NO_PAYMENT or SERVICE_UNAVAILABLE) with their held legs released. */
    fun findByStatusInAndExpiresAtBefore(statuses: Collection<OrderStatus>, cutoff: Instant): List<Order>

    /**
     * Pessimistic row lock for the seats-confirmed FAN-IN: concurrent per-leg confirms for the same booking
     * serialize on the order row, so the last confirm reliably sees every sibling leg CONFIRMED and emits
     * capture-payment exactly once (no "neither saw all-confirmed" race, no lost leg flip on an optimistic retry).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    fun findByIdForUpdate(id: UUID): Order?
}
