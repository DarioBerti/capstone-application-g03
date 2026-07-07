package it.g03.inventory

import it.g03.inventory.entities.TripSeats
import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.events.AvailabilityChangedEvent
import it.g03.inventory.events.ReservationResultEvent
import it.g03.inventory.events.SeatsConfirmedEvent
import it.g03.inventory.events.Topics
import it.g03.inventory.repository.OutboxEventRepository
import it.g03.inventory.repository.TripSeatsRepository
import it.g03.inventory.repository.ReservationRepository
import it.g03.inventory.service.InventoryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// reconciler sweep OFF and outbox relay OFF so neither races the assertions; listeners off (no broker) (#5.5)
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "inventory.reservation.enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
class InventoryServiceTest {

    // Injects the service under test
    @Autowired lateinit var service: InventoryService
    // Injects the trip-seats repository so the test can prepare and inspect seat counts directly
    @Autowired lateinit var items: TripSeatsRepository
    // Injects the reservation repository so the test can prepare and clean the database state directly
    @Autowired lateinit var reservations: ReservationRepository
    // Injects the outbox repository so the test can assert the saga replies are appended in-transaction
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private val trip = "FR9592-20260629"

    // Small helper: reads current available seat count for a trip
    private fun available(tripCode: String) = items.findById(tripCode).orElseThrow().available

    // Reply rows appended for an order on a given topic (the outbox key is the orderId)
    private fun replies(topic: String, orderId: String) =
        outbox.findAll().filter { it.topic == topic && it.aggregateId == orderId }

    private fun reservationStatusOf(orderId: String) =
        replies(Topics.RESERVATION_RESULT, orderId).map { objectMapper.readValue(it.payload, ReservationResultEvent::class.java).status }

    private fun seatsConfirmedStatusOf(orderId: String) =
        replies(Topics.SEATS_CONFIRMED, orderId).map { objectMapper.readValue(it.payload, SeatsConfirmedEvent::class.java).status }

    // availability-changed rows appended for a trip (the outbox key for this topic is the tripCode)
    private fun availabilityEventsOf(tripCode: String) =
        replies(Topics.SEAT_AVAILABILITY, tripCode).map { objectMapper.readValue(it.payload, AvailabilityChangedEvent::class.java) }

