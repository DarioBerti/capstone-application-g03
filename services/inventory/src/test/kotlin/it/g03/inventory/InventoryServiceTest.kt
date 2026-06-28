package it.g03.inventory

import it.g03.inventory.entities.InventoryItem
import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.repository.InventoryItemRepository
import it.g03.inventory.repository.ReservationRepository
import it.g03.inventory.service.InventoryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092", "events.publish.enabled=false"])  // boot the full Spring app context to test service + repository wiring
class InventoryServiceTest {

    // Injects the service under test
    @Autowired lateinit var service: InventoryService
    // Injects the inventory-item repository so the test can prepare and inspect seat counts directly
    @Autowired lateinit var items: InventoryItemRepository
    // Injects the reservation repository so the test can prepare and clean the database state directly
    @Autowired lateinit var reservations: ReservationRepository

    // Small helper: reads current available seat count for a product
    private fun available(productId: String) = items.findById(productId).orElseThrow().available

    @BeforeEach  // runs before each test method to start from a known database state
    fun cleanSlate() {
        reservations.deleteAll()
        items.deleteAll()
        items.save(InventoryItem(productId = "TRENO-TEST", totalSeats = 10, available = 10))
    }

    // Verifies a successful reserve decrements availability and records a RESERVED hold
    @Test
    fun `reserve decrements availability and records RESERVED`() {
        val outcome = service.reserve("order-1", "TRENO-TEST", 2)

        assertEquals(ReservationStatus.RESERVED, outcome.reservation.status)
        assertFalse(outcome.replayed)
        assertEquals(8, available("TRENO-TEST"))
    }

    // Verifies that reserving when sold out yields REJECTED and leaves availability untouched
    @Test
    fun `sold out yields REJECTED and does not change availability`() {
        service.reserve("order-1", "TRENO-TEST", 10)

        val outcome = service.reserve("order-2", "TRENO-TEST", 1)

        assertEquals(ReservationStatus.REJECTED, outcome.reservation.status)
        assertEquals(0, available("TRENO-TEST"))
    }

    // Verifies a duplicate reserve for the same order replays the stored outcome and decrements only once
    @Test
    fun `duplicate reserve is an idempotent replay - one decrement only`() {
        val first = service.reserve("order-1", "TRENO-TEST", 3)
        val replay = service.reserve("order-1", "TRENO-TEST", 3)

        assertTrue(replay.replayed)
        assertEquals(first.reservation.id, replay.reservation.id)
        assertEquals(ReservationStatus.RESERVED, replay.reservation.status)
        assertEquals(7, available("TRENO-TEST"), "a duplicated delivery must not decrement twice")
    }

    // Verifies the recorded outcome for an orderId is immutable: a replay keeps REJECTED even after seats are restocked
    @Test
    fun `replay preserves the original outcome even after restock`() {
        service.reserve("order-1", "TRENO-TEST", 10)
        val rejected = service.reserve("order-2", "TRENO-TEST", 1) // sold out
        service.release("order-1", "TRENO-TEST") // seats come back

        val replay = service.reserve("order-2", "TRENO-TEST", 1)

        // The reservation outcome for an orderId is immutable: a retry-as-new-purchase must use a new orderId (documented idempotency semantics)
        assertTrue(replay.replayed)
        assertEquals(ReservationStatus.REJECTED, rejected.reservation.status)
        assertEquals(ReservationStatus.REJECTED, replay.reservation.status)
    }

    // Verifies confirm moves a hold RESERVED -> CONFIRMED, is idempotent, and does not restock
    @Test
    fun `confirm moves RESERVED to CONFIRMED and is idempotent`() {
        service.reserve("order-1", "TRENO-TEST", 1)

        assertEquals(ReservationStatus.CONFIRMED, service.confirm("order-1", "TRENO-TEST").status)
        assertEquals(ReservationStatus.CONFIRMED, service.confirm("order-1", "TRENO-TEST").status)
        assertEquals(9, available("TRENO-TEST"), "confirm must not restock")
    }

    // Verifies release restocks the held seats exactly once and is idempotent
    @Test
    fun `release restocks and is idempotent`() {
        service.reserve("order-1", "TRENO-TEST", 4)

        assertEquals(ReservationStatus.RELEASED, service.release("order-1", "TRENO-TEST").status)
        assertEquals(ReservationStatus.RELEASED, service.release("order-1", "TRENO-TEST").status)
        assertEquals(10, available("TRENO-TEST"), "released seats come back exactly once")
    }

    // Verifies the slow-payment race: a confirm arriving after the TTL expires the hold and restocks (never a stale confirm)
    @Test
    fun `confirm after TTL expires the hold and restocks (slow-payment race)`() {
        // Simulate a hold whose TTL elapsed before the payment came back: design refinement #3: Orders must get EXPIRED and compensate, never a stale confirm.
        // (state prepared via save: @Modifying queries need a transaction, tests have none)
        items.save(InventoryItem(productId = "TRENO-TEST", totalSeats = 10, available = 9))
        reservations.save(
            Reservation(
                orderId = "order-slow", productId = "TRENO-TEST", qty = 1,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        val result = service.confirm("order-slow", "TRENO-TEST")

        assertEquals(ReservationStatus.EXPIRED, result.status)
        assertEquals(10, available("TRENO-TEST"), "expired hold must restock")
    }

    // Verifies the reconciler sweep expires overdue holds, restocks their seats, and marks them EXPIRED
    @Test
    fun `reconciler sweep expires overdue holds and restocks`() {
        items.save(InventoryItem(productId = "TRENO-TEST", totalSeats = 10, available = 8))
        reservations.save(
            Reservation(
                orderId = "order-stranded", productId = "TRENO-TEST", qty = 2,
                status = ReservationStatus.RESERVED,
                createdAt = Instant.now().minusSeconds(600),
                expiresAt = Instant.now().minusSeconds(300),
            ),
        )

        val swept = service.expireOverdue(Instant.now())

        assertEquals(1, swept)
        assertEquals(10, available("TRENO-TEST"))
        assertEquals(
            ReservationStatus.EXPIRED,
            reservations.findByOrderIdAndProductId("order-stranded", "TRENO-TEST")!!.status,
        )
    }

    // Verifies reserving an unknown product raises a not-found domain error (becomes a 404)
    @Test
    fun `unknown product is a 404-style domain error`() {
        assertFailsWith<NoSuchElementException> { service.reserve("order-1", "NO-SUCH-TRAIN", 1) }
    }
}
