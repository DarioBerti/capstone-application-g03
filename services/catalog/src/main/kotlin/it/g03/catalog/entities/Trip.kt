package it.g03.catalog.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A scheduled, sellable train departure (the catalog "product").
 *
 * Identity: a surrogate `id` UUID (app-assigned — no shared sequence to contend on) PLUS a human-readable
 * business `code` in the transit-industry format [CARRIER][SERVICE_NUMBER]-[YYYYMMDD] (e.g. FR9592-20260629).
 * The `code` is the cross-service vocabulary and what the public API / ticket shows; its parts
 * (carrierCode, serviceNumber, serviceDate) are ALSO first-class columns so the catalog is queryable
 * (filter by carrier/date/route) rather than parsing the code string.
 *
 * origin/destination are station CODES (FK at the DB level), not @ManyToOne associations: catalog is
 * read-mostly and tolerant of staleness, and string codes keep list reads cheap (no lazy-loading/N+1).
 * created_at/updated_at are DB-owned (DEFAULT now()), so they are read-only here (insertable/updatable=false).
 *
 * Plain `class`, not `data class` — JPA entities have identity, not value, semantics (see root CLAUDE.md).
 */
@Entity
@Table(name = "trips")
class Trip(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 32)
    val code: String = "",

    @Column(name = "carrier_code", nullable = false, length = 8)
    val carrierCode: String = "",

    @Column(name = "service_number", nullable = false, length = 16)
    val serviceNumber: String = "",

    @Column(name = "service_date", nullable = false)
    val serviceDate: LocalDate = LocalDate.EPOCH,

    @Column(name = "origin_code", nullable = false, length = 8)
    val originCode: String = "",

    @Column(name = "destination_code", nullable = false, length = 8)
    val destinationCode: String = "",

    @Column(name = "departure_time", nullable = false)
    val departureTime: OffsetDateTime = OffsetDateTime.MIN,

    @Column(name = "arrival_time", nullable = false)
    val arrivalTime: OffsetDateTime = OffsetDateTime.MIN,

    // DB-generated journey length (arrival - departure, STORED): read-only here, exists so "Fastest" is a
    // plain sortable property for paginated reads (a computed expression can't ride Pageable's property paths).
    @Column(name = "duration_minutes", insertable = false, updatable = false)
    val durationMinutes: Int = 0,

    @Column(name = "price_cents", nullable = false)
    val priceCents: Long = 0,

    @Column(nullable = false, length = 3)
    val currency: String = "EUR",

    // Advisory availability projection, kept fresh by consuming Inventory's seat-availability events (Catalog
    // owns only this derived hint, not Inventory's tables). NULL = unknown (shown); 0 = sold out (hidden from
    // search). `var` because the projection updates it (JPA mutable-entity exception, root CLAUDE). seatsUpdatedAt
    // holds the applied event time so an out-of-order event can't overwrite a fresher one.
    @Column(name = "available_seats")
    var availableSeats: Int? = null,

    @Column(name = "seats_updated_at")
    var seatsUpdatedAt: Instant? = null,

    // DB-owned audit stamps (DEFAULT now()); read-only from the entity's point of view
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.EPOCH,
)
