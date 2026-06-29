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

// payment-authorized event payload (informational; see EventPublishing.kt)
data class PaymentAuthorizedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val amountCents: Long,
    val occurredAt: Instant = Instant.now(),
)

@Service   // marks this class as a Spring service bean (business logic layer)
class PaymentsServiceImpl(
    private val authorizations: PaymentAuthorizationRepository,
    private val events: EventPublisher,
    private val meterRegistry: MeterRegistry,
    /**
     * (mock) PSP decision rule, deliberately deterministic and demo-controllable: amounts >= this threshold are DECLINED ("insufficient funds"); lets us exercise the saga compensation path (release reservation) on demand: order a huge qty → decline
     */
    @Value("\${payments.decline-threshold-cents:100000}") private val declineThresholdCents: Long,   // injects configured threshold (with a default debug value here) from the env/config
) : PaymentsService {
    private val log = LoggerFactory.getLogger(javaClass)

    // Helper to increment the per-outcome counter (authorized/declined/replayed/voided) for observability
    private fun outcomes(outcome: String) =
        meterRegistry.counter("payments_authorizations_total", "outcome", outcome)

    /**
     * STRICT idempotency ("must not double-charge", Pillar B) -> same pattern as Inventory.reserve:
     * - fast path: an authorization for this orderId exists -> return the STORED outcome, charge nothing (a retried call is a no-op);
     * - race path: two duplicates miss the pre-check concurrently -> both would charge, but UNIQUE(order_id) lets exactly ONE insert commit; the loser's transaction (charge included) rolls back, and its retry replays the stored outcome.
     */
    @Transactional   // run the whole method in a single database transaction (all-or-nothing)
    override fun authorize(orderId: String, amountCents: Long): AuthorizeOutcome {
        require(amountCents > 0) { "amountCents must be positive" }

        authorizations.findByOrderId(orderId)?.let {
            outcomes("replayed").increment()
            log.info("authorize replay for order={} -> {}", orderId, it.status)
            return AuthorizeOutcome(it, replayed = true)
        }

        // We do not call a real payment provider here. Instead, a simple fixed rule decides the result:
        // amounts above the threshold are declined, smaller amounts are approved (auth+capture in one).
        // This keeps the demo predictable; chaos and latency tests add failures at the network level.
        val authorized = amountCents < declineThresholdCents
        val auth = PaymentAuthorization(
            orderId = orderId,
            amountCents = amountCents,
            status = if (authorized) AuthorizationStatus.AUTHORIZED else AuthorizationStatus.DECLINED,
        )
        if (authorized) {
            auth.pspReference = "AUTH-${auth.id}"   // (mock) PSP authorization code
            auth.capturedAt = Instant.now()
        } else {
            auth.declineReason = "AMOUNT_OVER_THRESHOLD"
        }

        val saved = authorizations.save(auth)
        if (authorized) {
            publishAfterCommit(
                "payment-authorized",
                orderId,
                PaymentAuthorizedEvent(orderId = orderId, amountCents = amountCents),
            )
        }
        outcomes(if (authorized) "authorized" else "declined").increment()
        log.info("authorize order={} amountCents={} -> {}", orderId, amountCents, saved.status)
        return AuthorizeOutcome(saved, replayed = false)
    }

    /**
     * Compensating action (Pillar B / audit C2): void a previously AUTHORIZED charge so a rejected order
     * never keeps the customer's money. Idempotent and keyed by orderId — voiding an already-VOIDED record
     * (or a DECLINED one, where nothing was charged) replays the stored outcome.
     */
    @Transactional
    override fun void(orderId: String): AuthorizeOutcome {
        val auth = authorizations.findByOrderId(orderId)
            ?: throw NoSuchElementException("no authorization for order=$orderId")
        if (auth.status == AuthorizationStatus.AUTHORIZED) {
            auth.status = AuthorizationStatus.VOIDED
            auth.voidedAt = Instant.now()
            authorizations.save(auth)
            outcomes("voided").increment()
            log.info("voided authorization for order={}", orderId)
            return AuthorizeOutcome(auth, replayed = false)
        }
        // already VOIDED (idempotent replay) or DECLINED (nothing to void) -> return the stored outcome
        log.info("void no-op for order={} (status={})", orderId, auth.status)
        return AuthorizeOutcome(auth, replayed = true)
    }

    // Looks up the single authorization for an order, or raises a domain not-found error
    @Transactional(readOnly = true)
    override fun find(orderId: String): PaymentAuthorization =
        authorizations.findByOrderId(orderId)
            ?: throw NoSuchElementException("no authorization for order=$orderId")

    /**
     * Publish only AFTER the surrounding transaction commits: publishing inside the transaction could
     * announce an authorization that then rolls back (ghost event).
     */
    private fun publishAfterCommit(topic: String, key: String, payload: Any) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = events.publish(topic, key, payload)
            },
        )
    }
}
