package it.g03.inventory.entities

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
 * Reservation state machine (Pillar B):
 *
 *   (new) → RESERVED  → CONFIRMED                     (payment authorized)
 *                     → RELEASED                      (saga compensation: payment failed)
 *                     → EXPIRED                       (TTL passed, swept by the reconciler)
 *   (new) → REJECTED                                  (sold out at reserve time)
 *
 * The UNIQUE(order_id, trip_code) constraint is the idempotency record of the money path: if the same order
 * is placed twice, the second insert fails and rolls back the entire transaction (including the seat
 * decrement). Both the dedup check and seat reduction happen atomically in the same transaction (see
 * InventoryService.reserve).
 *
 * Plain `class`, not `data class` — a JPA-managed entity has identity, not value, semantics (root CLAUDE.md).
 */
@Entity
@Table(
    name = "reservations",
    // the UNIQUE(order_id, trip_code) constraint is the idempotency record of the money path
    uniqueConstraints = [UniqueConstraint(name = "uq_reservations_order_trip", columnNames = ["order_id", "trip_code"])],
)
class Reservation(
    @Id  // primary key (generated UUID, no shared sequence to contend on)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false, length = 64)  // the placing order
    val orderId: String = "",

    @Column(name = "trip_code", nullable = false, length = 64)  // the held trip (shared code vocabulary)
    val tripCode: String = "",

    @Column(nullable = false)  // how many seats this hold reserves
    val qty: Int = 0,

    @Enumerated(EnumType.STRING)  // store the state as its name (stable across reorderings)
    @Column(nullable = false, length = 16)
    var status: ReservationStatus = ReservationStatus.RESERVED,

    @Column(name = "created_at", nullable = false)  // when the hold was taken
    val createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)  // TTL deadline: after this the reconciler may sweep it
    val expiresAt: Instant = Instant.EPOCH,

    @Column(name = "confirmed_at")  // set on RESERVED -> CONFIRMED
    var confirmedAt: Instant? = null,

    @Column(name = "released_at")  // set on RESERVED -> RELEASED/EXPIRED (seats restocked)
    var releasedAt: Instant? = null,

    // Optimistic-lock guard (Pillar B): the terminal transitions RESERVED→{CONFIRMED,RELEASED,EXPIRED} each do a
    // read-then-(status-flip + ADDITIVE restock). Two drivers can race the SAME reservation — a late confirm vs the
    // reconciler, or two reconcilers (inventory runs >1 replica). Without this, both pass the status guard and BOTH
    // restock (additive, non-convergent) → phantom availability → oversell. @Version makes the loser fail at flush
    // (ObjectOptimisticLockingFailureException → rollback) BEFORE its restock runs (restock is @Modifying
    // flushAutomatically, so the versioned UPDATE flushes first). JPA manages the column; never set it by hand.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

// The reservation lifecycle states (see the state machine in the class doc above)
enum class ReservationStatus {
    /** Seats are being held; payment may still confirm or the hold may lapse */
    RESERVED,

    /** Terminal: payment authorized, seats sold */
    CONFIRMED,

    /** Terminal: explicitly released as a saga compensation; seats restocked */
    RELEASED,

    /** Terminal: TTL elapsed before confirmation; seats restocked by the reconciler */
    EXPIRED,

    /** Terminal: there were not enough seats at reserve time (sold out) */
    REJECTED,
}
