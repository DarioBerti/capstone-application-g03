package it.g03.orders

import io.github.resilience4j.bulkhead.BulkheadRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bulkhead ISOLATION invariant (Fase 2 #2.4 DoD): saturating the Payments bulkhead must NOT consume the permits
 * Inventory needs — that isolation is the whole point of SEPARATE semaphore instances (deck 11 slide 20). Permits are
 * driven directly (semaphore permits are not thread-bound), so the proof is deterministic with no sleeping threads.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
    ],
)
@Tag("chaos-01") // anticipates chaos #1 (payments-latency): a saturated payments bulkhead leaves inventory permits intact
@Import(TestcontainersConfiguration::class)
class BulkheadIsolationTest {

    @Autowired private lateinit var bulkheads: BulkheadRegistry

    private var paymentsAcquired = 0

    @AfterEach
    fun releasePermits() {
        // hand back every permit this test took so a sibling test sharing the cached context starts from full capacity
        repeat(paymentsAcquired) { bulkheads.bulkhead("payments").releasePermission() }
        paymentsAcquired = 0
    }

    @Test
    fun `saturating Payments does not consume Inventory permits`() {
        val payments = bulkheads.bulkhead("payments")
        val inventory = bulkheads.bulkhead("inventory")

        // take ALL 8 payments permits
        repeat(8) {
            assertTrue(payments.tryAcquirePermission(), "expected a free payments permit")
            paymentsAcquired++
        }
        // payments is now saturated: the 9th call is refused (fail-fast, no queue)
        assertFalse(payments.tryAcquirePermission(), "a saturated payments bulkhead must refuse further calls")
        assertEquals(0, payments.metrics.availableConcurrentCalls, "payments has no free permits")

        // ISOLATION: Inventory is untouched — full capacity, and a call still passes
        assertEquals(16, inventory.metrics.availableConcurrentCalls, "a saturated Payments must not steal Inventory permits")
        assertTrue(inventory.tryAcquirePermission(), "Inventory must still admit calls while Payments is saturated")
        inventory.releasePermission()
    }
}
