package it.g03.catalog.controller

import it.g03.catalog.dto.StationResponse
import it.g03.catalog.dto.TripFilter
import it.g03.catalog.dto.TripResponse
import it.g03.catalog.service.CatalogService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public read API (exposed through the ingress at /api/catalog). Read-mostly and tolerant of staleness
 * by contract — it sits OUTSIDE the money path — and is a candidate for caching/replicas later. HTTP-only:
 * it delegates to CatalogService and returns DTOs; it never touches the repository (Controller -> Service
 * -> Repository).
 */
@RestController
@RequestMapping("/api/catalog")
class CatalogController(private val service: CatalogService) {

    // GET /api/catalog/trips -> paginated, filterable list of trips (never an unbounded List).
    // Filters (?origin=&destination=&carrierCode=&serviceDate=&departAfter=&departBefore=&maxPriceCents=)
    // bind into TripFilter; ?page/?size/?sort into Pageable. Default sort: earliest departure first.
    @GetMapping("/trips")
    fun listTrips(
        filter: TripFilter,
        @PageableDefault(size = 20, sort = ["departureTime"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): Page<TripResponse> = service.search(filter, pageable)

    // GET /api/catalog/trips/{code} -> looks up a single trip by its public business code (e.g. FR9592-20260629).
    @GetMapping("/trips/{code}")
    fun tripByCode(@PathVariable code: String): TripResponse = service.byCode(code)

    // GET /api/catalog/stations -> paginated station list (origin/destination pickers).
    @GetMapping("/stations")
    fun listStations(@PageableDefault(size = 50) pageable: Pageable): Page<StationResponse> =
        service.listStations(pageable)
}
