package it.g03.catalog.events

import java.time.Instant
import java.util.UUID

/**
 * Catalog's own copy of Inventory's seat-availability event (no shared jar — duplicating a small payload is the
 * standard microservice trade-off against coupling to a common release cycle). Only `tripCode`, `available` and
 * `occurredAt` are used by the projection; `totalSeats` is carried for contract fidelity. Defaults let Jackson
 * deserialize partial/forward-compatible payloads.
 */
data class AvailabilityChangedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val tripCode: String = "",
    val available: Int = 0,
    val totalSeats: Int = 0,
    val occurredAt: Instant = Instant.EPOCH,
)
