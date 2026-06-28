package it.g03.orders.gateway

import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/*
 * Synchronous gateways to the sibling services ("a decision must be made NOW" -> these calls happen INSIDE the async pipeline, see docs/design.md). They are interfaces so the saga is testable with fakes; the HTTP implementations carry connect/read TIMEOUTS on every call (Pillar C: no unbounded waits).
 * Both HTTP implementations route every remote call through [GatewayResilience] (Fase 2 #1+#2): bulkhead + retry(backoff+jitter) on both, plus the circuit breaker on Payments.
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
    fun void(orderId: String): AuthorizationResult                          // reverse the charge (saga compensation, idempotent)
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
    // bulkhead + retry only (NO breaker): Inventory is the contended resource, not a money-path coordinator. reserve/confirm/release are idempotent (UNIQUE(order_id, product_id), design #6), so a retry of a transient transport fault is safe. After the retries are exhausted the exception still propagates (as before #2) -> Kafka redelivery / the recovery sweeper's per-order catch.
    private val resilience: GatewayResilience,
) : InventoryGateway {

    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build()

    // POST /api/inventory/reservations -> place a hold on the seats for this order
    override fun reserve(orderId: String, productId: String, qty: Int): ReservationResult =
        resilience.call("inventory") {
            val dto = client.post()
                .uri("/api/inventory/reservations")
                .body(mapOf("orderId" to orderId, "productId" to productId, "qty" to qty))
                .retrieve()
                .body(InventoryReservationDto::class.java)!!
            ReservationResult(dto.status, dto.replayed)
        }

    // POST /api/inventory/reservations/{o}/{p}/confirm -> turn the hold into a firm sale (idempotent)
    override fun confirm(orderId: String, productId: String): ReservationResult =
        resilience.call("inventory") {
            val dto = client.post()
                .uri("/api/inventory/reservations/{o}/{p}/confirm", orderId, productId)
                .retrieve()
                .body(InventoryReservationDto::class.java)!!
            ReservationResult(dto.status, dto.replayed)
        }

    // POST /api/inventory/reservations/{o}/{p}/release -> give the hold back (saga compensation, idempotent)
    override fun release(orderId: String, productId: String): ReservationResult =
        resilience.call("inventory") {
            val dto = client.post()
                .uri("/api/inventory/reservations/{o}/{p}/release", orderId, productId)
                .retrieve()
                .body(InventoryReservationDto::class.java)!!
            ReservationResult(dto.status, dto.replayed)
        }
}

@Component // registers this HTTP gateway as the production PaymentsGateway bean
class HttpPaymentsGateway(
    @Value("\${app.payments-base-url}") baseUrl: String,
    // Resilience policies (bulkhead + circuit breaker + retry) are applied PROGRAMMATICALLY via [GatewayResilience], NOT the @CircuitBreaker/@Retry/@Bulkhead annotations: the resilience4j starter targets Spring Boot 3, so the AOP aspects might not weave on Boot 4 (docs/agent-log.md #2) — the registry beans are verified to bind (OrdersApplicationTests), so decorating through them is deterministic. Config: resilience4j.{circuitbreaker,retry,bulkhead}.instances.payments in application.yml
    private val resilience: GatewayResilience,
    private val meterRegistry: MeterRegistry,
) : PaymentsGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutFactory()).build()

    // Outcome-tagged failure counter for the Orders->Payments call (Pillar D, same idiom as InventoryService.outcomes): outcome = unavailable | client_error | bulkhead_full
    private fun failures(outcome: String) = meterRegistry.counter("orders_payment_failures_total", "outcome", outcome)

    // POST /api/payments/authorizations -> authorize the charge for this order (idempotent on the Payments side)
    override fun authorize(orderId: String, amountCents: Long): AuthorizationResult =
        try {
            // The call is wrapped Retry(CircuitBreaker(Bulkhead(call))) by GatewayResilience. The retry absorbs a transient transport fault (5xx / timeout) before it ever reaches the breaker; a sustained fault opens the breaker (then CallNotPermittedException -> fast-fail). A DECLINED is a normal 200 body, so it returns here cleanly and never trips any policy — declining a card is a business outcome, not a fault
            resilience.callWithBreaker("payments") {
                val dto = client.post()
                    .uri("/api/payments/authorizations")
                    .body(mapOf("orderId" to orderId, "amountCents" to amountCents))
                    .retrieve()
                    .body(PaymentAuthorizationDto::class.java)!!
                AuthorizationResult(dto.status, dto.replayed)
            }
        } catch (e: CallNotPermittedException) {
            // breaker OPEN: the call was short-circuited, Payments was not hit
            unavailable(orderId, "circuit breaker open")
        } catch (e: BulkheadFullException) {
            // bulkhead saturated: the Payments concurrency cap is full -> shed this call rather than queue it. Park (PAYMENT_PENDING) and let the recovery sweep retry when pressure eases — controlled refusal, not a reject. Distinct outcome so a dashboard tells local saturation apart from a downstream-down/breaker-open "unavailable"
            unavailable(orderId, "payments bulkhead full", "bulkhead_full")
        } catch (e: HttpClientErrorException) {
            // A 4xx is a PERMANENT, deterministic client error (malformed / unprocessable authorization request), NOT a transient fault — retrying it would park the order forever. It is in the breaker's ignore-exceptions so it never trips the breaker; here we drive the order to a TERMINAL outcome instead of UNAVAILABLE. No charge happens on a 4xx, so mapping it to DECLINED is money-safe (the saga releases the hold and rejects)
            log.warn("payments rejected order {} with client error {} — terminal DECLINED", orderId, e.statusCode)
            failures("client_error").increment()
            AuthorizationResult("DECLINED", false)
        } catch (e: Exception) {
            // genuine transport fault recorded by the breaker above (connect/read timeout, 5xx, connection refused): map to the fallback so the saga parks the order
            unavailable(orderId, e.message ?: e.javaClass.simpleName)
        }

    // POST /api/payments/authorizations/{orderId}/void -> reverse the charge (saga compensation for a hold that expired
    // AFTER authorize, Fase 2 #4). Wrapped in the SAME "payments" policy as authorize (Retry(CircuitBreaker(Bulkhead)))
    // because it hits the same downstream and is idempotent (slide 22). The fallback ladder mirrors authorize EXACTLY,
    // keeping the outcome tags distinct (local saturation vs downstream-down) so a dashboard can still tell them apart
    override fun void(orderId: String): AuthorizationResult =
        try {
            resilience.callWithBreaker("payments") {
                val dto = client.post()
                    .uri("/api/payments/authorizations/{orderId}/void", orderId)
                    .retrieve()
                    .body(PaymentAuthorizationDto::class.java)!!
                AuthorizationResult(dto.status, dto.replayed)
            }
        } catch (e: CallNotPermittedException) {
            voidUnavailable(orderId, "circuit breaker open")
        } catch (e: BulkheadFullException) {
            voidUnavailable(orderId, "payments bulkhead full", "bulkhead_full")
        } catch (e: HttpClientErrorException) {
            if (e.statusCode.value() == 404) {
                // No authorization on record -> there is no charge to reverse: a BENIGN outcome (e.g. a void of an order
                // that never authorized), NOT a failed compensation. The saga treats NOT_FOUND like a successful no-op
                log.warn("payments void for order {} -> 404 (no authorization to reverse)", orderId)
                AuthorizationResult("NOT_FOUND", false)
            } else {
                // Any other 4xx is a permanent, deterministic client error on the void itself -> terminal FAILED (the
                // saga keeps the order parked so the recovery sweep retries; a 4xx will not self-heal, but it is rare)
                log.warn("payments void rejected order {} with client error {} — FAILED", orderId, e.statusCode)
                failures("client_error").increment()
                AuthorizationResult("FAILED", false)
            }
        } catch (e: Exception) {
            voidUnavailable(orderId, e.message ?: e.javaClass.simpleName)
        }

    // Circuit-breaker fallback: the saga maps "UNAVAILABLE" to the PAYMENT_PENDING parking state — it does NOT reject the order and does NOT propagate the fault (Pillar C: contain, don't propagate). Emitting a metric keeps the policy observable (deck 10). [outcome] tags the cause (unavailable = transport fault / breaker open; bulkhead_full = local saturation)
    private fun unavailable(orderId: String, reason: String, outcome: String = "unavailable"): AuthorizationResult {
        log.warn("payments unavailable for order {} ({}) — parking PAYMENT_PENDING", orderId, reason)
        failures(outcome).increment()
        return AuthorizationResult("UNAVAILABLE", false)
    }

    // Same as [unavailable] but for the void compensation: the saga keeps the order PARKED so [OrderRecoverySweeper]
    // retries the void (Fase 2 #4, Option B). The charge stays live but is actively retried, not abandoned
    private fun voidUnavailable(orderId: String, reason: String, outcome: String = "unavailable"): AuthorizationResult {
        log.warn("payments void unavailable for order {} ({}) — compensation deferred to recovery sweep", orderId, reason)
        failures(outcome).increment()
        return AuthorizationResult("UNAVAILABLE", false)
    }
}
