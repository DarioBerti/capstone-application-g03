package it.g03.inventory.repository

import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

// JpaRepository<Reservation, UUID> (the reservation id is a UUID). Spring Data generates the implementation.
interface ReservationRepository : JpaRepository<Reservation, UUID> {

    /** Idempotency lookup: the (orderId, tripCode) pair identifies the operation. */
    fun findByOrderIdAndTripCode(orderId: String, tripCode: String): Reservation?

    /** Reconciler sweep: holds whose TTL elapsed before a confirm/release arrived. */
    fun findByStatusAndExpiresAtBefore(status: ReservationStatus, cutoff: Instant): List<Reservation>
}
