package it.g03.inventory.events

import java.time.Instant
import java.util.UUID

/**
 * Saga command + reply payloads (JSON over Kafka). Each service keeps its OWN copy of the shapes it touches (no shared
 * library): duplicating a few small data classes is the standard microservice trade-off against coupling every service
 * to a common jar's release cycle. Every payload carries an eventId (consumer-side dedup/tracing) and occurredAt.
 */

// consumed: Orders asks Inventory to place a hold
data class ReserveSeatsCommand(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val tripCode: String,
    val qty: Int,
    val occurredAt: Instant = Instant.now(),
)

// consumed: Orders asks Inventory to commit a held reservation
data class ConfirmSeatsCommand(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val tripCode: String,
    val occurredAt: Instant = Instant.now(),
)

// consumed: Orders asks Inventory to release a hold (saga compensation)
data class ReleaseSeatsCommand(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val tripCode: String,
    val occurredAt: Instant = Instant.now(),
)

// produced: reply to a reserve-seats command (status RESERVED | SOLD_OUT). Carries tripCode so multi-leg Orders
// can map the reply to the right booking leg (a round-trip order holds two reservations under one orderId).
data class ReservationResultEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val tripCode: String,
    val status: String,
    val occurredAt: Instant = Instant.now(),
)

// produced: reply to a confirm-seats command (status CONFIRMED | EXPIRED). Carries tripCode for the same reason.
data class SeatsConfirmedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val tripCode: String,
    val status: String,
    val occurredAt: Instant = Instant.now(),
)
