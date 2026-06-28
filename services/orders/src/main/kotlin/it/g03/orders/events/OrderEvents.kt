package it.g03.orders.events

import java.time.Instant
import java.util.UUID

/** Topics this service produces to / consumes from (created by Strimzi, config repo) */
object Topics {
    const val ORDER_PLACED = "order-placed"               // consumed by our own saga ("listen to yourself")
    const val ORDER_CONFIRMED = "order-confirmed"         // emitted on the terminal happy path
    const val ORDER_REJECTED = "order-rejected"           // emitted on any terminal rejection
    const val NOTIFICATION_REQUESTED = "notification-requested" // tells the Notifications service to message the user
}

/**
 * Event payloads (JSON over Kafka). Each service keeps its OWN copy of the event shapes it touches (no shared library): duplicating a few small data classes is the standard microservice trade-off against coupling every service to a common jar's release cycle.
 * Every event carries an eventId (consumer-side dedup/tracing) and occurredAt
 */

// Emitted when an order is first placed; triggers the saga
data class OrderPlacedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val orderId: String,
    val productId: String,
    val qty: Int,
    val amountCents: Long,
    val occurredAt: Instant = Instant.now(),
)

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
