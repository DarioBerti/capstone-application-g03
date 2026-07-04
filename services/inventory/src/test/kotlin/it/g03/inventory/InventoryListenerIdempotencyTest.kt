package it.g03.inventory

import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.entities.TripSeats
import it.g03.inventory.events.ConfirmSeatsCommand
import it.g03.inventory.events.InventoryCommandListeners
import it.g03.inventory.events.ReservationResultEvent
import it.g03.inventory.events.ReserveSeatsCommand
import it.g03.inventory.events.SeatsConfirmedEvent
import it.g03.inventory.events.Topics
import it.g03.inventory.repository.OutboxEventRepository
import it.g03.inventory.repository.ReservationRepository
import it.g03.inventory.repository.TripSeatsRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Listener-level idempotency: drives the command listeners with serialised command payloads (the deserialise +
 * service + outbox path, minus the broker) and asserts a redelivered command never double-acts and always re-emits
 * its reply. autoStartup is off (no broker); the outbox relay + reconciler are off so neither races the assertions.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "inventory.reservation.enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
class InventoryListenerIdempotencyTest {

    @Autowired lateinit var listeners: InventoryCommandListeners
    @Autowired lateinit var items: TripSeatsRepository
    @Autowired lateinit var reservations: ReservationRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private val trip = "FR9592-20260629"

    private fun available(tripCode: String) = items.findById(tripCode).orElseThrow().available

    private fun reservationStatuses(orderId: String) =
        outbox.findAll().filter { it.topic == Topics.RESERVATION_RESULT && it.aggregateId == orderId }
            .map { objectMapper.readValue(it.payload, ReservationResultEvent::class.java).status }

    private fun seatsConfirmedStatuses(orderId: String) =
        outbox.findAll().filter { it.topic == Topics.SEATS_CONFIRMED && it.aggregateId == orderId }
            .map { objectMapper.readValue(it.payload, SeatsConfirmedEvent::class.java).status }

    private fun reserveSeats(orderId: String, qty: Int) =
        listeners.onReserveSeats(objectMapper.writeValueAsString(ReserveSeatsCommand(orderId = orderId, tripCode = trip, qty = qty)))

    private fun confirmSeats(orderId: String) =
        listeners.onConfirmSeats(objectMapper.writeValueAsString(ConfirmSeatsCommand(orderId = orderId, tripCode = trip)))

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        reservations.deleteAll()
        items.deleteAll()
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 10))
    }

    // The replies carry the tripCode so multi-leg Orders can map each reply to the right booking leg
    @Test
    fun `reservation-result and seats-confirmed replies carry the tripCode`() {
        reserveSeats("order-1", 1)
        confirmSeats("order-1")

        val reserveTrip = outbox.findAll().first { it.topic == Topics.RESERVATION_RESULT }
            .let { objectMapper.readValue(it.payload, ReservationResultEvent::class.java).tripCode }
        val confirmTrip = outbox.findAll().first { it.topic == Topics.SEATS_CONFIRMED }
            .let { objectMapper.readValue(it.payload, SeatsConfirmedEvent::class.java).tripCode }

        assertEquals(trip, reserveTrip)
        assertEquals(trip, confirmTrip)
    }

    // A redelivered reserve-seats command holds the seats exactly once and re-emits the RESERVED reply each time
    @Test
    fun `duplicate reserve-seats command holds the seats once and re-emits the reply`() {
        reserveSeats("order-1", 2)
        reserveSeats("order-1", 2) // at-least-once redelivery

        assertEquals(8, available(trip), "a duplicated reserve-seats must decrement only once")
        assertEquals(1, reservations.findAll().count { it.orderId == "order-1" }, "one reservation row only")
        assertEquals(listOf("RESERVED", "RESERVED"), reservationStatuses("order-1"), "each delivery re-emits the reply")
    }

    // A redelivered confirm-seats command confirms exactly once (no restock) and re-emits the CONFIRMED reply each time
    @Test
    fun `duplicate confirm-seats command confirms once`() {
        reserveSeats("order-1", 1)
        confirmSeats("order-1")
        confirmSeats("order-1") // at-least-once redelivery

        assertEquals(
            ReservationStatus.CONFIRMED,
            reservations.findByOrderIdAndTripCode("order-1", trip)?.status,
        )
        assertEquals(9, available(trip), "confirm must not restock, even when redelivered")
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), seatsConfirmedStatuses("order-1"))
    }

    // A hold whose TTL elapsed before the confirm arrives is expired (restocked) and replies EXPIRED, never oversells
    @Test
    fun `confirm-seats after the hold expired replies EXPIRED, restocks, and never oversells`() {
        // Seed a hold that already lapsed (1 of 10 seats held), as if the payment came back after the TTL
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 9))
        reservations.save(
            Reservation(
                orderId = "order-slow", tripCode = trip, qty = 1,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        confirmSeats("order-slow")

        assertEquals(
            ReservationStatus.EXPIRED,
            reservations.findByOrderIdAndTripCode("order-slow", trip)?.status,
        )
        assertEquals(10, available(trip), "the expired hold is restocked exactly once (no oversell)")
        assertEquals(listOf("EXPIRED"), seatsConfirmedStatuses("order-slow"))
    }
}
