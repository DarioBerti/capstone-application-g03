package it.g03.inventory.controller

import it.g03.inventory.dto.ReservationResponse
import it.g03.inventory.dto.ReserveRequest
import it.g03.inventory.dto.toResponse
import it.g03.inventory.entities.InventoryItem
import it.g03.inventory.repository.InventoryItemRepository
import it.g03.inventory.service.InventoryService
import org.springframework.web.bind.annotation.*

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
        service.confirm(orderId, productId).toResponse()

    // POST /api/inventory/reservations/{orderId}/{productId}/release -> releases a held reservation and restocks (saga compensation)
    @PostMapping("/reservations/{orderId}/{productId}/release")
    fun release(@PathVariable orderId: String, @PathVariable productId: String): ReservationResponse =
        service.release(orderId, productId).toResponse()

    /** Availability snapshot: also used to verify the "never oversell" invariant in chaos runs */
    // GET /api/inventory/items/{productId} -> looks up a single inventory item by its product id
    @GetMapping("/items/{productId}")
    fun item(@PathVariable productId: String): InventoryItem =
        items.findById(productId).orElseThrow { NoSuchElementException("unknown product: $productId") }

    // GET /api/inventory/items -> returns the full list of inventory items
    @GetMapping("/items")
    fun items(): List<InventoryItem> = items.findAll()
}
