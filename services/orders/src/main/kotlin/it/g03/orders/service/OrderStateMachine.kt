package it.g03.orders.service

import it.g03.orders.dto.toResponse
import it.g03.orders.entities.LegStatus
import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderLeg
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
import it.g03.orders.events.Topics
import it.g03.orders.events.toPaymentLineItem
import io.micrometer.core.instrument.MeterRegistry
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Reply-driven booking-state transitions + their outbox commands/events (the async half of the saga). Each
 * transition loads the order, guards on its status (and each leg on its own status), transitions, and appends the
 * next command / terminal events to the outbox — all in ONE transaction, so the state change and the events
 * commit (or roll back) together. Separate bean from [OrderSaga] so @Transactional is applied through the proxy.
 *
 * Multi-leg specifics (a round-trip holds two legs under one orderId):
 * - reservation-result / seats-confirmed carry the tripCode, so each reply maps to exactly one [OrderLeg].
 * - after AUTHORIZED, confirm-seats FANS OUT to every RESERVED leg; capture-payment is emitted ONCE, when the
 *   last leg confirms. The fan-in reads the order under a PESSIMISTIC lock so concurrent confirms serialize and
 *   exactly one sees "all legs confirmed" (capture is also idempotent downstream, so this is belt-and-braces).
 * - a leg that EXPIRED at confirm can't be fulfilled: release the other held legs, cancel the authorization
 *   (void, no capture so no refund — capture never fired because not all legs were confirmed), and reject.
 *
 * Idempotency: the per-order and per-leg status guards make a redelivered/out-of-order reply a clean no-op.
 * Commands are idempotent downstream, so the timeout sweeper's re-drive is always safe.
 */
