package it.g03.orders.service

import it.g03.orders.entities.LegDirection
import it.g03.orders.entities.LegStatus
import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderLeg
import it.g03.orders.entities.OrderStatus
import it.g03.orders.events.AuthorizePaymentCommand
import it.g03.orders.events.ReleaseSeatsCommand
import it.g03.orders.events.ReserveSeatsCommand
import it.g03.orders.events.Topics
import it.g03.orders.events.toPaymentLineItem
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The user-driven half of the booking flow (create -> add legs -> pay). Every mutating method persists the
 * business change and its outbound command in ONE transaction (transactional outbox — no dual-write). The
 * reply-driven half (reservation-result / payment-result / seats-confirmed / payment-captured) lives in
 * [OrderStateMachine]; this mirrors the original split (the synchronous entry emits, the saga reacts).
 *
 * A booking is one journey: an OUTBOUND leg (required) + an optional RETURN leg, at most one ACTIVE leg per
 * direction, always distinct trip codes. The hold window is backend-authoritative — stamped on the first leg and
 * re-checked here at pay.
 */
@Service
class OrderServiceImpl(
    private val orders: OrderRepository,
    private val legs: OrderLegRepository,
    private val outbox: Outbox,
    private val meterRegistry: MeterRegistry,
    // Flat demo pricing snapshotted onto each leg at add-time (see root CLAUDE.md: no sync Catalog call on the money path)
    @Value("\${app.unit-price-cents}") private val unitPriceCents: Long,
    // Backend-owned checkout hold window: how long the user has to pay after the first leg is added
    @Value("\${app.checkout.window}") private val checkoutWindow: Duration,
) : OrderService {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun createDraft(buyerSub: String, buyerEmail: String?, idempotencyKey: String?): OrderView {
        require(buyerSub.isNotBlank()) { "buyerSub must not be blank" }

        // Idempotent replay: a retried create with the same key returns the existing booking untouched.
        if (idempotencyKey != null) {
            orders.findByIdempotencyKey(idempotencyKey)?.let {
                log.info("idempotent replay for key={} -> order {}", idempotencyKey, it.id)
                return view(it)
            }
        }

        val order = orders.save(
            Order(buyerSub = buyerSub, buyerEmail = buyerEmail, idempotencyKey = idempotencyKey, status = OrderStatus.DRAFT),
        )
        meterRegistry.counter("orders_created_total").increment()
        log.info("order {} draft created (buyer={})", order.id, buyerSub)
        return view(order)
    }

    @Transactional
    override fun addLeg(orderId: UUID, buyerSub: String, direction: LegDirection, tripCode: String, qty: Int): OrderView {
        require(qty > 0) { "qty must be positive" }
        val order = loadOwned(orderId, buyerSub)
        check(order.status == OrderStatus.DRAFT || order.status == OrderStatus.HELD) {
            "cannot add a leg to a ${order.status} order"
        }
        val current = legs.findByOrderId(orderId)
        check(current.none { it.direction == direction && it.status in ACTIVE_LEG }) {
            "a $direction leg is already on this booking"
        }

        legs.save(
            OrderLeg(
                orderId = orderId, direction = direction, tripCode = tripCode, qty = qty,
                unitPriceCents = unitPriceCents, status = LegStatus.PENDING,
            ),
        )
        // The first leg starts the backend-owned hold window and moves the booking DRAFT -> HELD.
        if (order.status == OrderStatus.DRAFT) {
            order.status = OrderStatus.HELD
            order.expiresAt = Instant.now().plus(checkoutWindow)
        }
        touch(order)
        // reserve-on-add: the seat hold for this leg is requested now (Inventory replies reservation-result later)
        outbox.append(Topics.RESERVE_SEATS, order.id.toString(), ReserveSeatsCommand(order.id.toString(), tripCode, qty))
        log.info("order {} added {} leg trip={} qty={} — reserve-seats emitted", orderId, direction, tripCode, qty)
        return view(order)
    }

    @Transactional
    override fun removeLeg(orderId: UUID, buyerSub: String, legId: UUID): OrderView {
        val order = loadOwned(orderId, buyerSub)
        check(order.status == OrderStatus.HELD) { "cannot remove a leg from a ${order.status} order" }
        val leg = legs.findById(legId).orElseThrow { NoSuchElementException("unknown leg: $legId") }
        require(leg.orderId == orderId) { "leg $legId does not belong to order $orderId" }

        // Only a confirmed-held (RESERVED) leg needs an explicit give-back; a PENDING leg's inventory state is
        // unknown here, so its hold (if any) is reclaimed by the inventory TTL reconciler instead of a racy release.
        if (leg.status == LegStatus.RESERVED) {
            outbox.append(Topics.RELEASE_SEATS, order.id.toString(), ReleaseSeatsCommand(order.id.toString(), leg.tripCode))
        }
        if (leg.status in ACTIVE_LEG) {
            leg.status = LegStatus.RELEASED
            touchLeg(leg)
        }
        // No active leg left -> the booking is empty again: back to DRAFT and clear the window.
        if (legs.findByOrderId(orderId).none { it.status in ACTIVE_LEG }) {
            order.status = OrderStatus.DRAFT
            order.expiresAt = null
        }
        touch(order)
        log.info("order {} removed leg {} ({})", orderId, legId, leg.direction)
        return view(order)
    }

    @Transactional
    override fun pay(orderId: UUID, buyerSub: String): OrderView {
        val order = loadOwned(orderId, buyerSub)
        // Idempotent repeat: once paying/paid, replay the current view instead of re-authorizing.
        when (order.status) {
            OrderStatus.PAYING, OrderStatus.AUTHORIZED, OrderStatus.CONFIRMED -> return view(order)
            OrderStatus.HELD -> Unit
            else -> throw BookingNotPayableException("order $orderId is ${order.status}, not payable")
        }
        // Backend-authoritative window re-check (never trust a client countdown).
        val expiresAt = order.expiresAt ?: throw BookingNotPayableException("order $orderId has no active hold window")
        if (!expiresAt.isAfter(Instant.now())) {
            throw CheckoutWindowExpiredException("checkout window expired for order $orderId")
        }

        val orderLegs = legs.findByOrderId(orderId)
        // Don't pay while a leg's reservation-result is still in flight — we must know each leg's fate first.
        if (orderLegs.any { it.status == LegStatus.PENDING }) {
            throw BookingNotPayableException("a reservation is still settling for order $orderId; retry")
        }
        val reserved = orderLegs.filter { it.status == LegStatus.RESERVED }
        if (reserved.none { it.direction == LegDirection.OUTBOUND }) {
            throw BookingNotPayableException("no reserved outbound leg to pay for on order $orderId")
        }

        val amount = reserved.sumOf { it.qty * it.unitPriceCents }
        order.amountCents = amount
        order.paymentInitiatedAt = Instant.now()
        order.status = OrderStatus.PAYING
        touch(order)
        val items = reserved.map { it.toPaymentLineItem() }
        outbox.append(Topics.AUTHORIZE_PAYMENT, order.id.toString(), AuthorizePaymentCommand(order.id.toString(), amount, items))
        meterRegistry.counter("orders_pay_initiated_total").increment()
        log.info("order {} pay initiated — authorize-payment {} emitted ({} leg(s))", orderId, amount, reserved.size)
        return view(order)
    }

    @Transactional(readOnly = true)
    override fun get(orderId: UUID, buyerSub: String): OrderView = view(loadOwned(orderId, buyerSub))

    @Transactional(readOnly = true)
    override fun listForBuyer(buyerSub: String, pageable: Pageable): Page<OrderView> {
        val page = orders.findByBuyerSub(buyerSub, pageable)
        // Batch-load every page order's legs in one query, then pair them up — avoids an N+1 over the page.
        val legsByOrder = legs.findByOrderIdIn(page.content.map { it.id }).groupBy { it.orderId }
        return page.map { OrderView(it, legsByOrder[it.id] ?: emptyList()) }
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun view(order: Order) = OrderView(order, legs.findByOrderId(order.id))

    private fun load(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }

    // Ownership: a buyer may only mutate their own booking. Don't leak existence -> a foreign order reads as 404.
    private fun loadOwned(orderId: UUID, buyerSub: String): Order {
        val order = load(orderId)
        if (order.buyerSub != buyerSub) throw NoSuchElementException("unknown order: $orderId")
        return order
    }

    private fun touch(order: Order) {
        order.updatedAt = Instant.now()
        orders.save(order)
    }

    private fun touchLeg(leg: OrderLeg) {
        leg.updatedAt = Instant.now()
        legs.save(leg)
    }

    private companion object {
        // A leg the user still intends to buy (blocks re-adding the same direction; counts toward the displayed total).
        val ACTIVE_LEG = setOf(LegStatus.PENDING, LegStatus.RESERVED, LegStatus.CONFIRMED)
    }
}
