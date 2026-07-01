package it.g03.orders.service

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import it.g03.orders.entities.RejectionReason
import it.g03.orders.events.AuthorizePaymentCommand
import it.g03.orders.events.CancelAuthorizationCommand
import it.g03.orders.events.CapturePaymentCommand
import it.g03.orders.events.ConfirmSeatsCommand
import it.g03.orders.events.NotificationRequestedEvent
import it.g03.orders.events.OrderConfirmedEvent
import it.g03.orders.events.OrderRejectedEvent
import it.g03.orders.events.ReleaseSeatsCommand
import it.g03.orders.events.ReserveSeatsCommand
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Reply-driven order-state transitions + their outbox commands/events.
 *
 * Why this is a separate bean from [OrderSaga]: Spring applies @Transactional through a proxy, so a method
 * calling another @Transactional method on the same object would bypass the proxy. Each transition loads the
 * order, guards on its current status, transitions, and appends the NEXT command (or the terminal events) to
 * the outbox — all in ONE transaction, so the state change and the event commit (or roll back) together.
 *
 * Every transition is idempotent: the status guard makes a redelivered/out-of-order reply on a wrong or
 * terminal status a clean no-op (no duplicate command rows, no flip of a terminal order). Commands are
 * idempotent downstream (Inventory `UNIQUE(order_id, trip_code)`, Payments `UNIQUE(order_id)`), so a re-emit
 * from the timeout sweeper is always safe.
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

    // Saga trigger: a PENDING order emits reserve-seats and STAYS PENDING ("reserve sent, awaiting reservation-result"). Idempotent: only fires from PENDING.
    @Transactional // transition + the reserve-seats command commit atomically
    fun start(orderId: UUID) {
        val order = load(orderId)
        if (order.status != OrderStatus.PENDING) return
        appendReserveSeats(order)
        touch(order)
        log.info("order {} saga started — reserve-seats emitted", orderId)
    }

    // reservation-result (guard PENDING): RESERVED -> advance + authorize-payment; SOLD_OUT -> reject (no hold to give back).
    @Transactional // transition + the next command/terminal events commit atomically
    fun onReservationResult(orderId: UUID, status: String) {
        val order = load(orderId)
        if (order.status != OrderStatus.PENDING) return
        when (status) {
            "RESERVED" -> {
                order.status = OrderStatus.RESERVED
                outbox.append(Topics.AUTHORIZE_PAYMENT, order.idStr(), AuthorizePaymentCommand(order.idStr(), order.amountCents))
                touch(order)
                log.info("order {} RESERVED — authorize-payment emitted", orderId)
            }
            "SOLD_OUT" -> reject(order, RejectionReason.SOLD_OUT)
            else -> log.warn("order {} unexpected reservation-result status '{}' — ignored", orderId, status)
        }
    }

    // payment-result (guard RESERVED): AUTHORIZED -> advance + confirm-seats; DECLINED -> release-seats (compensation) then reject.
    @Transactional
    fun onPaymentResult(orderId: UUID, status: String) {
        val order = load(orderId)
        if (order.status != OrderStatus.RESERVED) return
        when (status) {
            "AUTHORIZED" -> {
                order.status = OrderStatus.AUTHORIZED
                outbox.append(Topics.CONFIRM_SEATS, order.idStr(), ConfirmSeatsCommand(order.idStr(), order.tripCode))
                touch(order)
                log.info("order {} AUTHORIZED — confirm-seats emitted", orderId)
            }
            "DECLINED" -> {
                outbox.append(Topics.RELEASE_SEATS, order.idStr(), ReleaseSeatsCommand(order.idStr(), order.tripCode))
                reject(order, RejectionReason.PAYMENT_DECLINED)
            }
            else -> log.warn("order {} unexpected payment-result status '{}' — ignored", orderId, status)
        }
    }

    // seats-confirmed (guard AUTHORIZED): CONFIRMED -> capture-payment (stays AUTHORIZED, "capture sent, awaiting payment-captured"); EXPIRED -> cancel-authorization (release the funds hold, no capture so no refund) then reject.
    @Transactional
    fun onSeatsConfirmed(orderId: UUID, status: String) {
        val order = load(orderId)
        if (order.status != OrderStatus.AUTHORIZED) return
        when (status) {
            "CONFIRMED" -> {
                outbox.append(Topics.CAPTURE_PAYMENT, order.idStr(), CapturePaymentCommand(order.idStr()))
                touch(order)
                log.info("order {} seats CONFIRMED — capture-payment emitted", orderId)
            }
            "EXPIRED" -> {
                outbox.append(Topics.CANCEL_AUTHORIZATION, order.idStr(), CancelAuthorizationCommand(order.idStr()))
                reject(order, RejectionReason.RESERVATION_EXPIRED)
            }
            else -> log.warn("order {} unexpected seats-confirmed status '{}' — ignored", orderId, status)
        }
    }

    // payment-captured (guard AUTHORIZED): the funds are captured -> terminal happy end.
    @Transactional
    fun onPaymentCaptured(orderId: UUID) {
        val order = load(orderId)
        if (order.status != OrderStatus.AUTHORIZED) return
        confirm(order)
    }

    /**
     * Timeout-sweep re-drive: a non-terminal order whose reply was lost re-emits the command for its CURRENT
     * state (PENDING -> reserve-seats, RESERVED -> authorize-payment, AUTHORIZED -> confirm-seats). The state is
     * not changed — only the command is re-sent and `updatedAt` bumped (so the sweeper does not re-drive it next
     * poll). Commands are idempotent downstream, so a re-send is always safe; an AUTHORIZED order awaiting capture
     * re-sends confirm-seats, which yields seats-confirmed=CONFIRMED again and re-emits capture-payment — both no-ops.
     */
    @Transactional // re-emit + the updatedAt bump commit atomically; @Version aborts a concurrent reply's transition instead of duplicating
    fun redrive(order: Order) {
        val current = load(order.id)
        when (current.status) {
            OrderStatus.PENDING -> appendReserveSeats(current)
            OrderStatus.RESERVED -> outbox.append(Topics.AUTHORIZE_PAYMENT, current.idStr(), AuthorizePaymentCommand(current.idStr(), current.amountCents))
            OrderStatus.AUTHORIZED -> outbox.append(Topics.CONFIRM_SEATS, current.idStr(), ConfirmSeatsCommand(current.idStr(), current.tripCode))
            OrderStatus.CONFIRMED, OrderStatus.REJECTED -> return // terminal: nothing to re-drive
        }
        touch(current)
        log.info("order {} re-driven from {} (timeout sweeper)", current.id, current.status)
    }

    // Terminal happy end: AUTHORIZED -> CONFIRMED, emits order-confirmed + the confirmation notification. Public + @Transactional so the AUTHORIZED -> CONFIRMED edge (and its @Version concurrency guard) can be driven in isolation.
    @Transactional
    fun confirm(orderId: UUID) = confirm(load(orderId))

    private fun confirm(order: Order) {
        if (order.status != OrderStatus.AUTHORIZED) return // idempotent: only the AUTHORIZED -> CONFIRMED edge fires
        order.status = OrderStatus.CONFIRMED
        touch(order)
        val id = order.idStr()
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

    // Terminal rejection: emits order-rejected + a notification (the user is told why). Called within a guarded transition's transaction with the order already loaded.
    private fun reject(order: Order, reason: RejectionReason) {
        if (order.status == OrderStatus.REJECTED) return // idempotent replay
        order.status = OrderStatus.REJECTED
        order.rejectionReason = reason
        touch(order)
        val id = order.idStr()
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

    private fun appendReserveSeats(order: Order) =
        outbox.append(Topics.RESERVE_SEATS, order.idStr(), ReserveSeatsCommand(order.idStr(), order.tripCode, order.qty))

    // Every transition bumps updatedAt (the timeout sweeper's staleness clock) and persists the change.
    private fun touch(order: Order) {
        order.updatedAt = Instant.now()
        orders.save(order)
    }

    private fun Order.idStr() = id.toString()

    // Notification recipient: the snapshotted buyer email, or a deterministic fallback if the token had none.
    private fun Order.recipient(): String =
        buyerEmail?.takeIf { it.isNotBlank() } ?: "$buyerSub@no-email.eurotransit.local"

    // Loads the order or throws a domain not-found (a reply for an unknown order surfaces to the Kafka error handler -> DLT).
    private fun load(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }
}
