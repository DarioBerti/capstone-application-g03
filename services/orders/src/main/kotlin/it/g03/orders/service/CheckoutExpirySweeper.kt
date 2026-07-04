package it.g03.orders.service

import it.g03.orders.entities.OrderStatus
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

// A booking is expirable while it is pre-terminal AND has a hold window: HELD (awaiting the user) or PAYING
// (paid, but the pipeline hasn't finished). AUTHORIZED is post-pay convergence and is left to the timeout sweeper.
private val EXPIRABLE = listOf(OrderStatus.HELD, OrderStatus.PAYING)

/**
 * The GIVE-UP-at-the-window half of the backend-authoritative TTL (Pillar C). While a booking is within its hold
 * window the timeout sweeper RECOVERS it (re-driving a stuck post-pay step); once the window elapses this sweeper
 * gives up and rejects it, releasing the held legs. The rejection reason is dual-cause (see
 * [OrderStateMachine.expire]): EXPIRED_NO_PAYMENT if the user never paid, SERVICE_UNAVAILABLE if pay was accepted
 * but the pipeline stayed stuck.
 *
 * Backend-authoritative: expiry is decided here against the SERVER clock and the stored `expiresAt`, never the
 * client. Driven by Spring `@Scheduled`; gated by `app.checkout.expiry-sweeper.enabled` (tests set it false and
 * drive [expireOnce]/the state machine directly). Each order is expired in its own try/catch.
 */
@Component
class CheckoutExpirySweeper(
    private val orders: OrderRepository,
    private val stateMachine: OrderStateMachine,
    @Value("\${app.checkout.expiry-sweeper.enabled}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.checkout.expiry-sweeper.poll-interval}")
    fun sweep() {
        if (!enabled) return
        sweepOnce()
    }

    // One sweep pass. Package-visible so a test can drive it directly.
    internal fun sweepOnce() {
        val now = Instant.now()
        val expired = try {
            orders.findByStatusInAndExpiresAtBefore(EXPIRABLE, now)
        } catch (e: Exception) {
            log.warn("checkout expiry sweep query failed, will retry: {}", e.message)
            return
        }
        if (expired.isEmpty()) return
        log.info("expiring {} booking(s) past the checkout window", expired.size)
        for (order in expired) {
            try {
                stateMachine.expire(order)
            } catch (e: Exception) {
                log.warn("expiry of order {} failed, will retry next sweep: {}", order.id, e.message)
            }
        }
    }
}
