package it.g03.orders.repository

import it.g03.orders.entities.OrderLeg
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// One repository per file (root convention). Legs are loaded by their parent order id (no lazy collection graph).
interface OrderLegRepository : JpaRepository<OrderLeg, UUID> {

    /** All legs of a booking (outbound + optional return): the fan-out/fan-in saga and the order view read this. */
    fun findByOrderId(orderId: UUID): List<OrderLeg>

    /** The single leg for a (order, trip): maps a reservation-result / seats-confirmed reply to its leg. */
    fun findByOrderIdAndTripCode(orderId: UUID, tripCode: String): OrderLeg?
}
