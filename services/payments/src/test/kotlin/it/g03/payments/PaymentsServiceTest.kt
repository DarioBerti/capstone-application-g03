package it.g03.payments

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.repository.PaymentAuthorizationRepository
import it.g03.payments.service.PaymentsService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(  // boot the full Spring app context to test service + repository wiring
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        // no broker in tests: swap the Kafka publisher for the no-op one
        "events.publish.enabled=false",
    ],
)
class PaymentsServiceTest {

    // Injects the service under test
    @Autowired lateinit var service: PaymentsService
    // Injects the repository so the test can prepare and clean the database state directly
    @Autowired lateinit var repository: PaymentAuthorizationRepository

    // Thread pool used to fire concurrent duplicate authorizations in the race test
    private val pool = Executors.newFixedThreadPool(8)

    @BeforeEach  // runs before each test method to start from a known database state
    fun cleanSlate() = repository.deleteAll()

    @AfterEach  // runs after each test method to release the thread pool
    fun shutdownPool() {
        pool.shutdownNow()
    }

    // Verifies an amount below the threshold is persisted as AUTHORIZED (one row)
    @Test
    fun `authorize persists AUTHORIZED below the decline threshold`() {
        val outcome = service.authorize("order-1", 5_000)

        assertEquals(AuthorizationStatus.AUTHORIZED, outcome.authorization.status)
        assertEquals(1, repository.count())
    }

    // Verifies the deterministic mock-PSP rule: amounts at or above the threshold are DECLINED
    @Test
    fun `amounts at or above the threshold are DECLINED (mock PSP rule)`() {
        val outcome = service.authorize("order-big", 100_000)

        assertEquals(AuthorizationStatus.DECLINED, outcome.authorization.status)
    }

    // Verifies a retried authorize is an idempotent replay: it must NOT double-charge (no second row)
    @Test
    fun `retried authorize is an idempotent replay - never a second charge`() {
        val first = service.authorize("order-1", 5_000)
        val replay = service.authorize("order-1", 5_000)

        assertTrue(replay.replayed)
        assertEquals(first.authorization.id, replay.authorization.id)
        assertEquals(1, repository.count(), "a retry must NOT create a second authorization")
    }

    // Verifies the replay returns the STORED outcome, even when that outcome was DECLINED
    @Test
    fun `replay returns the stored outcome even for a DECLINED authorization`() {
        service.authorize("order-big", 100_000)

        val replay = service.authorize("order-big", 100_000)

        assertTrue(replay.replayed)
        assertEquals(AuthorizationStatus.DECLINED, replay.authorization.status)
    }

    // Verifies the "must not double-charge" race: concurrent duplicates of the same order commit exactly ONE charge
    @Test
    fun `concurrent duplicates of the SAME order charge exactly once`() {
        // "must not double-charge" race: 8 concurrent deliveries of the same authorize. UNIQUE(order_id) + same-transaction insert guarantee one commit
        val start = CountDownLatch(1)
        val futures = (1..8).map {
            pool.submit(
                Callable {
                    start.await()
                    runCatching { service.authorize("order-dup", 5_000) }
                },
            )
        }
        start.countDown()
        val results = futures.map { it.get() }

        assertTrue(results.any { it.isSuccess }, "at least one delivery must commit")
        assertEquals(1, repository.count(), "exactly one charge per order")
    }

    // Verifies the "error-path": non-positive amounts are rejected with an argument error
    @Test
    fun `non-positive amounts are rejected`() {
        assertFailsWith<IllegalArgumentException> { service.authorize("order-x", 0) }
    }
}
