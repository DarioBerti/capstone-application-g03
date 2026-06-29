package it.g03.inventory.controller

import it.g03.inventory.dto.ReservationResponse
import it.g03.inventory.dto.ReserveRequest
import it.g03.inventory.dto.toResponse
import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.TripSeats
import it.g03.inventory.repository.TripSeatsRepository
import it.g03.inventory.service.InventoryService
import org.springframework.orm.ObjectOptimisticLockingFailureException
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
        terminalReadOnLockLoss(orderId, tripCode) { service.confirm(orderId, tripCode) }

    // POST /api/inventory/reservations/{orderId}/{tripCode}/release -> releases a held reservation and restocks (saga compensation)
    @PostMapping("/reservations/{orderId}/{tripCode}/release")
    fun release(@PathVariable orderId: String, @PathVariable tripCode: String): ReservationResponse =
        terminalReadOnLockLoss(orderId, tripCode) { service.release(orderId, tripCode) }

    /**
     * Runs an idempotent terminal transition (confirm/release) and, if it LOSES the optimistic-lock race to a
     * concurrent driver (the reconciler expired the same hold first, @Version on Reservation), re-reads and returns the
     * now-terminal state as a clean 200 instead of a spurious 5xx. The re-read is idempotent (the row is already
     * terminal), so the caller (Orders) sees the real outcome — e.g. EXPIRED -> it compensates — without a retry round-trip
     */
    private inline fun terminalReadOnLockLoss(orderId: String, tripCode: String, op: () -> Reservation): ReservationResponse =
        try {
            op().toResponse()
        } catch (e: ObjectOptimisticLockingFailureException) {
            service.find(orderId, tripCode).toResponse()
        }

    /** Availability snapshot: also used to verify the "never oversell" invariant in chaos runs */
    // GET /api/inventory/items/{tripCode} -> looks up a single trip's seat counts by its trip code
    @GetMapping("/items/{tripCode}")
    fun item(@PathVariable tripCode: String): TripSeats =
        items.findById(tripCode).orElseThrow { NoSuchElementException("unknown trip: $tripCode") }

    // GET /api/inventory/items -> returns the full list of trip seat counts
    @GetMapping("/items")
    fun items(): List<TripSeats> = items.findAll()
}
