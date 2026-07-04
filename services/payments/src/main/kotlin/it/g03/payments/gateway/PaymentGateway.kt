package it.g03.payments.gateway

/**
 * The outbound PSP seam. The money-path service depends on this abstraction, not on any concrete provider:
 * [MockPaymentGateway] keeps the deterministic demo/test rule; [PayPalPaymentGateway] calls the real PayPal
 * sandbox. Selection is config-driven (`payments.provider`).
 *
 * A DECLINE is a business outcome ([AuthorizationResult.Declined]), never an exception. Transport failures ARE
 * exceptions: [TransientPspException] for retryable ones (5xx/timeout/network) so the Kafka retry/DLT/sweeper
 * path re-drives; [PspException] for terminal ones.
 */
interface PaymentGateway {
    fun authorize(orderId: String, amountCents: Long, currency: String, items: List<LineItem> = emptyList()): AuthorizationResult
    fun capture(orderId: String, pspReference: String): CaptureResult
    fun void(orderId: String, pspReference: String)
}

/**
 * A line item the PSP records (WHAT the buyer is purchasing) — a booking leg. Optional: an empty list authorizes
 * the total amount alone (as before). When present, PayPal shows the products and validates that the items sum to
 * the order amount, so the caller must keep them consistent (Orders builds both from the same reserved legs).
 */
data class LineItem(val name: String, val quantity: Int, val unitAmountCents: Long)

sealed interface AuthorizationResult {
    /** The PSP placed a HOLD; [pspReference] is the authorization id to capture/void later. */
    data class Authorized(val pspReference: String) : AuthorizationResult

    /** The PSP refused the charge — a valid business outcome, not a failure. */
    data class Declined(val reason: String) : AuthorizationResult
}

/** The PSP took the held funds; [captureReference] is the capture id (kept for reconciliation). */
data class CaptureResult(val captureReference: String)

/**
 * Retryable transport failure (5xx / timeout / network / circuit open). The caller must NOT treat it as a
 * decline: it propagates so the Kafka error handler retries and the timeout sweeper re-drives the saga.
 */
class TransientPspException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Terminal, non-retryable PSP error (bad request, auth/config error): parked to the DLT, not retried forever. */
class PspException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
