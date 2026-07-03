package it.g03.orders.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * A single leg of a booking: the OUTBOUND leg (required) or the optional RETURN leg. A round-trip order holds two
 * legs under one [Order] — always DISTINCT trip codes (different trains), so UNIQUE(order_id, trip_code) mirrors
 * Inventory's reservation key and makes a redelivered reservation-result / seats-confirmed reply map to exactly
 * one leg.
 *
 * `status` is the per-leg projection of the reservation lifecycle: PENDING (reserve-seats emitted, awaiting the
 * reservation-result), RESERVED (inventory confirmed the hold), SOLD_OUT (inventory could not hold — for a RETURN
 * this is the one-way fallback, the order stays HELD), CONFIRMED (firm sale), EXPIRED (the hold lapsed at
 * confirm), RELEASED (given back by compensation/removal). `unitPriceCents` is snapshotted at add-time so a later
 * price change does not re-price a held booking; the line amount is qty × unitPriceCents.
 *
 * @Version guards concurrent per-leg transitions (a reply and the sweeper can race the same leg), mirroring the
 * Order aggregate. Plain `class`, not `data class` — a JPA entity has identity, not value, semantics.
 */
@Entity
@Table(
    name = "order_legs",
    uniqueConstraints = [UniqueConstraint(name = "uq_order_legs_order_trip", columnNames = ["order_id", "trip_code"])],
)
class OrderLeg(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false)
    val orderId: UUID = UUID(0, 0),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val direction: LegDirection = LegDirection.OUTBOUND,

    @Column(name = "trip_code", nullable = false, length = 64)
    val tripCode: String = "",

    @Column(nullable = false)
    val qty: Int = 0,

    @Column(name = "unit_price_cents", nullable = false)
    val unitPriceCents: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: LegStatus = LegStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

// Outbound (there) or return (back) — the two legs of a round-trip booking; a one-way has only the outbound.
enum class LegDirection { OUTBOUND, RETURN }

// Per-leg projection of the reservation lifecycle. Active = PENDING/RESERVED/CONFIRMED; terminal = SOLD_OUT/EXPIRED/RELEASED.
enum class LegStatus { PENDING, RESERVED, SOLD_OUT, CONFIRMED, EXPIRED, RELEASED }
