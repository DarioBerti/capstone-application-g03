package it.g03.inventory.events

/**
 * Kafka topics this service consumes (saga commands from Orders) and produces (replies via the outbox). Each service
 * keeps its OWN copy; the names are the canonical cross-service contract (topics created by Strimzi, config repo).
 */
object Topics {
    // consumed: commands from the Orders saga
    const val RESERVE_SEATS = "reserve-seats"
    const val CONFIRM_SEATS = "confirm-seats"
    const val RELEASE_SEATS = "release-seats"

    // produced: replies back to Orders (key = orderId), emitted via the transactional outbox
    const val RESERVATION_RESULT = "reservation-result"
    const val SEATS_CONFIRMED = "seats-confirmed"

    // dead-letter sink for commands that exhaust the bounded retry
    const val INVENTORY_DLT = "inventory-dlt"
}