    @BeforeEach  // runs before each test method to start from a known database state
    fun cleanSlate() {
        outbox.deleteAll()
        reservations.deleteAll()
        items.deleteAll()
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 10))
    }

    // Verifies a successful reserve decrements availability, records a RESERVED hold, and appends one RESERVED reply
    @Test
    fun `reserve decrements availability, records RESERVED, and appends a reservation-result reply`() {
        val outcome = service.reserve("order-1", trip, 2)

        assertEquals(ReservationStatus.RESERVED, outcome.reservation.status)
        assertFalse(outcome.replayed)
        assertEquals(8, available(trip))
        assertEquals(listOf("RESERVED"), reservationStatusOf("order-1"), "exactly one reservation-result reply, status RESERVED")
    }

    // Verifies that reserving when sold out yields REJECTED, leaves availability untouched, and replies SOLD_OUT
    @Test
    fun `sold out yields REJECTED and replies SOLD_OUT`() {
        service.reserve("order-1", trip, 10)

        val outcome = service.reserve("order-2", trip, 1)

        assertEquals(ReservationStatus.REJECTED, outcome.reservation.status)
        assertEquals(0, available(trip))
        assertEquals(listOf("SOLD_OUT"), reservationStatusOf("order-2"), "sold-out reserve replies SOLD_OUT")
    }

    // Verifies a duplicate reserve for the same order replays the stored outcome and decrements only once
    @Test
    fun `duplicate reserve is an idempotent replay - one decrement only`() {
        val first = service.reserve("order-1", trip, 3)
        val replay = service.reserve("order-1", trip, 3)

        assertTrue(replay.replayed)
        assertEquals(first.reservation.id, replay.reservation.id)
        assertEquals(ReservationStatus.RESERVED, replay.reservation.status)
        assertEquals(7, available(trip), "a duplicated delivery must not decrement twice")
    }

    // Verifies the reply is RE-EMITTED on the idempotent replay path (at-least-once reply recovery for Orders)
    @Test
    fun `reserve replay re-emits the reservation-result reply`() {
        service.reserve("order-1", trip, 3)
        service.reserve("order-1", trip, 3) // duplicate delivery

        assertEquals(listOf("RESERVED", "RESERVED"), reservationStatusOf("order-1"), "each delivery (original + replay) appends one reply")
        assertEquals(7, available(trip), "the replay still must not decrement again")
    }

    // Verifies the recorded outcome for an orderId is immutable: a replay keeps REJECTED even after seats are restocked
    @Test
    fun `replay preserves the original outcome even after restock`() {
        service.reserve("order-1", trip, 10)
        val rejected = service.reserve("order-2", trip, 1) // sold out
        service.release("order-1", trip) // seats come back

        val replay = service.reserve("order-2", trip, 1)

        // The reservation outcome for an orderId is immutable: a retry-as-new-purchase must use a new orderId (documented idempotency semantics)
        assertTrue(replay.replayed)
        assertEquals(ReservationStatus.REJECTED, rejected.reservation.status)
        assertEquals(ReservationStatus.REJECTED, replay.reservation.status)
        assertEquals(listOf("SOLD_OUT", "SOLD_OUT"), reservationStatusOf("order-2"), "the replayed reply keeps SOLD_OUT")
    }

    // Verifies confirm moves a hold RESERVED -> CONFIRMED, is idempotent, replies CONFIRMED on every delivery, and does not restock
    @Test
    fun `confirm moves RESERVED to CONFIRMED, replies CONFIRMED, and is idempotent`() {
        service.reserve("order-1", trip, 1)

        assertEquals(ReservationStatus.CONFIRMED, service.confirm("order-1", trip).status)
        assertEquals(ReservationStatus.CONFIRMED, service.confirm("order-1", trip).status)
        assertEquals(9, available(trip), "confirm must not restock")
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), seatsConfirmedStatusOf("order-1"), "each confirm delivery appends one seats-confirmed reply")
    }

    // Verifies release restocks the held seats exactly once, is idempotent, and appends NO reply (Orders is already rejecting)
    @Test
    fun `release restocks, is idempotent, and appends no reply`() {
        service.reserve("order-1", trip, 4)

        assertEquals(ReservationStatus.RELEASED, service.release("order-1", trip).status)
        assertEquals(ReservationStatus.RELEASED, service.release("order-1", trip).status)
        assertEquals(10, available(trip), "released seats come back exactly once")
        // release restocks (which emits an advisory availability-changed), but never a saga REPLY to Orders
        assertEquals(listOf("RESERVED"), reservationStatusOf("order-1"), "release adds no reservation-result reply")
        assertTrue(seatsConfirmedStatusOf("order-1").isEmpty(), "release never emits seats-confirmed")
    }

    // Verifies the round-trip partial-expiry compensation: releasing an ALREADY-CONFIRMED reservation un-sells it
    // (restocks) exactly once and is idempotent. Orders uses this to give back a leg it had confirmed when a
    // sibling leg of the booking expires.
    @Test
    fun `release of a CONFIRMED reservation restocks the sale and is idempotent`() {
        service.reserve("order-1", trip, 2)
        service.confirm("order-1", trip) // firm sale: 8 left, not restocked
        assertEquals(8, available(trip))

        assertEquals(ReservationStatus.RELEASED, service.release("order-1", trip).status)
        assertEquals(ReservationStatus.RELEASED, service.release("order-1", trip).status) // redelivery
        assertEquals(10, available(trip), "a released CONFIRMED sale restocks exactly once")
    }

    // Verifies the slow-payment race: a confirm arriving after the TTL expires the hold, restocks, and replies EXPIRED (never a stale confirm)
    @Test
    fun `confirm after TTL expires the hold, restocks, and replies EXPIRED (slow-payment race)`() {
        // Simulate a hold whose TTL elapsed before the payment came back: design refinement #3: Orders must get EXPIRED and compensate, never a stale confirm.
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 9))
        reservations.save(
            Reservation(
                orderId = "order-slow", tripCode = trip, qty = 1,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        val result = service.confirm("order-slow", trip)

        assertEquals(ReservationStatus.EXPIRED, result.status)
        assertEquals(10, available(trip), "expired hold must restock")
        assertEquals(listOf("EXPIRED"), seatsConfirmedStatusOf("order-slow"), "a confirm after TTL replies EXPIRED")
    }

    // Verifies the reconciler sweep expires overdue holds, restocks their seats, marks them EXPIRED, and appends NO reply
    @Test
    fun `reconciler sweep expires overdue holds, restocks, and appends no reply`() {
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 8))
        reservations.save(
            Reservation(
                orderId = "order-stranded", tripCode = trip, qty = 2,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        val swept = service.expireOverdue(Instant.now())

        assertEquals(1, swept)
        assertEquals(10, available(trip))
        // the sweep restocks (emitting an advisory availability-changed) but no saga REPLY — no order awaits it
        assertTrue(reservationStatusOf("order-stranded").isEmpty(), "the sweep emits no reservation-result reply")
        assertTrue(seatsConfirmedStatusOf("order-stranded").isEmpty(), "the sweep emits no seats-confirmed reply")
        assertEquals(
            ReservationStatus.EXPIRED,
            requireNotNull(reservations.findByOrderIdAndTripCode("order-stranded", trip)) {
                "expected a swept reservation for order-stranded/$trip"
            }.status,
        )
    }

    // Verifies reserving an unknown trip raises a not-found domain error (becomes a 404 / a DLT-bound poison message)
    @Test
    fun `unknown trip is a 404-style domain error`() {
        assertFailsWith<NoSuchElementException> { service.reserve("order-1", "NO-SUCH-TRIP", 1) }
    }

    // Verifies the public availability projection returns a paginated DTO whose counts reflect live reservations
    @Test
    fun `availability returns live seat counts as a paginated DTO`() {
        items.save(TripSeats(tripCode = "FR1000-20260701", totalSeats = 5, available = 5))
        service.reserve("order-1", trip, 3) // trip: 10 -> 7

        val page = service.availability(PageRequest.of(0, 200))

        val byTrip = page.content.associateBy { it.tripCode }
        assertEquals(2, page.totalElements.toInt())
        assertEquals(7, byTrip.getValue(trip).available, "availability reflects the live decrement")
        assertEquals(10, byTrip.getValue(trip).totalSeats)
        assertEquals(5, byTrip.getValue("FR1000-20260701").available)
    }

    // Verifies a successful reserve emits ONE availability-changed carrying the fresh (post-decrement) count
    @Test
    fun `reserve emits availability-changed with the fresh seat count`() {
        service.reserve("order-1", trip, 3) // 10 -> 7

        val events = availabilityEventsOf(trip)
        assertEquals(1, events.size)
        assertEquals(7, events.single().available)
        assertEquals(10, events.single().totalSeats)
    }

    // Verifies the reserve that takes the last seat emits available=0 so a read-side projection learns sold-out
    @Test
    fun `reserving the last seats emits availability-changed with available zero`() {
        service.reserve("order-1", trip, 10) // 10 -> 0

        assertEquals(listOf(0), availabilityEventsOf(trip).map { it.available })
    }

    // Verifies a sold-out (REJECTED) reserve emits NO availability-changed (the count did not move)
    @Test
    fun `rejected reserve emits no availability-changed`() {
        service.reserve("order-1", trip, 10) // sells out, emits available=0
        service.reserve("order-2", trip, 1) // REJECTED, no change

        assertEquals(listOf(0), availabilityEventsOf(trip).map { it.available }, "only the sell-out reserve emits")
    }

    // Verifies release restocks and emits availability-changed with the restored count
    @Test
    fun `release emits availability-changed with the restocked count`() {
        service.reserve("order-1", trip, 4) // 10 -> 6 (emits 6)
        service.release("order-1", trip) // 6 -> 10 (emits 10)

        assertEquals(listOf(6, 10), availabilityEventsOf(trip).map { it.available })
    }

    // Verifies a plain confirm (no restock) emits NO availability-changed; only the reserve did
    @Test
    fun `plain confirm emits no availability-changed`() {
        service.reserve("order-1", trip, 2) // emits 8
        service.confirm("order-1", trip) // CONFIRMED, count unchanged

        assertEquals(listOf(8), availabilityEventsOf(trip).map { it.available })
    }

    // Verifies a confirm after TTL restocks and emits availability-changed with the restored count
    @Test
    fun `confirm after TTL emits availability-changed with the restocked count`() {
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 9))
        reservations.save(
            Reservation(
                orderId = "order-slow", tripCode = trip, qty = 1,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        service.confirm("order-slow", trip) // EXPIRED + restock: 9 -> 10

        assertEquals(listOf(10), availabilityEventsOf(trip).map { it.available })
    }

    // Verifies the reconciler sweep restocks and emits availability-changed for the swept trip
    @Test
    fun `reconciler sweep emits availability-changed with the restocked count`() {
        items.save(TripSeats(tripCode = trip, totalSeats = 10, available = 8))
        reservations.save(
            Reservation(
                orderId = "order-stranded", tripCode = trip, qty = 2,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        service.expireOverdue(Instant.now()) // 8 -> 10

        assertEquals(listOf(10), availabilityEventsOf(trip).map { it.available })
    }
}
