package it.g03.catalog.service

import it.g03.catalog.dto.StationResponse
import it.g03.catalog.dto.TripFilter
import it.g03.catalog.dto.TripResponse
import it.g03.catalog.dto.toResponse
import it.g03.catalog.entities.Trip
import it.g03.catalog.repository.StationRepository
import it.g03.catalog.repository.TripRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Business layer for the catalog (Controller -> Service -> Repository, mandatory for every service).
 * Read-mostly: it owns query composition (turning a TripFilter into a Specification), the station
 * resolution that enriches a Trip into its response, and the not-found contract — so the controller
 * stays HTTP-only. Reads are `readOnly` transactions (no dirty-checking, and the DB connection isn't
 * held past the method now that open-in-view is off).
 */
@Service
class CatalogServiceImpl(
    private val trips: TripRepository,
    private val stations: StationRepository,
) : CatalogService {

    // Paginated, filterable trip list. A null filter field adds no predicate (lists everything).
    @Transactional(readOnly = true)
    override fun search(filter: TripFilter, pageable: Pageable): Page<TripResponse> {
        val page = trips.findAll(filter.toSpecification(), pageable)
        val stationsByCode = resolveStations(page.content)
        return page.map { it.toTripResponse(stationsByCode) }
    }

    // Single trip lookup by its public business code; a missing code is a domain not-found (-> 404).
    @Transactional(readOnly = true)
    override fun byCode(code: String): TripResponse {
        val trip = trips.findByCode(code) ?: throw NoSuchElementException("unknown trip: $code")
        return trip.toTripResponse(resolveStations(listOf(trip)))
    }

    // Paginated station list (e.g. to populate origin/destination pickers).
    @Transactional(readOnly = true)
    override fun listStations(pageable: Pageable): Page<StationResponse> =
        stations.findAll(pageable).map { it.toResponse() }

    // Bulk-load the stations referenced by a set of trips (no N+1), as response DTOs keyed by code.
    private fun resolveStations(trips: List<Trip>): Map<String, StationResponse> {
        val codes = trips.flatMap { listOf(it.originCode, it.destinationCode) }.toSet()
        return stations.findAllById(codes).associate { it.code to it.toResponse() }
    }

    private fun Trip.toTripResponse(stationsByCode: Map<String, StationResponse>): TripResponse =
        toResponse(stationsByCode.getValue(originCode), stationsByCode.getValue(destinationCode))
}

// Builds a composable Specification from the filter. Each clause is added only when its field is present,
// so only supplied filters constrain the query (an empty filter matches everything).
private fun TripFilter.toSpecification(): Specification<Trip> =
    Specification.allOf(
        listOfNotNull(
            origin?.let { eq("originCode", it) },
            destination?.let { eq("destinationCode", it) },
            carrierCode?.let { eq("carrierCode", it) },
            serviceDate?.let { eq("serviceDate", it) },
            departAfter?.let { departsAtOrAfter(it) },
            departBefore?.let { departsAtOrBefore(it) },
            maxPriceCents?.let { priceAtMost(it) },
        ),
    )

private fun eq(attribute: String, value: Any): Specification<Trip> =
    Specification { root, _, cb -> cb.equal(root.get<Any>(attribute), value) }

private fun departsAtOrAfter(t: OffsetDateTime): Specification<Trip> =
    Specification { root, _, cb -> cb.greaterThanOrEqualTo(root.get<OffsetDateTime>("departureTime"), t) }

private fun departsAtOrBefore(t: OffsetDateTime): Specification<Trip> =
    Specification { root, _, cb -> cb.lessThanOrEqualTo(root.get<OffsetDateTime>("departureTime"), t) }

private fun priceAtMost(max: Long): Specification<Trip> =
    Specification { root, _, cb -> cb.lessThanOrEqualTo(root.get<Long>("priceCents"), max) }
