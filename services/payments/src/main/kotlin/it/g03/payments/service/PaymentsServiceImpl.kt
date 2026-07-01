package it.g03.payments.service

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.entities.PaymentAuthorization
import it.g03.payments.events.PaymentCapturedEvent
import it.g03.payments.events.PaymentResultEvent
import it.g03.payments.events.Topics
import it.g03.payments.repository.PaymentAuthorizationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service   // marks this class as a Spring service bean (business logic layer)
class PaymentsServiceImpl(
    private val authorizations: PaymentAuthorizationRepository,
    private val outbox: Outbox,
    private val meterRegistry: MeterRegistry,
    /**
     * (mock) PSP decision rule, deliberately deterministic and demo-controllable: amounts >= this threshold are DECLINED ("insufficient funds"); lets us exercise the saga compensation path (release reservation) on demand: order a huge qty → decline
     */
    @Value("\${payments.decline-threshold-cents:100000}") private val declineThresholdCents: Long,   // injects configured threshold (with a default debug value here) from the env/config
) : PaymentsService {
    private val log = LoggerFactory.getLogger(javaClass)

    // Helper to increment the per-outcome counter (authorized/declined/replayed) for observability
    private fun outcomes(outcome: String) =
        meterRegistry.counter("payments_authorizations_total", "outcome", outcome)

    // Capture (second phase) gets its OWN metric name: outcome = captured | replayed | noop
    private fun captureOutcomes(outcome: String) =
        meterRegistry.counter("payments_captures_total", "outcome", outcome)

    // Cancel (hold release) gets its OWN metric name so the authorize RED series stays clean
    // (one-name-per-concern): outcome = cancelled | replayed | noop_declined | noop_captured
    private fun cancelOutcomes(outcome: String) =
        meterRegistry.counter("payments_cancellations_total", "outcome", outcome)

    // payment-result reply status: any non-DECLINED outcome means the authorization was approved.
    private fun resultStatus(auth: PaymentAuthorization): String =
        if (auth.status == AuthorizationStatus.DECLINED) "DECLINED" else "AUTHORIZED"

    /**
     * Phase 1 (HOLD). STRICT idempotency ("must not double-charge", Pillar B) -> same pattern as Inventory.reserve:
     * - fast path: an authorization for this orderId exists -> return the STORED outcome, charge nothing (a retried call is a no-op);
     * - race path: two duplicates miss the pre-check concurrently -> both would charge, but UNIQUE(order_id) lets exactly ONE insert commit; the loser's transaction (charge + its outbox reply included) rolls back, and its retry replays the stored outcome.
     *
     * The payment-result reply is appended to the outbox IN THIS TRANSACTION on EVERY path (first-time AND replay):
     * re-emitting on redelivery is the at-least-once recovery mechanism — Orders' handler is idempotent.
     */
    @Transactional   // run the whole method in a single database transaction (all-or-nothing)
    override fun authorize(orderId: String, amountCents: Long): AuthorizeOutcome {
        require(amountCents > 0) { "amountCents must be positive" }

        authorizations.findByOrderId(orderId)?.let {
            outcomes("replayed").increment()
            appendPaymentResult(orderId, resultStatus(it))
            log.info("authorize replay for order={} -> {}", orderId, it.status)
            return AuthorizeOutcome(it, replayed = true)
        }

        // We do not call a real payment provider here. Instead, a simple fixed rule decides the result:
        // amounts above the threshold are declined, smaller amounts are approved. This places a HOLD only
        // (no capture) — capture is a separate command. The demo stays predictable; chaos/latency tests add
        // failures at the network level.
        val authorized = amountCents < declineThresholdCents
        val auth = PaymentAuthorization(
            orderId = orderId,
            amountCents = amountCents,
            status = if (authorized) AuthorizationStatus.AUTHORIZED else AuthorizationStatus.DECLINED,
        )
        if (authorized) {
            auth.pspReference = "AUTH-${auth.id}"   // (mock) PSP authorization code for the hold
        } else {
            auth.declineReason = "AMOUNT_OVER_THRESHOLD"
        }

        val saved = authorizations.save(auth)
        appendPaymentResult(orderId, resultStatus(saved))
        outcomes(if (authorized) "authorized" else "declined").increment()
        log.info("authorize order={} amountCents={} -> {} (hold)", orderId, amountCents, saved.status)
        return AuthorizeOutcome(saved, replayed = false)
    }

    /**
     * Phase 2 (CAPTURE). Takes the held funds: AUTHORIZED -> CAPTURED, stamping captured_at. Idempotent, keyed by
     * orderId: a missing row is a domain not-found (the saga only captures after an AUTHORIZED reply); an already-
     * CAPTURED row replays; a DECLINED/VOIDED row is a defensive no-op (nothing to capture). The payment-captured
     * reply is appended to the outbox in this transaction on the transition AND on the CAPTURED replay.
     */
    @Transactional
    override fun capture(orderId: String): CaptureOutcome {
        val auth = authorizations.findByOrderId(orderId)
            ?: throw NoSuchElementException("no authorization for order=$orderId")
        return when (auth.status) {
            AuthorizationStatus.AUTHORIZED -> {
                auth.status = AuthorizationStatus.CAPTURED
                auth.capturedAt = Instant.now()
                val captured = authorizations.save(auth)
                appendPaymentCaptured(orderId)
                captureOutcomes("captured").increment()
                log.info("capture order={} -> AUTHORIZED captured to CAPTURED", orderId)
                CaptureOutcome(captured, replayed = false)
            }
            AuthorizationStatus.CAPTURED -> {
                appendPaymentCaptured(orderId)
                captureOutcomes("replayed").increment()
                log.info("capture replay for order={} -> already CAPTURED", orderId)
                CaptureOutcome(auth, replayed = true)
            }
            AuthorizationStatus.DECLINED, AuthorizationStatus.VOIDED -> {
                captureOutcomes("noop").increment()
                log.info("capture order={} -> no-op (status {}, nothing to capture)", orderId, auth.status)
                CaptureOutcome(auth, replayed = true)
            }
        }
    }

    /**
     * Compensation: release a previously placed HOLD — AUTHORIZED -> VOIDED, stamping voided_at. No money moved
     * (the hold was never captured), no reply (Orders is already rejecting the order). Idempotent, keyed by
     * orderId: a missing row is a domain not-found (the saga only cancels after an AUTHORIZED reply); an already-
     * VOIDED row replays; a DECLINED row is a no-op (nothing was held); a CAPTURED row is a defensive no-op (the
     * funds were already taken — a release here would need a refund, out of scope, and the saga never reaches it).
     */
    @Transactional
    override fun cancelAuthorization(orderId: String): CancelOutcome {
        val auth = authorizations.findByOrderId(orderId)
            ?: throw NoSuchElementException("no authorization for order=$orderId")
        return when (auth.status) {
            AuthorizationStatus.AUTHORIZED -> {
                auth.status = AuthorizationStatus.VOIDED
                auth.voidedAt = Instant.now()
                val voided = authorizations.save(auth)
                cancelOutcomes("cancelled").increment()
                log.info("cancelAuthorization order={} -> AUTHORIZED hold released to VOIDED", orderId)
                CancelOutcome(voided, replayed = false)
            }
            AuthorizationStatus.VOIDED -> {
                cancelOutcomes("replayed").increment()
                log.info("cancelAuthorization replay for order={} -> already VOIDED", orderId)
                CancelOutcome(auth, replayed = true)
            }
            AuthorizationStatus.DECLINED -> {
                cancelOutcomes("noop_declined").increment()
                log.info("cancelAuthorization order={} -> no-op (authorization was DECLINED, no hold)", orderId)
                CancelOutcome(auth, replayed = true)
            }
            AuthorizationStatus.CAPTURED -> {
                cancelOutcomes("noop_captured").increment()
                log.warn("cancelAuthorization order={} -> no-op (already CAPTURED, funds taken)", orderId)
                CancelOutcome(auth, replayed = true)
            }
        }
    }

    // Looks up the single authorization for an order, or raises a domain not-found error
    @Transactional(readOnly = true)
    override fun find(orderId: String): PaymentAuthorization =
        authorizations.findByOrderId(orderId)
            ?: throw NoSuchElementException("no authorization for order=$orderId")

    // Appends the load-bearing reply to the outbox IN the caller's transaction (atomic with the business change).
    private fun appendPaymentResult(orderId: String, status: String) =
        outbox.append(Topics.PAYMENT_RESULT, orderId, PaymentResultEvent(orderId = orderId, status = status))

    private fun appendPaymentCaptured(orderId: String) =
        outbox.append(Topics.PAYMENT_CAPTURED, orderId, PaymentCapturedEvent(orderId = orderId))
}
