package it.g03.orders.service

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.events.NotificationRequestedEvent
import it.g03.orders.events.OrderConfirmedEvent
import it.g03.orders.events.OrderRejectedEvent
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Transactional order-state transitions + their outbox events.
 *
 * Why this is a separate bean from [OrderSaga]: Spring applies @Transactional through a proxy, so a method
 * calling another @Transactional method on the same object would bypass the proxy and run without a
 * transaction. The saga makes remote calls and must not keep a DB transaction open across them, so it calls
 * this bean instead. That way, each transition and its outbox writes commit together atomically.
 *
 * Every transition is idempotent (terminal-state guards): an at-least-once redelivery that re-runs the saga
 * can't append duplicate events or flip a terminal order.
 *
 * The notification recipient is the order's `buyerEmail` (snapshotted from the JWT at placement). If the
 * token carried no email, we fall back to a deterministic per-subject address so the recipient is never
 * blank (Notifications then degrades gracefully rather than logging "to null").
 */
@Component // registers this class as a Spring-managed bean
class OrderStateMachine(
    private val orders: OrderRepository,
    private val outbox: Outbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // PENDING -> RESERVED transition (idempotent: only fires from PENDING)
    @Transactional // transition + any outbox writes commit atomically in one transaction
    fun markReserved(orderId: UUID) {
        val order = load(orderId)
        if (order.status == OrderStatus.PENDING) {
            order.status = OrderStatus.RESERVED
            order.updatedAt = Instant.now()
            orders.save(order)
        }
    }

    // Terminal happy end: emits order-confirmed + the confirmation notification
    @Transactional // transition + both outbox events commit atomically in one transaction
    fun confirm(orderId: UUID) {
        val order = load(orderId)
        if (order.status == OrderStatus.CONFIRMED) return // idempotent replay
        order.status = OrderStatus.CONFIRMED
        order.updatedAt = Instant.now()
        orders.save(order)
        val id = orderId.toString()
        outbox.append(Topics.ORDER_CONFIRMED, id, OrderConfirmedEvent(orderId = id))
        outbox.append(
            Topics.NOTIFICATION_REQUESTED, id,
            NotificationRequestedEvent(
                orderId = id, recipient = order.recipient(),
                message = "Your order $id is confirmed. Enjoy your trip!",
            ),
        )
        log.info("order {} CONFIRMED", id)
    }

    // Terminal rejection: emits order-rejected + a notification (the user is told why)
    @Transactional // transition + both outbox events commit atomically in one transaction
    fun reject(orderId: UUID, reason: RejectionReason) {
        val order = load(orderId)
        if (order.status == OrderStatus.REJECTED) return // idempotent replay
        order.status = OrderStatus.REJECTED
        order.rejectionReason = reason
        order.updatedAt = Instant.now()
        orders.save(order)
        val id = orderId.toString()
        outbox.append(Topics.ORDER_REJECTED, id, OrderRejectedEvent(orderId = id, reason = reason.name))
        outbox.append(
            Topics.NOTIFICATION_REQUESTED, id,
            NotificationRequestedEvent(
                orderId = id, recipient = order.recipient(),
                message = "Your order $id was rejected: $reason.",
            ),
        )
        log.info("order {} REJECTED ({})", id, reason)
    }

    // Notification recipient: the snapshotted buyer email, or a deterministic fallback if the token had none.
    private fun Order.recipient(): String =
        buyerEmail?.takeIf { it.isNotBlank() } ?: "$buyerSub@no-email.eurotransit.local"

    // Loads the order or throws a domain not-found (turned into a 404 at the controller edge)
    private fun load(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }
}
