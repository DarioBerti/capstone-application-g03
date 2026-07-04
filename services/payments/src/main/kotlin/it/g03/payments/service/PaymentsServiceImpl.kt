package it.g03.payments.service

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.entities.PaymentAuthorization
import it.g03.payments.events.PaymentCapturedEvent
import it.g03.payments.events.PaymentResultEvent
import it.g03.payments.events.Topics
import it.g03.payments.gateway.AuthorizationResult
import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.PaymentGateway
import it.g03.payments.repository.PaymentAuthorizationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service   // marks this class as a Spring service bean (business logic layer)
class PaymentsServiceImpl(
    private val authorizations: PaymentAuthorizationRepository,
    private val outbox: Outbox,
    private val meterRegistry: MeterRegistry,
    private val gateway: PaymentGateway,
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
    override fun authorize(orderId: String, amountCents: Long, items: List<LineItem>): AuthorizeOutcome {
        require(amountCents > 0) { "amountCents must be positive" }

        authorizations.findByOrderId(orderId)?.let {
            // A VOIDED row is a cancellation tombstone (or a genuinely voided hold): the order was cancelled/expired,
            // so refuse to charge and reply DECLINED. This is what makes a cancel-authorization win the race against a
            // stale authorize-payment on the separate topic — see cancelAuthorization's tombstone insert below.
            if (it.status == AuthorizationStatus.VOIDED) {
                outcomes("refused_voided").increment()
                appendPaymentResult(orderId, "DECLINED")
                log.info("authorize refused for order={} -> already VOIDED (cancellation tombstone)", orderId)
                return AuthorizeOutcome(it, replayed = true)
            }
            outcomes("replayed").increment()
            appendPaymentResult(orderId, resultStatus(it))
            log.info("authorize replay for order={} -> {}", orderId, it.status)
            return AuthorizeOutcome(it, replayed = true)
        }

        // Delegate the PSP decision to the configured gateway (PayPal sandbox, or the deterministic mock in
        // dev/tests). This places a HOLD only (no capture) — capture is a separate command. A DECLINE is a
        // business outcome; a transient transport failure throws (TransientPspException) so this transaction
        // rolls back and the Kafka retry/DLT/sweeper path re-drives.
        val auth = PaymentAuthorization(orderId = orderId, amountCents = amountCents)
        when (val result = gateway.authorize(orderId, amountCents, auth.currency, items)) {
            is AuthorizationResult.Authorized -> {
                auth.status = AuthorizationStatus.AUTHORIZED
                auth.pspReference = result.pspReference
            }
            is AuthorizationResult.Declined -> {
                auth.status = AuthorizationStatus.DECLINED
                auth.declineReason = result.reason
            }
        }

        val saved = authorizations.save(auth)
        appendPaymentResult(orderId, resultStatus(saved))
        val approved = saved.status != AuthorizationStatus.DECLINED
        outcomes(if (approved) "authorized" else "declined").increment()
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
                val pspRef = requireNotNull(auth.pspReference) {
                    "AUTHORIZED hold for order=$orderId has no pspReference to capture"
                }
                val result = gateway.capture(orderId, pspRef)
                auth.status = AuthorizationStatus.CAPTURED
                auth.capturedAt = Instant.now()
                auth.captureReference = result.captureReference
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
     * orderId: a missing row inserts a VOIDED tombstone (see [insertVoidTombstone]) so a later stale
     * authorize-payment refuses to charge; an already-VOIDED row replays; a DECLINED row is a no-op (nothing was
     * held); a CAPTURED row is a defensive no-op (the funds were already taken — a release here would need a
     * refund, out of scope, and the saga never reaches it).
     */
    @Transactional
    override fun cancelAuthorization(orderId: String): CancelOutcome {
        val auth = authorizations.findByOrderId(orderId) ?: return insertVoidTombstone(orderId)
        return when (auth.status) {
            AuthorizationStatus.AUTHORIZED -> {
                val pspRef = requireNotNull(auth.pspReference) {
                    "AUTHORIZED hold for order=$orderId has no pspReference to void"
                }
                gateway.void(orderId, pspRef)
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

    // No authorization row yet, but the order is being cancelled/expired: insert a VOIDED tombstone so a later,
    // stale authorize-payment (separate topic, no ordering) finds it and refuses to charge. UNIQUE(order_id) is the
    // arbiter — if an authorize is inserting concurrently, exactly one insert commits and the loser rolls back and
    // is re-driven (converging to VOIDED either way; no dangling hold). No reply: Orders is already rejecting.
    private fun insertVoidTombstone(orderId: String): CancelOutcome {
        val tombstone = authorizations.save(
            PaymentAuthorization(
                orderId = orderId,
                amountCents = 0,
                status = AuthorizationStatus.VOIDED,
                voidedAt = Instant.now(),
            ),
        )
        cancelOutcomes("tombstoned").increment()
        log.info("cancelAuthorization order={} -> no prior hold, inserted VOIDED tombstone", orderId)
        return CancelOutcome(tombstone, replayed = false)
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
