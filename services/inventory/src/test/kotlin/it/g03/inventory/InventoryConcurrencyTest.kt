package it.g03.inventory

import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.entities.TripSeats
import it.g03.inventory.repository.ReservationRepository
import it.g03.inventory.repository.TripSeatsRepository
import it.g03.inventory.service.InventoryService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "two customers must not buy the last seat" test (Pillar B, chaos #2 precondition).
 * N concurrent transactions race for 1 remaining seat: the atomic conditional UPDATE must let EXACTLY ONE win,
 * regardless of interleaving. This is the in-process proof of the invariant; the chaos experiment later
 * reproduces it under pod kill in the cluster. Runs on Testcontainers PostgreSQL — the race semantics that
 * matter only hold on a real database.
 */
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=localhost:9092", "events.publish.enabled=false", "app.kafka-listeners-enabled=false", "app.outbox.enabled=false", "inventory.reservation.enabled=false"])  // boot the full Spring app context; reconciler sweep + outbox relay OFF and listeners off so nothing races the seat-count assertions (#5.5)
@Import(TestcontainersConfiguration::class)
@Tag("chaos-02") // anticipates chaos #2 (inventory-podkill): concurrent/redelivered ops never oversell, restock once
class InventoryConcurrencyTest {

    // Injects the service under test
    @Autowired lateinit var service: InventoryService
    // Injects the trip-seats repository so the test can prepare and inspect seat counts directly
    @Autowired lateinit var items: TripSeatsRepository
    // Injects the reservation repository so the test can prepare and clean the database state directly
    @Autowired lateinit var reservations: ReservationRepository

    // Thread pool that lets several reserve calls truly run at the same time
    private val pool = Executors.newFixedThreadPool(8)

    @BeforeEach  // runs before each test method to start from a known database state
    fun cleanSlate() {
        reservations.deleteAll()
        items.deleteAll()
    }

    // Tears down the thread pool after each test so threads don't leak between runs
    @AfterEach
    fun shutdownPool() {
        pool.shutdownNow()
    }

    // Verifies the "two customers must not buy the last seat" invariant: with 8 threads racing for 1 seat, EXACTLY ONE wins and availability never goes negative
    @Test
    fun `only one of 8 concurrent customers gets the last seat`() {
        items.save(TripSeats(tripCode = "FR9999-20260629", totalSeats = 1, available = 1))
        val start = CountDownLatch(1)

        val futures = (1..8).map { i ->
            pool.submit(
                Callable {
                    start.await()
                    // each call runs in its own transaction (service proxy entry point)
                    runCatching { service.reserve("order-$i", "FR9999-20260629", 1) }
                },
            )
        }
        start.countDown()
        val results = futures.map { it.get() }

        val outcomes = results.mapNotNull { it.getOrNull() }
        val winners = outcomes.count { it.reservation.status == ReservationStatus.RESERVED }
        val rejected = outcomes.count { it.reservation.status == ReservationStatus.REJECTED }

        assertEquals(1, winners, "exactly one customer may win the last seat")
        assertEquals(outcomes.size - 1, rejected, "everyone else must be told sold-out")
        assertEquals(0, items.findById("FR9999-20260629").orElseThrow().available)
        assertTrue(
            items.findById("FR9999-20260629").orElseThrow().available >= 0,
            "never oversell: availability can never go negative",
        )
    }

    // Verifies that 8 concurrent duplicate deliveries of the SAME order decrement seats exactly once (idempotent under concurrency)
    @Test
    fun `concurrent duplicates of the SAME order decrement exactly once`() {
        items.save(TripSeats(tripCode = "FR9592-20260629", totalSeats = 5, available = 5))
        val start = CountDownLatch(1)

        // 8 concurrent deliveries of the same logical operation (orderId, tripCode): the unique constraint + same-transaction decrement guarantee a single commit; losers throw (constraint) and at-least-once redelivery would replay them
        val futures = (1..8).map {
            pool.submit(
                Callable {
                    start.await()
                    runCatching { service.reserve("order-dup", "FR9592-20260629", 1) }
                },
            )
        }
        start.countDown()
        val results = futures.map { it.get() }

        val committed = results.mapNotNull { it.getOrNull() }
        assertTrue(committed.isNotEmpty(), "at least one delivery must commit")
        assertEquals(4, items.findById("FR9592-20260629").orElseThrow().available, "one decrement only")
        assertEquals(1, reservations.findAll().count { it.orderId == "order-dup" })
    }

    // Helper: persist an already-expired RESERVED hold of `qty` seats on `tripCode` whose item shows `available` free
    private fun seedExpiredHold(tripCode: String, total: Int, available: Int, qty: Int): java.util.UUID {
        items.save(TripSeats(tripCode = tripCode, totalSeats = total, available = available))
        val past = Instant.now().minusSeconds(120)
        return reservations.save(
            Reservation(orderId = "o-$tripCode", tripCode = tripCode, qty = qty, status = ReservationStatus.RESERVED, createdAt = past, expiresAt = past),
        ).id
    }

    // Verifies MAJOR-1 (oversell guard): N concurrent expiry sweeps on the SAME overdue hold restock it EXACTLY ONCE.
    // The restock is an additive UPDATE; without the @Version on Reservation each racing sweep would restock again
    // (phantom availability -> oversell). With @Version only one sweep commits; the losers fail the optimistic lock
    // (rollback BEFORE their restock) and self-heal next pass.
    @Test
    fun `concurrent expiry sweeps restock an overdue hold exactly once`() {
        val id = seedExpiredHold("HOLD-A", total = 10, available = 9, qty = 1) // 1 seat currently held
        val start = CountDownLatch(1)

        val futures = (1..8).map {
            pool.submit(
                Callable {
                    start.await()
                    runCatching { service.expireOverdue(Instant.now()) }
                },
            )
        }
        start.countDown()
        futures.forEach { it.get() }

        assertEquals(10, items.findById("HOLD-A").orElseThrow().available, "the hold is restocked exactly once, never twice (no oversell)")
        assertEquals(ReservationStatus.EXPIRED, reservations.findById(id).orElseThrow().status)
    }

    // Verifies MAJOR-1: a late confirm racing the reconciler sweep on the same overdue hold restocks at most once.
    // Both see RESERVED+expired and would each restock; @Version lets only one terminal transition win.
    @Test
    fun `confirm racing the expiry sweep restocks at most once`() {
        val id = seedExpiredHold("HOLD-B", total = 10, available = 9, qty = 1)
        val start = CountDownLatch(1)

        val confirmF = pool.submit(Callable { start.await(); runCatching { service.confirm("o-HOLD-B", "HOLD-B") } })
        val sweepF = pool.submit(Callable { start.await(); runCatching { service.expireOverdue(Instant.now()) } })
        start.countDown()
        confirmF.get(); sweepF.get()

        assertEquals(10, items.findById("HOLD-B").orElseThrow().available, "at most one restock for a single hold (no oversell)")
        assertEquals(ReservationStatus.EXPIRED, reservations.findById(id).orElseThrow().status, "an expired hold cannot be confirmed")
    }
}
