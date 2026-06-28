package it.g03.payments.service

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.entities.PaymentAuthorization
import it.g03.payments.events.EventPublisher
import it.g03.payments.repository.PaymentAuthorizationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

// Outcome of an authorize call; "replayed = true" means the idempotent replay path fired
data class AuthorizeOutcome(val authorization: PaymentAuthorization, val replayed: Boolean)

// payment-authorized event payload (informational; see EventPublishing.kt)
data class PaymentAuthorizedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val amountCents: Long,
    val occurredAt: Instant = Instant.now(),
)

@Service   // marks this class as a Spring service bean (business logic layer)
class PaymentsService(
    private val authorizations: PaymentAuthorizationRepository,
    private val events: EventPublisher,
    private val meterRegistry: MeterRegistry,
    /**
     * (mock) PSP decision rule, deliberately deterministic and demo-controllable: amounts >= this threshold are DECLINED ("insufficient funds"); lets us exercise the saga compensation path (release reservation) on demand: order a huge qty → decline
     */
    @Value("\${payments.decline-threshold-cents:100000}") private val declineThresholdCents: Long,   // injects configured threshold (with a default debug value here) from the env/config
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Helper to increments the per-outcome counter (authorized/declined/replayed) for observability
    private fun outcomes(outcome: String) =
        meterRegistry.counter("payments_authorizations_total", "outcome", outcome)

    /**
     * STRICT idempotency ("mustn't double-charge", Pillar B) -> same pattern as Inventory.reserve:
     * - fast path: an authorization for this orderId exists -> return the STORED outcome, charge nothing (a retried call is a no-op);
     * - race path: two duplicates miss the pre-check concurrently -> both would charge, but UNIQUE(order_id) lets exactly ONE insert commit; the loser's transaction (charge included) rolls back, and its retry replays the stored outcome
     */

    @Transactional   // run the whole method in a single database transaction (all-or-nothing)
    fun authorize(orderId: String, amountCents: Long): AuthorizeOutcome {
        require(amountCents > 0) { "amountCents must be positive" }

        authorizations.findByOrderId(orderId)?.let {
            outcomes("replayed").increment()
            log.info("authorize replay for order={} -> {}", orderId, it.status)
            return AuthorizeOutcome(it, replayed = true)
        }

        // We do not call a real payment provider here. Instead, a simple fixed rule decides the result: amounts above the threshold are declined, and smaller amounts are approved.
        // This keeps the demo predictable; chaos and latency tests add failures at the network level (not here)
        val status =
            if (amountCents >= declineThresholdCents) AuthorizationStatus.DECLINED
            else AuthorizationStatus.AUTHORIZED

        val auth = authorizations.save(
            PaymentAuthorization(orderId = orderId, amountCents = amountCents, status = status),
        )
        if (status == AuthorizationStatus.AUTHORIZED) {
            publishAfterCommit(
                "payment-authorized",
                orderId,
                PaymentAuthorizedEvent(orderId = orderId, amountCents = amountCents),
            )
        }
        outcomes(if (status == AuthorizationStatus.AUTHORIZED) "authorized" else "declined").increment()
        log.info("authorize order={} amountCents={} -> {}", orderId, amountCents, status)
        return AuthorizeOutcome(auth, replayed = false)
    }

    // Looks up the single authorization for an order, or raises a domain not-found error
    fun find(orderId: String): PaymentAuthorization =
        authorizations.findByOrderId(orderId)
            ?: throw NoSuchElementException("no authorization for order=$orderId")

    /**
     * Publish only AFTER the surrounding transaction commits: publishing inside the transaction could announce an authorization that then rolls back (ghost event)
     */
    private fun publishAfterCommit(topic: String, key: String, payload: Any) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = events.publish(topic, key, payload)
            },
        )
    }
}
