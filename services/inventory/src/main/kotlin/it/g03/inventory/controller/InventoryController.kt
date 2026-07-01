package it.g03.inventory.controller

import it.g03.inventory.entities.TripSeats
import it.g03.inventory.repository.TripSeatsRepository
import org.springframework.web.bind.annotation.*

/**
 * Inspection endpoints over the seat counts. The reserve/confirm/release command surface is no longer HTTP —
 * Orders now drives it asynchronously over Kafka (reserve-seats/confirm-seats/release-seats), with the answer
 * coming back through the transactional outbox. These read-only GETs remain so the chaos/demo runs can prove
 * the "never oversell" invariant directly against the live seat counts. Not exposed through the public ingress.
 */
@RestController   // exposes this class as a REST controller (will return values as HTTP responses)
@RequestMapping("/api/inventory") // sets the base path for every endpoint defined in this controller
class InventoryController(
    private val items: TripSeatsRepository,
) {

    /** Availability snapshot: also used to verify the "never oversell" invariant in chaos runs */
    // GET /api/inventory/items/{tripCode} -> looks up a single trip's seat counts by its trip code
    @GetMapping("/items/{tripCode}")
    fun item(@PathVariable tripCode: String): TripSeats =
        items.findById(tripCode).orElseThrow { NoSuchElementException("unknown trip: $tripCode") }

    // GET /api/inventory/items -> returns the full list of trip seat counts
    @GetMapping("/items")
    fun items(): List<TripSeats> = items.findAll()
}
