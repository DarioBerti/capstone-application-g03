package it.g03.orders.controller

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Backpressure / load shedding for the synchronous intake (Pillar C, Fase 2 #3)
 *
 * The public entry "POST /api/orders" is the one place where a traffic spike can pile work on the money path faster than it drains. "backpressure is not failure: it is controlled refusal" and "queues just delay the failure". So when the per-window budget is exhausted we REFUSE the request immediately (HTTP 429 + Retry-After, mapped in OrdersController) instead of queueing it until the system collapses
 *
 * This is the EDGE counterpart of  GatewayResilience: same PROGRAMMATIC approach: we drive the policy through the injected RateLimiterRegistry rather than the "@RateLimiter" annotation, because the resilience4j-spring-boot3 starter's AOP aspect is not guaranteed to weave on Spring Boot 4 (docs/agent-log.md #2); the registry bean is verified to bind, so the decoration is deterministic.
 * The shed is INTAKE-only: internal saga calls are governed by the breaker/bulkhead, never by a 429 (#3.5)
 */
@Component
class OrdersIntakeRateLimiter(
    rateLimiters: RateLimiterRegistry,
    meterRegistry: MeterRegistry,
) {
    // The "orders-intake" instance is configured in application.yml (limit / window / timeout=0 = no queueing)
    private val limiter = rateLimiters.rateLimiter(INSTANCE)

    // Observability of the policy: every shed is counted so load shedding is visible as a time series.
    // Low-cardinality by design (no orderId/userId labels) -> "high-cardinality labels destroy metrics systems"
    private val shed = meterRegistry.counter("orders_intake_shed_total")

    /**
     * Runs "block" only if an intake permit is available RIGHT NOW. With "timeout-duration": PT0S in config, io.github.resilience4j.ratelimiter.RateLimiter.acquirePermission returns immediately:
     * "true" if within the budget (proceed)
     * "false" if the window is exhausted (shed). On a shed we count it and throw [IntakeOverloadedException], which the controller maps to HTTP 429 + Retry-After — controlled refusal, not a 5xx error
     */
    fun <T> withPermit(block: () -> T): T {
        if (!limiter.acquirePermission()) {
            shed.increment()
            throw IntakeOverloadedException(retryAfterSeconds())
        }
        return block()
    }

    // Retry-After hint DERIVED from the configured window (not a magic number), so the header and the limiter can never drift apart: a client that waits this long will find the budget refreshed. Rounded up, min 1s
    private fun retryAfterSeconds(): Long {
        val windowMillis = limiter.rateLimiterConfig.limitRefreshPeriod.toMillis()
        return maxOf(1L, (windowMillis + 999) / 1000)
    }

    companion object {
        // Matches resilience4j.ratelimiter.instances.<name> in application.yml.
        const val INSTANCE = "orders-intake"
    }
}

/**
 * Thrown when the intake is over its rate budget. Carries the retryAfterSeconds hint so the controller can
 * set the standard Retry-After response header. It is a controlled-refusal signal, NOT an internal failure
 */
class IntakeOverloadedException(val retryAfterSeconds: Long) :
    RuntimeException("orders intake overloaded — shed to protect the money path")
