package it.g03.orders.controller

import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import it.g03.orders.dto.AddLegRequest
import it.g03.orders.dto.OrderResponse
import it.g03.orders.dto.toResponse
import it.g03.orders.entities.OrderStatus
import it.g03.orders.service.OrderService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * PUBLIC API (exposed through the ingress at /api/orders) for the two-phase, human-gated booking flow:
 *   create DRAFT -> add outbound/return leg(s) (reserve-on-add, starts the hold window) -> pay within the window.
 *
 * The buyer identity is always taken from the authenticated JWT, never the request body. `POST /pay` returns
 * 202 Accepted — authorization succeeded and the confirm/capture saga proceeds asynchronously; clients poll GET
 * to watch the booking reach a terminal state.
 */
@RestController
@RequestMapping("/api/orders")
class OrdersController(
    private val service: OrderService,
) {

    // POST /api/orders -> create an empty DRAFT booking (201). Optional Idempotency-Key replays instead of duplicating.
    @RateLimiter(name = "orders-intake")
    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<OrderResponse> {
        val view = service.createDraft(buyerSub(jwt), jwt.getClaimAsString("email"), idempotencyKey)
        return ResponseEntity.status(HttpStatus.CREATED).body(view.toResponse())
    }

    // POST /api/orders/{id}/legs -> add an outbound/return leg (reserve-on-add); the first leg starts the window.
    @PostMapping("/{orderId}/legs")
    fun addLeg(
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: AddLegRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<OrderResponse> {
        val view = service.addLeg(orderId, buyerSub(jwt), request.direction, request.tripCode, request.qty, request.priceToken)
        return ResponseEntity.status(HttpStatus.CREATED).body(view.toResponse())
    }

    // DELETE /api/orders/{id}/legs/{legId} -> remove a leg (releasing its hold); empties back to DRAFT if the last.
    @DeleteMapping("/{orderId}/legs/{legId}")
    fun removeLeg(
        @PathVariable orderId: UUID,
        @PathVariable legId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): OrderResponse = service.removeLeg(orderId, buyerSub(jwt), legId).toResponse()

    // POST /api/orders/{id}/pay -> authorize within the window; 202 (confirm/capture proceed asynchronously).
    @RateLimiter(name = "orders-intake")
    @PostMapping("/{orderId}/pay")
    fun pay(
        @PathVariable orderId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<OrderResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(service.pay(orderId, buyerSub(jwt)).toResponse())

    // GET /api/orders -> the authenticated buyer's bookings (my-bookings), paginated. Optional filters: `status`
    // (CSV of OrderStatus) and a `createdFrom`/`createdTo` (ISO LocalDate) range resolved to inclusive UTC day
    // bounds. Default order is newest-first; an explicit client `sort` param overrides it.
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) status: Set<OrderStatus>?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) createdFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) createdTo: LocalDate?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Page<OrderResponse> =
        service.listForBuyer(
            buyerSub(jwt),
            status,
            createdFrom?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
            createdTo?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
            pageable,
        ).map { it.toResponse() }

    // GET /api/orders/{orderId} -> the booking with its legs, hold window, and terminal state (buyer-scoped).
    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: UUID, @AuthenticationPrincipal jwt: Jwt): OrderResponse =
        service.get(orderId, buyerSub(jwt)).toResponse()

    // Buyer identity comes from the validated token (guard a missing `sub` with a 400 instead of an NPE).
    private fun buyerSub(jwt: Jwt): String =
        jwt.subject ?: throw IllegalArgumentException("token is missing the subject (sub) claim")
}
