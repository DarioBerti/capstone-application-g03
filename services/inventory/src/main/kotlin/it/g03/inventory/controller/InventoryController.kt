package it.g03.inventory.controller

import it.g03.inventory.dto.AvailabilityResponse
import it.g03.inventory.entities.TripSeats
import it.g03.inventory.repository.TripSeatsRepository
import it.g03.inventory.service.InventoryService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.*

/**
 * Read surface over the seat counts. The reserve/confirm/release command surface is no longer HTTP —
 * Orders drives it asynchronously over Kafka (reserve-seats/confirm-seats/release-seats), with the answer
 * coming back through the transactional outbox.
 *
 * Only `GET /availability` (a paginated DTO projection) is exposed through the public ingress, so the
 * storefront can show live seat counts while browsing. The `/items` GETs below return the raw entity and
 * stay INTERNAL/debug-only (used by chaos/demo runs to prove the "never oversell" invariant directly).
 */
@RestController   // exposes this class as a REST controller (will return values as HTTP responses)
@RequestMapping("/api/inventory") // sets the base path for every endpoint defined in this controller
class InventoryController(
    private val items: TripSeatsRepository,
    private val service: InventoryService,
) {

    // GET /api/inventory/availability -> paginated public seat-availability projection (DTO, not the raw entity).
    // The ONLY inventory read routed through the public ingress; browse traffic lands here.
    @GetMapping("/availability")
    fun availability(@PageableDefault(size = 200) pageable: Pageable): Page<AvailabilityResponse> =
        service.availability(pageable)

    /** Availability snapshot: also used to verify the "never oversell" invariant in chaos runs */
    // GET /api/inventory/items/{tripCode} -> looks up a single trip's seat counts by its trip code
    @GetMapping("/items/{tripCode}")
    fun item(@PathVariable tripCode: String): TripSeats =
        items.findById(tripCode).orElseThrow { NoSuchElementException("unknown trip: $tripCode") }

    // GET /api/inventory/items -> returns the full list of trip seat counts
    @GetMapping("/items")
    fun items(): List<TripSeats> = items.findAll()
}
