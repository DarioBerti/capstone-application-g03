package it.g03.payments.events

import java.time.Instant
import java.util.UUID

/**
 * Saga payloads (JSON over Kafka). Each service keeps its OWN copy of the event shapes it touches (no shared
 * library): duplicating a few small data classes is the standard microservice trade-off against coupling every
 * service to a common jar's release cycle. Every payload carries an eventId (consumer-side dedup/tracing) and
 * occurredAt; Jackson ignores unknown fields, so extra metadata from the producer is tolerated.
 */

// --- Commands consumed from Orders --------------------------------------------------------------------------

// authorize-payment: place a funds hold for an order (auth only; capture is a separate command)
data class AuthorizePaymentCommand(
    val orderId: String,
    val amountCents: Long,
    // The booking legs, for the PSP to record the products. Defaulted empty for backward-compat with any
    // pre-line-items producer (authorize then falls back to charging the amount alone).
    val items: List<PaymentLineItem> = emptyList(),
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// One line item of an authorize-payment (a booking leg): what the buyer is purchasing.
data class PaymentLineItem(val name: String, val quantity: Int, val unitAmountCents: Long)

// capture-payment: capture the funds previously held by authorize-payment
data class CapturePaymentCommand(
    val orderId: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// cancel-authorization: release a previously placed hold (compensation; no money moved, no reply)
data class CancelAuthorizationCommand(
    val orderId: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// --- Replies produced back to Orders (via the outbox) -------------------------------------------------------

// payment-result: the AUTHORIZED|DECLINED outcome of an authorize hold. status is a plain String to match the
// codebase convention of strings across service boundaries.
data class PaymentResultEvent(
    val orderId: String,
    val status: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// payment-captured: confirms the held funds were captured
data class PaymentCapturedEvent(
    val orderId: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)
