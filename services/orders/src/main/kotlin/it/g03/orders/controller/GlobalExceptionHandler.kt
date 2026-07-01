package it.g03.orders.controller

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.micrometer.core.instrument.MeterRegistry
import it.g03.orders.dto.ErrorResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Centralizes domain-exception → HTTP mapping for every controller in this service, so controllers
 * stay HTTP-only and the mapping isn't duplicated per controller.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    rateLimiters: RateLimiterRegistry,
    meterRegistry: MeterRegistry,
) {

    private val intakeLimiter = rateLimiters.rateLimiter(INTAKE_LIMITER)

    // Low-cardinality by design (no orderId/userId labels): one shed = one increment, visible as a time series.
    private val shed = meterRegistry.counter("orders_intake_shed_total")

    // A missing order/resource → 404 with a small JSON error body.
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message))

    // An invalid request (blank tripCode / non-positive qty / missing sub) → 400 with a small JSON error body.
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message))

    /**
     * A concurrent POST that loses the UNIQUE(idempotency_key) race → 409 Conflict. The common sequential
     * retry is handled earlier by the fast-path replay in OrderService; this only fires for two in-flight
     * requests sharing a key, and a subsequent retry resolves to the committed order.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("duplicate request in flight (idempotency key); retry to fetch the existing order"))

    /**
     * The intake @RateLimiter rejected the request (over the per-window budget) → 429 Too Many Requests +
     * Retry-After, and the shed is counted. Controlled refusal, not a 5xx: the client is told when to retry
     * and the money path is protected.
     */
    @ExceptionHandler(RequestNotPermitted::class)
    fun overloaded(e: RequestNotPermitted): ResponseEntity<ErrorResponse> {
        shed.increment()
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds().toString())
            .body(ErrorResponse("orders intake overloaded, retry later"))
    }

    // Retry-After hint derived from the configured limiter window (not a magic number), so a client that waits
    // this long finds the budget refreshed. Rounded up, min 1s.
    private fun retryAfterSeconds(): Long {
        val windowMillis = intakeLimiter.rateLimiterConfig.limitRefreshPeriod.toMillis()
        return maxOf(1L, (windowMillis + 999) / 1000)
    }

    companion object {
        const val INTAKE_LIMITER = "orders-intake"
    }
}
