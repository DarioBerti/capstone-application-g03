package it.g03.orders.events

import java.time.Instant
import java.util.UUID

/**
 * Saga command + reply payloads (JSON over Kafka). Orders keeps its OWN copy of these shapes (no shared library):
 * duplicating a few small data classes is the standard microservice trade-off against coupling every service to a
 * common jar's release cycle. Every payload carries an eventId (consumer-side dedup/tracing) and occurredAt.
 */

// --- Commands Orders PRODUCES (key = orderId), appended to the outbox in the same tx as the state change ---

// -> reserve-seats: place a seat hold for this order
data class ReserveSeatsCommand(
    val orderId: String,
    val tripCode: String,
    val qty: Int,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// -> authorize-payment: hold the funds (authorization only; the capture happens last, on payment-captured's prereq).
// Carries the booking legs so the PSP can record the products (PayPal purchase_units.items). Items sum to amountCents.
data class AuthorizePaymentCommand(
    val orderId: String,
    val amountCents: Long,
    val items: List<PaymentLineItem> = emptyList(),
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// One line item of an authorize-payment (a booking leg): what the buyer is purchasing.
data class PaymentLineItem(val name: String, val quantity: Int, val unitAmountCents: Long)

// Renders a booking leg as an authorize-payment line item (e.g. "OUTBOUND FR9592-20260629"). Keep the name
// stable/deterministic — it's display metadata the PSP records, not an identifier.
fun it.g03.orders.entities.OrderLeg.toPaymentLineItem() =
    PaymentLineItem(name = "$direction $tripCode", quantity = qty, unitAmountCents = unitPriceCents)

// -> confirm-seats: commit the hold into a firm sale
data class ConfirmSeatsCommand(
    val orderId: String,
    val tripCode: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// -> capture-payment: capture the previously held funds (the terminal money step)
data class CapturePaymentCommand(
    val orderId: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// -> release-seats: compensation when the payment is DECLINED (give the held seats back)
data class ReleaseSeatsCommand(
    val orderId: String,
    val tripCode: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// -> cancel-authorization: compensation when the hold EXPIRED before confirm (release the funds hold; no capture, so no refund)
data class CancelAuthorizationCommand(
    val orderId: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// --- Replies Orders CONSUMES from Inventory/Payments (status strings mirror the siblings' enums, not a shared type) ---

// <- reservation-result: status = "RESERVED" | "SOLD_OUT". tripCode identifies which booking leg this reply is
// for (a round-trip order holds two reservations under one orderId); defaulted "" for backward-compat with any
// pre-migration reply that predates the field (only single-leg orders can exist then -> the sole leg).
data class ReservationResultEvent(
    val orderId: String,
    val status: String,
    val tripCode: String = "",
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// <- payment-result: status = "AUTHORIZED" | "DECLINED"
data class PaymentResultEvent(
    val orderId: String,
    val status: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// <- seats-confirmed: status = "CONFIRMED" | "EXPIRED". tripCode identifies which booking leg (see above).
data class SeatsConfirmedEvent(
    val orderId: String,
    val status: String,
    val tripCode: String = "",
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)

// <- payment-captured: the held funds were captured (no status branch — captured is the only outcome)
data class PaymentCapturedEvent(
    val orderId: String,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)
