package it.g03.inventory.dto

import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import java.time.Instant

/**
 * Boundary DTOs for the inventory reservation API. JPA entities are never exposed directly (root
 * convention); the controller returns these immutable value types and maps from the entity explicitly.
 */

// request body for a reserve call ("hold these seats now")
data class ReserveRequest(val orderId: String, val tripCode: String, val qty: Int = 1)

// response body carrying the reservation outcome back to the caller (Orders)
data class ReservationResponse(
    val orderId: String,
    val tripCode: String,
    val qty: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    /** true when this call hit the idempotent replay path (duplicate delivery/retry) */
    val replayed: Boolean,
)

// maps a persisted Reservation entity to the API response DTO
fun Reservation.toResponse(replayed: Boolean = false) =
    ReservationResponse(orderId, tripCode, qty, status, expiresAt, replayed)
