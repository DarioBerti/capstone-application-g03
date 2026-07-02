package it.g03.inventory.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The contended resource (Pillar B): finite seats for a trip, keyed by the shared trip `code` vocabulary
 * (e.g. 'FR9592-20260629').
 *
 * `available` is NEVER updated through entity state — that would be a lost-update waiting to happen. All
 * decrements/restocks go through the atomic conditional UPDATEs in [it.g03.inventory.repository.TripSeatsRepository],
 * which is what makes "never oversell" hold under concurrency. The PostgreSQL schema adds CHECK (available >= 0)
 * as a last line of defence (db/migration/V1__baseline.sql).
 *
 * Plain `class`, not `data class` — a JPA-managed entity has identity, not value, semantics (root CLAUDE.md).
 */
@Entity
@Table(name = "trip_seats")
class TripSeats(

    @Id
    @Column(name = "trip_code", length = 64)   // shared trip-code vocabulary with Catalog
    val tripCode: String = "",

    @Column(name = "total_seats", nullable = false)   // fixed capacity for this departure
    val totalSeats: Int = 0,

    // contended counter: mutated ONLY by the atomic conditional UPDATEs, never by entity state
    @Column(nullable = false)
    var available: Int = 0,

    // last time the seat count changed; bumped by the atomic reserve/restock UPDATEs
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
