package it.g03.orders.service

import it.g03.orders.entities.Order
import it.g03.orders.events.OrderPlacedEvent
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service // marks this class as a service-layer Spring bean (hosts the business logic)
class OrderServiceImpl(
    private val orders: OrderRepository,
    private val outbox: Outbox,
    private val meterRegistry: MeterRegistry,
    /**
     * Flat demo pricing: looking prices up in Catalog would add a synchronous dependency to the money path that the track's interaction shape does not call for (client -> Catalog is for browsing; Orders -> Inventory/Payments are the decision calls)
     */
    @Value("\${app.unit-price-cents}") private val unitPriceCents: Long,
) : OrderService {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * THE synchronous entry of the money path: persist the order (PENDING) and its order-placed event in ONE transaction (transactional outbox to avoid dual-writes), then return immediately.
     *
     * Idempotent entry: when the caller supplies an `idempotencyKey`, a retried POST replays the existing
     * order (no new order, no new event) instead of creating a duplicate. The UNIQUE(idempotency_key)
     * constraint is the hard backstop for a concurrent same-key race (the loser's insert fails -> 409, and
     * a retry then hits this replay path).
     *
     * Everything else happens asynchronously: the caller gets 202 + orderId and can poll GET /api/orders/{id}.
     */
    @Transactional // order row + order-placed outbox event commit atomically (no dual-write)
    override fun placeOrder(
        tripCode: String,
        qty: Int,
        buyerSub: String,
        buyerEmail: String?,
        idempotencyKey: String?,
    ): Order {
        require(tripCode.isNotBlank()) { "tripCode must not be blank" }
        require(qty > 0) { "qty must be positive" }
        require(buyerSub.isNotBlank()) { "buyerSub must not be blank" }

        // Idempotent replay: a retried POST with the same key returns the existing order untouched.
        if (idempotencyKey != null) {
            orders.findByIdempotencyKey(idempotencyKey)?.let { existing ->
                log.info("idempotent replay for key={} -> order {}", idempotencyKey, existing.id)
                return existing
            }
        }

        val order = orders.save(
            Order(
                tripCode = tripCode,
                qty = qty,
                amountCents = qty * unitPriceCents,
                buyerSub = buyerSub,
                buyerEmail = buyerEmail,
                idempotencyKey = idempotencyKey,
            ),
        )
        val id = order.id.toString()
        outbox.append(
            Topics.ORDER_PLACED, id,
            OrderPlacedEvent(
                orderId = id, tripCode = tripCode, qty = qty, amountCents = order.amountCents,
                buyerSub = buyerSub, buyerEmail = buyerEmail,
            ),
        )
        // Business metric for the checkout SLIs (orders accepted = SLO denominator)
        meterRegistry.counter("orders_placed_total").increment()
        log.info("order {} placed (trip={}, qty={}, buyer={})", id, tripCode, qty, buyerSub)
        return order
    }

    // Read side: fetch an order by id or throw a domain not-found (turned into a 404 at the controller)
    @Transactional(readOnly = true)
    override fun get(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }
}
