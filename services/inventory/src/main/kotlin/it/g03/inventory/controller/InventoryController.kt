package it.g03.inventory.controller

import it.g03.inventory.dto.ReservationResponse
import it.g03.inventory.dto.ReserveRequest
import it.g03.inventory.dto.toResponse
import it.g03.inventory.entities.TripSeats
import it.g03.inventory.repository.TripSeatsRepository
import it.g03.inventory.service.InventoryService
import org.springframework.web.bind.annotation.*

/**
 * Internal synchronous API, called by Orders inside its async pipeline ("a reservation decision must be made now"). Not exposed through the public ingress.
 *
 * Domain outcomes (RESERVED vs REJECTED/sold-out) are returned as 200 + status field, not as HTTP errors: sold-out is a valid business answer the saga must branch on, while HTTP errors are reserved for transport/contract problems (and would trip retries and the caller's circuit breaker for something that is not a failure).
 */
@RestController   // exposes this class as a REST controller (will return values as HTTP responses)
@RequestMapping("/api/inventory") // sets the base path for every endpoint defined in this controller
class InventoryController(
    private val service: InventoryService,
    private val items: TripSeatsRepository,
) {

    // POST /api/inventory/reservations -> holds seats for an order (returns RESERVED or REJECTED)
    @PostMapping("/reservations")
    fun reserve(@RequestBody request: ReserveRequest): ReservationResponse {
        val outcome = service.reserve(request.orderId, request.tripCode, request.qty)
        return outcome.reservation.toResponse(replayed = outcome.replayed)
    }

    // POST /api/inventory/reservations/{orderId}/{tripCode}/confirm -> confirms a held reservation (payment authorized)
    @PostMapping("/reservations/{orderId}/{tripCode}/confirm")
    fun confirm(@PathVariable orderId: String, @PathVariable tripCode: String): ReservationResponse =
        service.confirm(orderId, tripCode).toResponse()

    // POST /api/inventory/reservations/{orderId}/{tripCode}/release -> releases a held reservation and restocks (saga compensation)
    @PostMapping("/reservations/{orderId}/{tripCode}/release")
    fun release(@PathVariable orderId: String, @PathVariable tripCode: String): ReservationResponse =
        service.release(orderId, tripCode).toResponse()

    /** Availability snapshot: also used to verify the "never oversell" invariant in chaos runs */
    // GET /api/inventory/items/{tripCode} -> looks up a single trip's seat counts by its trip code
    @GetMapping("/items/{tripCode}")
    fun item(@PathVariable tripCode: String): TripSeats =
        items.findById(tripCode).orElseThrow { NoSuchElementException("unknown trip: $tripCode") }

    // GET /api/inventory/items -> returns the full list of trip seat counts
    @GetMapping("/items")
    fun items(): List<TripSeats> = items.findAll()
}
