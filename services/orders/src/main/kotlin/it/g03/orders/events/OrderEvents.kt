package it.g03.orders.events

import java.time.Instant
import java.util.UUID

/** Topics this service produces to / consumes from (created by Strimzi, config repo) */
object Topics {
    const val ORDER_CONFIRMED = "order-confirmed"         // emitted on the terminal happy path
    const val ORDER_REJECTED = "order-rejected"           // emitted on any terminal rejection
    const val NOTIFICATION_REQUESTED = "notification-requested" // tells the Notifications service to message the user
    const val ORDER_STATUS_CHANGED = "order-status-changed" // per-transition booking snapshot -> Notifications relays it to the browser over SSE

    // Commands Orders PRODUCES (key = orderId) -> Inventory/Payments, appended to the outbox in the same tx as the state change
    const val RESERVE_SEATS = "reserve-seats"             // -> Inventory: place a seat hold
    const val AUTHORIZE_PAYMENT = "authorize-payment"     // -> Payments: hold funds (auth only, no capture)
    const val CONFIRM_SEATS = "confirm-seats"             // -> Inventory: commit the hold
    const val CAPTURE_PAYMENT = "capture-payment"         // -> Payments: capture the held funds (last step)
    const val RELEASE_SEATS = "release-seats"             // -> Inventory: compensation on a DECLINED payment
    const val CANCEL_AUTHORIZATION = "cancel-authorization" // -> Payments: compensation on an EXPIRED hold (release funds, no refund)

    // Replies Orders CONSUMES from Inventory/Payments -> drive the saga edges
    const val RESERVATION_RESULT = "reservation-result"   // <- Inventory: RESERVED | SOLD_OUT
    const val PAYMENT_RESULT = "payment-result"           // <- Payments: AUTHORIZED | DECLINED
    const val SEATS_CONFIRMED = "seats-confirmed"         // <- Inventory: CONFIRMED | EXPIRED
    const val PAYMENT_CAPTURED = "payment-captured"       // <- Payments: funds captured
}

/**
 * Event payloads (JSON over Kafka). Each service keeps its OWN copy of the event shapes it touches (no shared library): duplicating a few small data classes is the standard microservice trade-off against coupling every service to a common jar's release cycle.
 * Every event carries an eventId (consumer-side dedup/tracing) and occurredAt.
 */

// Emitted on the terminal happy path (order reached CONFIRMED)
data class OrderConfirmedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val occurredAt: Instant = Instant.now(),
)

// Emitted on any terminal rejection (order reached REJECTED) + carries the reason for observability/notification
data class OrderRejectedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val reason: String,
    val occurredAt: Instant = Instant.now(),
)

// Asks the Notifications service to message the user (the informational tail of the saga, not load-bearing)
data class NotificationRequestedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val recipient: String,
    val message: String,
    val occurredAt: Instant = Instant.now(),
)
