package it.g03.catalog.controller

import it.g03.catalog.dto.DestinationSummaryResponse
import it.g03.catalog.dto.StationResponse
import it.g03.catalog.dto.TripFilter
import it.g03.catalog.dto.TripResponse
import it.g03.catalog.service.CatalogService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

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

    // GET /api/catalog/stations -> paginated station list (origin/destination pickers); ?q= typeahead over
    // name/city/code.
    @GetMapping("/stations")
    fun listStations(
        @RequestParam(required = false) q: String?,
        @PageableDefault(size = 50) pageable: Pageable,
    ): Page<StationResponse> = service.listStations(q, pageable)

    // GET /api/catalog/stations/all -> the full station list for the storefront's load-once, filter-locally
    // picker. Small, near-static reference data (changes only on a new deploy), so it's served with a public
    // HTTP cache window rather than paginated.
    @GetMapping("/stations/all")
    fun listAllStations(): ResponseEntity<List<StationResponse>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
            .body(service.listAllStations())

    // GET /api/catalog/destinations/top -> the most-served upcoming destinations for the storefront's
    // "popular destinations" strip (station + cheapest-from price + future-departure count). Optional ?origin=
    // narrows to one departure hub; ?limit= (default 6) is clamped server-side to 1..24. Aggregated near-static
    // data, so it's served with a public HTTP cache window.
    @GetMapping("/destinations/top")
    fun topDestinations(
        @RequestParam(required = false) origin: String?,
        @RequestParam(defaultValue = "6") limit: Int,
    ): ResponseEntity<List<DestinationSummaryResponse>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .body(service.topDestinations(origin, limit))
}
