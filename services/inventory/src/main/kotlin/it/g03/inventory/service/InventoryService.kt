package it.g03.inventory.service

import it.g03.inventory.dto.AvailabilityResponse
import it.g03.inventory.entities.Reservation
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant

// Outcome of a reserve call; `replayed = true` means the idempotent replay path fired (the reservation already exists, so no new reservation is created)
data class ReserveOutcome(val reservation: Reservation, val replayed: Boolean)

/**
 * Inventory business-layer contract (Controller -> Service -> Repository). The command listeners and the
 * [ReservationReconciler] depend on this interface; [InventoryServiceImpl] is the single implementation. Each
 * command method appends its saga reply to the transactional outbox in the SAME transaction.
 */
interface InventoryService {
    fun reserve(orderId: String, tripCode: String, qty: Int): ReserveOutcome
    fun confirm(orderId: String, tripCode: String): Reservation
    fun release(orderId: String, tripCode: String): Reservation
    fun expireOverdue(now: Instant): Int

    // Read-only seat-availability projection for the public storefront (paginated DTO, never the raw entity).
    fun availability(pageable: Pageable): Page<AvailabilityResponse>

    // Per-trip seat-availability projection for the storefront trip view (unknown trip -> not-found).
    fun availabilityFor(tripCode: String): AvailabilityResponse

    // One-shot snapshot: re-emit a seat-availability event for every known trip so read-side consumers
    // (Catalog's projection) can be seeded without a booking having moved the count. Returns the trip count.
    fun republishAllAvailability(): Int
}
