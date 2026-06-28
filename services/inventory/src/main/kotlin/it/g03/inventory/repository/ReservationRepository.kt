package it.g03.inventory.repository

import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the Reservation entity in this case); the IdType is UUID because the reservation id is a UUID. Spring Data will generate the implementation at runtime
interface ReservationRepository : JpaRepository<Reservation, UUID> {

    /** Idempotency lookup: the (orderId, productId) pair identifies the operation. */
    // Spring Data derives the query from the method name: find a reservation by its orderId and productId
    fun findByOrderIdAndProductId(orderId: String, productId: String): Reservation?

    /** Reconciler sweep: holds whose TTL elapsed before a confirm/release arrived. */
    // Spring Data derives the query from the method name: find reservations in the given status whose expiresAt is before the cutoff
    fun findByStatusAndExpiresAtBefore(status: ReservationStatus, cutoff: Instant): List<Reservation>
}
