package it.g03.catalog.repository

import it.g03.catalog.entities.Trip
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

// JpaRepository<Trip, UUID> = CRUD keyed by the surrogate UUID id; JpaSpecificationExecutor adds
// findAll(Specification, Pageable) so list reads are paginated and filterable via composable Specifications.
// findByCode looks a trip up by its public business key (the cross-service vocabulary, e.g. FR9592-20260629).
interface TripRepository :
    JpaRepository<Trip, UUID>,
    JpaSpecificationExecutor<Trip> {

    fun findByCode(code: String): Trip?

    // Applies an availability projection from an Inventory seat-availability event. The seatsUpdatedAt guard
    // makes it last-writer-by-event-time (an out-of-order or replayed event can't overwrite a fresher count).
    // No-ops (returns 0) for a trip code Catalog doesn't list — we only need availability for what we can sell.
    @Modifying(clearAutomatically = true)
    @Query(
        """
        update Trip t set t.availableSeats = :available, t.seatsUpdatedAt = :occurredAt
        where t.code = :code and (t.seatsUpdatedAt is null or t.seatsUpdatedAt < :occurredAt)
        """,
    )
    fun applyAvailability(
        @Param("code") code: String,
        @Param("available") available: Int,
        @Param("occurredAt") occurredAt: Instant,
    ): Int

    // Most-served upcoming destinations for the storefront's "popular destinations" strip. An ad-hoc entity
    // join (Station is not a Trip association) aggregates every future departure by destination station:
    // count(t) = departures, min(priceCents) = cheapest-from. A null :origin lists all hubs; a non-null one
    // narrows to departures leaving that origin. Ordering (most trips first, cheapest as tie-break) is in the
    // query; the caller passes an unsorted, size-limited Pageable, and the List return type skips the count
    // query so only the top-N rows are fetched.
    @Query(
        """
        select s.code as code, s.name as name, s.city as city, s.country as country,
               min(t.priceCents) as fromPriceCents, t.currency as currency, count(t) as tripCount
        from Trip t
        join Station s on s.code = t.destinationCode
        where t.departureTime > :now
          and (:origin is null or t.originCode = :origin)
        group by s.code, s.name, s.city, s.country, t.currency
        order by count(t) desc, min(t.priceCents) asc
        """,
    )
    fun topDestinations(
        @Param("now") now: OffsetDateTime,
        @Param("origin") origin: String?,
        pageable: Pageable,
    ): List<DestinationAggregate>
}
