package it.g03.orders.gateway

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.Callable

/**
 * Single place where the Resilience4j policies are composed for an outbound gateway call (Pillar C, Fase 2 #2).
 * Both [HttpPaymentsGateway] and [HttpInventoryGateway] route their HTTP calls through here so the composition
 * ORDER is defined exactly once (DRY) and cannot drift between gateways.
 *
 * Composition (the canonical Resilience4j order, deck 11 / plan #2.5):
 *
 *     Retry( CircuitBreaker( Bulkhead( call ) ) )
 *
 * Retry is OUTERMOST, Bulkhead INNERMOST — achieved by applying `.withBulkhead` first and `.withRetry` last
 * (each `.withX` wraps the previous). Consequences (all intended, documented in docs/design.md decision 7):
 *  - Breaker OPEN -> the inner call throws `CallNotPermittedException`; it is NOT in the retry's retry-exceptions,
 *    so the retry does not loop on it -> fast-fail, no retry storm (slide 45).
 *  - Bulkhead full -> `BulkheadFullException`; also not retried, and added to the breaker's ignore-exceptions so a
 *    local concurrency cap does not by itself open the breaker (genuine slowness is already caught by the >2s
 *    slow-call rate).
 *  - Retry outermost => every attempt is a data point for the breaker, so it opens faster under a sustained fault.
 *
 * Applied PROGRAMMATICALLY via the injected registries (NOT the `@Retry`/`@Bulkhead` annotations): same rationale as
 * the #1 breaker — the resilience4j-spring-boot3 starter's AOP aspect is not guaranteed to weave on Spring Boot 4
 * (docs/agent-log.md #2), whereas the registry beans are verified to bind (OrdersApplicationTests), so the
 * decoration is deterministic.
 */
@Component
class GatewayResilience(
    private val circuitBreakers: CircuitBreakerRegistry,
    private val retries: RetryRegistry,
    private val bulkheads: BulkheadRegistry,
) {
    /**
     * Runs [block] decorated with bulkhead + circuit breaker + retry — for the money-path call to Payments (the #1
     * breaker). [instance] selects the Resilience4j instance ("payments") configured in application.yml.
     */
    fun <T> callWithBreaker(instance: String, block: () -> T): T = decorate(instance, useBreaker = true, block)

    /**
     * Runs [block] decorated with bulkhead + retry only — NO breaker, for Inventory (the contended resource, not a
     * money-path coordinator). [instance] selects the Resilience4j instance ("inventory") configured in application.yml.
     */
    fun <T> call(instance: String, block: () -> T): T = decorate(instance, useBreaker = false, block)

    // Any resilience signal (CallNotPermittedException / BulkheadFullException / the exhausted transport fault) is
    // rethrown to the caller, which maps it to its own domain result.
    private fun <T> decorate(instance: String, useBreaker: Boolean, block: () -> T): T {
        // Compose innermost -> outermost so the runtime order is Retry( CircuitBreaker( Bulkhead( block ) ) ).
        var callable: Callable<T> = Bulkhead.decorateCallable(bulkheads.bulkhead(instance), Callable { block() })
        if (useBreaker) {
            callable = CircuitBreaker.decorateCallable(circuitBreakers.circuitBreaker(instance), callable)
        }
        callable = Retry.decorateCallable(retries.retry(instance), callable)
        return callable.call()
    }
}
