package it.g03.inventory.service

import it.g03.inventory.entities.Reservation
import java.time.Instant

// Outcome of a reserve call; `replayed = true` means the idempotent replay path fired (the reservation already exists, so no new reservation is created)
data class ReserveOutcome(val reservation: Reservation, val replayed: Boolean)

/**
 * Inventory business-layer contract (Controller -> Service -> Repository). The controller and the
 * [ReservationReconciler] depend on this interface; [InventoryServiceImpl] is the single implementation.
 */
interface InventoryService {
    fun reserve(orderId: String, tripCode: String, qty: Int): ReserveOutcome
    fun confirm(orderId: String, tripCode: String): Reservation
    fun release(orderId: String, tripCode: String): Reservation
    fun expireOverdue(now: Instant): Int
}
