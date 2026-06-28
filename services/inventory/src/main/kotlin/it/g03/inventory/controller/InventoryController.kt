package it.g03.inventory.controller

import it.g03.inventory.entities.InventoryItem
import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.repository.InventoryItemRepository
import it.g03.inventory.service.InventoryService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.*
import java.time.Instant

// request body for a reserve call ("hold these seats now")
data class ReserveRequest(val orderId: String, val productId: String, val qty: Int = 1)

// response body carrying the reservation outcome back to the caller (Orders)
data class ReservationResponse(
    val orderId: String,
    val productId: String,
    val qty: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    /** true when this call hit the idempotent replay path (duplicate delivery/retry) */
    val replayed: Boolean,
)

// maps a persisted Reservation entity to the API response DTO
private fun Reservation.toResponse(replayed: Boolean = false) =
    ReservationResponse(orderId, productId, qty, status, expiresAt, replayed)

/**
 * Internal synchronous API, called by Orders inside its async pipeline ("a reservation decision must be made now"). Not exposed through the public ingress
 *
 * Domain outcomes (RESERVED vs REJECTED/sold-out) are returned as 200 + status field, not as HTTP errors: sold-out is a valid business answer the saga must branch on, while HTTP errors are reserved for transport/contract problems (and would trip retries and the caller's circuit breaker for something that is not a failure)
 */

@RestController   // exposes this class as a REST controller (will return values as HTTP responses)

@RequestMapping("/api/inventory") // sets the base path for every endpoint defined in this controller
class InventoryController(
    private val service: InventoryService,
    private val items: InventoryItemRepository,
) {

    // POST /api/inventory/reservations -> holds seats for an order (returns RESERVED or REJECTED)
    @PostMapping("/reservations")
    fun reserve(@RequestBody request: ReserveRequest): ReservationResponse {
        val outcome = service.reserve(request.orderId, request.productId, request.qty)
        return outcome.reservation.toResponse(replayed = outcome.replayed)
    }

    // POST /api/inventory/reservations/{orderId}/{productId}/confirm -> confirms a held reservation (payment authorized)
    @PostMapping("/reservations/{orderId}/{productId}/confirm")
    fun confirm(@PathVariable orderId: String, @PathVariable productId: String): ReservationResponse =
        terminalReadOnLockLoss(orderId, productId) { service.confirm(orderId, productId) }

    // POST /api/inventory/reservations/{orderId}/{productId}/release -> releases a held reservation and restocks (saga compensation)
    @PostMapping("/reservations/{orderId}/{productId}/release")
    fun release(@PathVariable orderId: String, @PathVariable productId: String): ReservationResponse =
        terminalReadOnLockLoss(orderId, productId) { service.release(orderId, productId) }

    /**
     * Runs an idempotent terminal transition (confirm/release) and, if it LOSES the optimistic-lock race to a
     * concurrent driver (the reconciler expired the same hold first, @Version on Reservation), re-reads and returns the
     * now-terminal state as a clean 200 instead of a spurious 5xx. The re-read is idempotent (the row is already
     * terminal), so the caller (Orders) sees the real outcome — e.g. EXPIRED -> it compensates — without a retry round-trip
     */
    private inline fun terminalReadOnLockLoss(orderId: String, productId: String, op: () -> Reservation): ReservationResponse =
        try {
            op().toResponse()
        } catch (e: ObjectOptimisticLockingFailureException) {
            service.find(orderId, productId).toResponse()
        }

    /** Availability snapshot: also used to verify the "never oversell" invariant in chaos runs */
    // GET /api/inventory/items/{productId} -> looks up a single inventory item by its product id
    @GetMapping("/items/{productId}")
    fun item(@PathVariable productId: String): InventoryItem =
        items.findById(productId).orElseThrow { NoSuchElementException("unknown product: $productId") }

    // GET /api/inventory/items -> returns the full list of inventory items
    @GetMapping("/items")
    fun items(): List<InventoryItem> = items.findAll()

    // converts a missing product/reservation into a 404 response with a small JSON error body
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    // converts an invalid request (e.g. non-positive qty) into a 400 response with a small JSON error body
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
}
