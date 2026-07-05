package it.g03.inventory.dto

import it.g03.inventory.entities.TripSeats

/**
 * Public seat-availability projection for a trip. This is the ONLY inventory read exposed through the
 * public ingress (GET /api/inventory/availability), so the storefront can show live seat counts while
 * browsing. Advisory by design — the authoritative never-oversell guard is still the atomic conditional
 * decrement at reserve time; a slightly stale count here can never cause an oversell. The raw-entity
 * /items debug endpoints stay internal.
 */
data class AvailabilityResponse(
    val tripCode: String,
    val available: Int,
    val totalSeats: Int,
)

fun TripSeats.toResponse(): AvailabilityResponse =
    AvailabilityResponse(tripCode = tripCode, available = available, totalSeats = totalSeats)
