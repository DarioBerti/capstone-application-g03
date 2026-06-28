package it.g03.orders.repository

import it.g03.orders.entities.Order
import it.g03.orders.entities.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the Order entity in this case); the IdType is UUID because the order id is a UUID. Spring Data will generate the implementation at runtime
interface OrderRepository : JpaRepository<Order, UUID> {

    // Recovery sweep ([OrderRecoverySweeper]): the orders parked in PAYMENT_PENDING by the circuit-breaker fallback, retried once Payments is reachable again. Spring Data derives the WHERE clause from the method name.
    fun findByStatus(status: OrderStatus): List<Order>
}
