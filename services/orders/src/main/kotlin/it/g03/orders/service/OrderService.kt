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
class OrderService(
    private val orders: OrderRepository,
    private val outbox: Outbox,
    private val meterRegistry: MeterRegistry,
    /**
     * Flat demo pricing: looking prices up in Catalog would add a synchronous dependency to the money path that the track's interaction shape does not call for (client -> Catalog is for browsing; Orders -> Inventory/Payments are the decision calls)
     */
    @Value("\${app.unit-price-cents}") private val unitPriceCents: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * THE synchronous entry of the money path: persist the order (PENDING) and its order-placed event in ONE transaction (transactional outbox to don't have dual-writes), then return immediately
     * 
     * Everything else happens asynchronously: the caller gets 202 + orderId and can poll GET /api/orders/{id}
     */
    @Transactional // order row + order-placed outbox event commit atomically (no dual-write)
    fun placeOrder(productId: String, qty: Int): Order {
        require(productId.isNotBlank()) { "productId must not be blank" }
        require(qty > 0) { "qty must be positive" }

        val order = orders.save(
            Order(productId = productId, qty = qty, amountCents = qty * unitPriceCents),
        )
        val id = order.id.toString()
        outbox.append(
            Topics.ORDER_PLACED, id,
            OrderPlacedEvent(orderId = id, productId = productId, qty = qty, amountCents = order.amountCents),
        )
        // Business metric for the checkout SLIs (orders accepted = SLO denominator)
        meterRegistry.counter("orders_placed_total").increment()
        log.info("order {} placed (product={}, qty={})", id, productId, qty)
        return order
    }

    // Read side: fetch an order by id or throw a domain not-found (turned into a 404 at the controller)
    fun get(orderId: UUID): Order =
        orders.findById(orderId).orElseThrow { NoSuchElementException("unknown order: $orderId") }
}
