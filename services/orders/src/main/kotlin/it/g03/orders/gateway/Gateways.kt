package it.g03.orders.gateway

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/*
 * Synchronous gateways to the sibling services ("a decision must be made NOW" -> these calls happen INSIDE the async pipeline, see docs/design.md). They are interfaces so the saga is testable with fakes; the HTTP implementations carry connect/read TIMEOUTS on every call (Pillar C: no unbounded waits).
 * Phase 2 wraps these same gateways with Resilience4j (circuit breaker / retry+jitter / bulkhead)
 */

// Mirror of Inventory's response statuses (as always, strings to avoid duplicating the enum)
data class ReservationResult(val status: String, val replayed: Boolean)

// Mirror of Payments' response statuses
data class AuthorizationResult(val status: String, val replayed: Boolean)

interface InventoryGateway {
    fun reserve(orderId: String, productId: String, qty: Int): ReservationResult // place a hold on the seats
    fun confirm(orderId: String, productId: String): ReservationResult     // turn the hold into a firm sale
    fun release(orderId: String, productId: String): ReservationResult     // give the hold back (compensation)
}

interface PaymentsGateway {
    fun authorize(orderId: String, amountCents: Long): AuthorizationResult // charge/authorize the payment
}

// Shared bounded-timeout request factory: connect 2s, read 5s
private fun timeoutFactory() =
    JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    ).apply { setReadTimeout(Duration.ofSeconds(5)) }

// Exact mirrors of the sibling services' response bodies
private data class InventoryReservationDto(
    val orderId: String,
    val productId: String,
    val qty: Int,
    val status: String,
    val expiresAt: Instant,
    val replayed: Boolean,
)

private data class PaymentAuthorizationDto(
    val authorizationId: UUID,
    val orderId: String,
    val amountCents: Long,
    val status: String,
    val replayed: Boolean,
)

@Component // registers this HTTP gateway as the production InventoryGateway bean
class HttpInventoryGateway(
    // in-cluster the env var points at the Service DNS (http://inventory:8080)
    // the localhost default is for local multi-process dev
    @Value("\${app.inventory-base-url}") baseUrl: String,
) : InventoryGateway {

    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build()

    // POST /api/inventory/reservations -> place a hold on the seats for this order
    override fun reserve(orderId: String, productId: String, qty: Int): ReservationResult {
        val dto = client.post()
            .uri("/api/inventory/reservations")
            .body(mapOf("orderId" to orderId, "productId" to productId, "qty" to qty))
            .retrieve()
            .body(InventoryReservationDto::class.java)
            ?: error("empty reserve response from inventory for order=$orderId product=$productId")
        return ReservationResult(dto.status, dto.replayed)
    }

    // POST /api/inventory/reservations/{o}/{p}/confirm -> turn the hold into a firm sale (idempotent)
    override fun confirm(orderId: String, productId: String): ReservationResult {
        val dto = client.post()
            .uri("/api/inventory/reservations/{o}/{p}/confirm", orderId, productId)
            .retrieve()
            .body(InventoryReservationDto::class.java)
            ?: error("empty confirm response from inventory for order=$orderId product=$productId")
        return ReservationResult(dto.status, dto.replayed)
    }

    // POST /api/inventory/reservations/{o}/{p}/release -> give the hold back (saga compensation, idempotent)
    override fun release(orderId: String, productId: String): ReservationResult {
        val dto = client.post()
            .uri("/api/inventory/reservations/{o}/{p}/release", orderId, productId)
            .retrieve()
            .body(InventoryReservationDto::class.java)
            ?: error("empty release response from inventory for order=$orderId product=$productId")
        return ReservationResult(dto.status, dto.replayed)
    }
}

@Component // registers this HTTP gateway as the production PaymentsGateway bean
class HttpPaymentsGateway(
    @Value("\${app.payments-base-url}") baseUrl: String,
) : PaymentsGateway {

    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build()

    // POST /api/payments/authorizations -> authorize the charge for this order (idempotent)
    override fun authorize(orderId: String, amountCents: Long): AuthorizationResult {
        val dto = client.post()
            .uri("/api/payments/authorizations")
            .body(mapOf("orderId" to orderId, "amountCents" to amountCents))
            .retrieve()
            .body(PaymentAuthorizationDto::class.java)
            ?: error("empty authorize response from payments for order=$orderId")
        return AuthorizationResult(dto.status, dto.replayed)
    }
}