@Component
class OrderStateMachine(
    private val orders: OrderRepository,
    private val legs: OrderLegRepository,
    private val outbox: Outbox,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // reservation-result (per leg): RESERVED -> the leg is held; SOLD_OUT -> the leg drops out (one-way fallback,
    // the order stays HELD). Idempotent on the leg's PENDING guard; the order itself does not transition here.
    @Transactional
    fun onReservationResult(orderId: UUID, tripCode: String, status: String) {
        val leg = legs.findByOrderIdAndTripCode(orderId, tripCode)
        if (leg == null) {
            log.warn("reservation-result for unknown leg order={} trip={} — ignored", orderId, tripCode)
            return
        }
        if (leg.status != LegStatus.PENDING) return
        leg.status = if (status == STATUS_RESERVED) LegStatus.RESERVED else LegStatus.SOLD_OUT
        touchLeg(leg)
        log.info("order {} leg trip={} ({}) -> {}", orderId, tripCode, leg.direction, leg.status)
        publishStatus(load(orderId)) // the leg flip (PENDING -> RESERVED) is what unlocks Pay in the UI
    }

    // payment-result (guard PAYING): AUTHORIZED -> fan out confirm-seats to every RESERVED leg; DECLINED -> reject
    // (which releases the held legs). A redelivered AUTHORIZED is a no-op (status is no longer PAYING).
    @Transactional
    fun onPaymentResult(orderId: UUID, status: String) {
        val order = load(orderId)
        if (order.status != OrderStatus.PAYING) return
        when (status) {
            STATUS_AUTHORIZED -> {
                order.status = OrderStatus.AUTHORIZED
                legs.findByOrderId(orderId).filter { it.status == LegStatus.RESERVED }.forEach {
                    outbox.append(Topics.CONFIRM_SEATS, order.idStr(), ConfirmSeatsCommand(order.idStr(), it.tripCode))
                }
                touch(order)
                publishStatus(order)
                log.info("order {} AUTHORIZED — confirm-seats fanned out", orderId)
            }
            STATUS_DECLINED -> reject(order, RejectionReason.PAYMENT_DECLINED, cancelAuth = false)
            else -> log.warn("order {} unexpected payment-result status '{}' — ignored", orderId, status)
        }
    }

    // seats-confirmed FAN-IN (guard AUTHORIZED). Loads the order FOR UPDATE so concurrent per-leg confirms
    // serialize: the last one reliably sees every leg CONFIRMED and emits capture-payment exactly once. A leg
    // EXPIRED here means the booking can't be fulfilled -> compensate all held legs + cancel the authorization.
    @Transactional
    fun onSeatsConfirmed(orderId: UUID, tripCode: String, status: String) {
        val order = orders.findByIdForUpdate(orderId) ?: throw NoSuchElementException("unknown order: $orderId")
        if (order.status != OrderStatus.AUTHORIZED) return
        val leg = legs.findByOrderIdAndTripCode(orderId, tripCode)
        if (leg == null) {
            log.warn("seats-confirmed for unknown leg order={} trip={} — ignored", orderId, tripCode)
            return
        }
        if (leg.status != LegStatus.RESERVED) return // idempotent: a redelivered confirm on a settled leg
        when (status) {
            STATUS_CONFIRMED -> {
                leg.status = LegStatus.CONFIRMED
                touchLeg(leg)
                if (legs.findByOrderId(orderId).none { it.status == LegStatus.RESERVED }) {
                    outbox.append(Topics.CAPTURE_PAYMENT, order.idStr(), CapturePaymentCommand(order.idStr()))
                    touch(order)
                    log.info("order {} all legs confirmed — capture-payment emitted", orderId)
                }
                publishStatus(order)
            }
            STATUS_EXPIRED -> {
                leg.status = LegStatus.EXPIRED
                touchLeg(leg)
                reject(order, RejectionReason.RESERVATION_EXPIRED, cancelAuth = true)
            }
            else -> log.warn("order {} unexpected seats-confirmed status '{}' — ignored", orderId, status)
        }
    }

    // payment-captured (guard AUTHORIZED): the funds are captured -> terminal happy end.
    @Transactional
    fun onPaymentCaptured(orderId: UUID) {
        val order = load(orderId)
        if (order.status != OrderStatus.AUTHORIZED) return
        order.status = OrderStatus.CONFIRMED
        touch(order)
        // Saga-completion SLI numerator (with orders_rejected_total): terminal ratio vs orders_placed_total
        meterRegistry.counter("orders_confirmed_total").increment()
        val id = order.idStr()
        outbox.append(Topics.ORDER_CONFIRMED, id, OrderConfirmedEvent(orderId = id))
        outbox.append(
            Topics.NOTIFICATION_REQUESTED, id,
            NotificationRequestedEvent(orderId = id, recipient = order.recipient(), message = "Your order $id is confirmed. Enjoy your trip!"),
        )
        publishStatus(order)
        log.info("order {} CONFIRMED", id)
    }

    /**
     * Timeout-sweep re-drive of a STUCK POST-PAY order (a lost reply). PAYING -> re-emit authorize-payment;
     * AUTHORIZED -> re-emit confirm-seats for each still-RESERVED leg, or capture-payment if all legs are already
     * confirmed (a lost payment-captured). Commands are idempotent downstream, so a re-send is always safe.
     */
    @Transactional
    fun redrive(order: Order) {
        val current = load(order.id)
        when (current.status) {
            OrderStatus.PAYING -> {
                val items = legs.findByOrderId(current.id).filter { it.status == LegStatus.RESERVED }.map { it.toPaymentLineItem() }
                outbox.append(Topics.AUTHORIZE_PAYMENT, current.idStr(), AuthorizePaymentCommand(current.idStr(), current.amountCents, items))
            }
            OrderStatus.AUTHORIZED -> {
                val reserved = legs.findByOrderId(current.id).filter { it.status == LegStatus.RESERVED }
                if (reserved.isNotEmpty()) {
                    reserved.forEach { outbox.append(Topics.CONFIRM_SEATS, current.idStr(), ConfirmSeatsCommand(current.idStr(), it.tripCode)) }
                } else {
                    outbox.append(Topics.CAPTURE_PAYMENT, current.idStr(), CapturePaymentCommand(current.idStr()))
                }
            }
            else -> return // DRAFT/HELD (awaiting the user, not "stuck") and terminal states are not re-driven here
        }
        touch(current)
        log.info("order {} re-driven from {} (timeout sweeper)", current.id, current.status)
    }

    /**
     * Checkout-window expiry (CheckoutExpirySweeper): the backend gives up on a booking whose hold window elapsed.
     * The dual cause is `paymentInitiatedAt`: null = the user never paid (EXPIRED_NO_PAYMENT); non-null = pay was
     * accepted but the pipeline stayed stuck (SERVICE_UNAVAILABLE), so we also cancel any authorization (the
     * tombstone makes that safe even if authorize never ran).
     */
    @Transactional
    fun expire(order: Order) {
        val current = load(order.id)
        if (current.status != OrderStatus.HELD && current.status != OrderStatus.PAYING) return
        val expiresAt = current.expiresAt ?: return
        if (expiresAt.isAfter(Instant.now())) return // not actually past the window (defensive)
        if (current.paymentInitiatedAt == null) {
            reject(current, RejectionReason.EXPIRED_NO_PAYMENT, cancelAuth = false)
        } else {
            reject(current, RejectionReason.SERVICE_UNAVAILABLE, cancelAuth = true)
        }
    }

    /**
     * Housekeeping counterpart to the saga transitions (driven by [AbandonedDraftSweeper]): hard-delete DRAFT
     * bookings whose last activity predates [cutoff]. A DRAFT is an abandoned empty cart — it holds no seats
     * (add-leg moves a booking to HELD; a re-emptied draft already released its legs) and no money, so there is
     * nothing to compensate and no terminal event to emit. Legs go first because the order_legs FK has no
     * ON DELETE CASCADE; both tables are outside the Debezium publication, so the deletes emit no CDC. One
     * transaction, so a partial delete can never orphan a leg. Returns the number of orders removed.
     */
    @Transactional
    fun discardAbandonedDrafts(cutoff: Instant): Int {
        legs.deleteByOrderStatusAndUpdatedAtBefore(OrderStatus.DRAFT, cutoff)
        return orders.deleteByStatusAndUpdatedAtBefore(OrderStatus.DRAFT, cutoff)
    }

    // Terminal rejection: give back every still-held leg (release-seats), optionally cancel the payment
    // authorization, then flip the order to REJECTED and emit order-rejected + the notification. Called with the
    // order already loaded inside a guarded transition's transaction.
    private fun reject(order: Order, reason: RejectionReason, cancelAuth: Boolean) {
        if (order.status == OrderStatus.REJECTED) return // idempotent replay
        legs.findByOrderId(order.id)
            .filter { it.status == LegStatus.RESERVED || it.status == LegStatus.CONFIRMED }
            .forEach {
                outbox.append(Topics.RELEASE_SEATS, order.idStr(), ReleaseSeatsCommand(order.idStr(), it.tripCode))
                it.status = LegStatus.RELEASED
                touchLeg(it)
            }
        if (cancelAuth) {
            outbox.append(Topics.CANCEL_AUTHORIZATION, order.idStr(), CancelAuthorizationCommand(order.idStr()))
        }
        order.status = OrderStatus.REJECTED
        order.rejectionReason = reason
        touch(order)
        // Rejection IS saga completion (the user got an answer): counted in the terminal ratio, split by
        // reason for the business panel. `reason` is a 3-value enum — low-cardinality by construction.
        meterRegistry.counter("orders_rejected_total", "reason", reason.name).increment()
        val id = order.idStr()
        outbox.append(Topics.ORDER_REJECTED, id, OrderRejectedEvent(orderId = id, reason = reason.name))
        outbox.append(
            Topics.NOTIFICATION_REQUESTED, id,
            NotificationRequestedEvent(orderId = id, recipient = order.recipient(), message = "Your order $id was rejected: $reason."),
        )
        publishStatus(order)
        log.info("order {} REJECTED ({})", id, reason)
    }

    // Announce the new booking state to the SSE hub (Notifications, which relays it to the browser). Appended in
    // the SAME transaction as the state change via the outbox, so it's atomic with it — and reuses OrderResponse
    // so the SSE payload is byte-identical to GET /api/orders/{id} (zero new frontend shape). Only the async
    // transitions emit here; the sync create/addLeg/removeLeg/pay already return the snapshot in their HTTP reply.
    private fun publishStatus(order: Order) {
        val snapshot = OrderView(order, legs.findByOrderId(order.id)).toResponse()
        outbox.append(Topics.ORDER_STATUS_CHANGED, order.idStr(), snapshot)
    }

    private fun touch(order: Order) {
        order.updatedAt = Instant.now()
        orders.save(order)
    }

    private fun touchLeg(leg: OrderLeg) {
        leg.updatedAt = Instant.now()
        legs.save(leg)
    }

    private fun Order.idStr() = id.toString()

    // Notification recipient: the snapshotted buyer email, or a deterministic fallback if the token had none.
    private fun Order.recipient(): String =
        buyerEmail?.takeIf { it.isNotBlank() } ?: "$buyerSub@no-email.eurotransit.local"

    private fun load(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }

    private companion object {
        const val STATUS_RESERVED = "RESERVED"
        const val STATUS_AUTHORIZED = "AUTHORIZED"
        const val STATUS_DECLINED = "DECLINED"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
