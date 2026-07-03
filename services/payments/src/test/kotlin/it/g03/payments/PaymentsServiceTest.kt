package it.g03.payments

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.events.PaymentResultEvent
import it.g03.payments.events.Topics
import it.g03.payments.repository.OutboxEventRepository
import it.g03.payments.repository.PaymentAuthorizationRepository
import it.g03.payments.service.PaymentsService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(  // boot the full Spring app context to test service + repository wiring
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        // no broker in tests: swap the Kafka publisher for the no-op one and keep listeners + relay off
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
@Tag("chaos-02") // anticipates chaos #2 (podkill / redelivery): a retried/duplicated authorize charges once; capture/cancel idempotent
class PaymentsServiceTest {

    // Injects the service under test
    @Autowired lateinit var service: PaymentsService
    // Injects the repository so the test can prepare and clean the database state directly
    @Autowired lateinit var repository: PaymentAuthorizationRepository
    // Injects the outbox repository to assert the saga reply was appended in the same transaction
    @Autowired lateinit var outbox: OutboxEventRepository
    // Injects the ObjectMapper to read a reply payload (assert the tombstone guard replies DECLINED)
    @Autowired lateinit var objectMapper: ObjectMapper

    // Thread pool used to fire concurrent duplicate authorizations in the race test
    private val pool = Executors.newFixedThreadPool(8)

    @BeforeEach  // runs before each test method to start from a known database state
    fun cleanSlate() {
        repository.deleteAll()
        outbox.deleteAll()
    }

    @AfterEach  // runs after each test method to release the thread pool
    fun shutdownPool() {
        pool.shutdownNow()
    }

    private fun outboxCount(topic: String) = outbox.findAll().count { it.topic == topic }

    // Deserialises every payment-result reply's status (used to assert the tombstone guard replies DECLINED)
    private fun paymentResultStatuses(): List<String> =
        outbox.findAll().filter { it.topic == Topics.PAYMENT_RESULT }
            .map { objectMapper.readValue(it.payload, PaymentResultEvent::class.java).status }

    // --- authorize: phase 1 is a HOLD ---------------------------------------------------------------------------

    // Verifies an amount below the threshold is a HOLD: AUTHORIZED with a PSP reference but NOT yet captured
    @Test
    fun `authorize below the threshold places a HOLD - AUTHORIZED, pspReference set, not captured`() {
        val outcome = service.authorize("order-1", 5_000)

        assertEquals(AuthorizationStatus.AUTHORIZED, outcome.authorization.status)
        assertEquals(1, repository.count())
        assertNotNull(outcome.authorization.pspReference, "an AUTHORIZED hold carries a PSP reference")
        assertNull(outcome.authorization.capturedAt, "a HOLD is not captured until the capture step")
        assertNull(outcome.authorization.declineReason)
    }

    // Verifies the deterministic mock-PSP rule: amounts at or above the threshold are DECLINED with a reason
    @Test
    fun `amounts at or above the threshold are DECLINED (mock PSP rule)`() {
        val outcome = service.authorize("order-big", 100_000)

        assertEquals(AuthorizationStatus.DECLINED, outcome.authorization.status)
        assertNotNull(outcome.authorization.declineReason, "a DECLINED authorization records why")
        assertNull(outcome.authorization.pspReference, "nothing was authorized -> no PSP reference")
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

    // --- capture: phase 2 takes the held funds ------------------------------------------------------------------

    // Verifies the second phase: capture moves an AUTHORIZED hold to CAPTURED and stamps captured_at
    @Test
    fun `capture moves an AUTHORIZED hold to CAPTURED and stamps captured_at`() {
        val held = service.authorize("order-1", 5_000)

        val captured = service.capture("order-1")

        assertEquals(AuthorizationStatus.CAPTURED, captured.authorization.status)
        assertEquals(held.authorization.id, captured.authorization.id, "capture mutates the existing aggregate")
        assertNotNull(captured.authorization.capturedAt, "a captured charge is stamped")
        assertEquals(1, repository.count(), "capture must NOT create a second row")
    }

    // Verifies idempotency: a retried capture is a no-op replay, the funds are captured exactly once
    @Test
    fun `retried capture is an idempotent replay`() {
        service.authorize("order-1", 5_000)
        val first = service.capture("order-1")
        val replay = service.capture("order-1")

        assertEquals(false, first.replayed)
        assertTrue(replay.replayed, "the second capture must be a no-op replay")
        assertEquals(AuthorizationStatus.CAPTURED, replay.authorization.status)
        assertEquals(1, repository.count())
    }

    // Verifies the defensive not-found path: capturing an order with no authorization is a domain not-found (-> 404)
    @Test
    fun `capture of an unknown order raises not-found`() {
        assertFailsWith<NoSuchElementException> { service.capture("order-unknown") }
    }

    // --- cancelAuthorization: release a hold (compensation) -----------------------------------------------------

    // Verifies the compensation: cancelling an AUTHORIZED hold releases it to VOIDED on the SAME row (no money moved)
    @Test
    fun `cancelAuthorization releases an AUTHORIZED hold to VOIDED`() {
        val held = service.authorize("order-1", 5_000)

        val cancelled = service.cancelAuthorization("order-1")

        assertEquals(AuthorizationStatus.VOIDED, cancelled.authorization.status)
        assertEquals(held.authorization.id, cancelled.authorization.id, "cancel mutates the existing aggregate")
        assertNull(cancelled.authorization.capturedAt, "a released hold was never captured")
        assertEquals(1, repository.count(), "cancel must NOT create a second row")
    }

    // Verifies idempotency: a retried cancel is a no-op replay, the hold is released exactly once
    @Test
    fun `retried cancelAuthorization is an idempotent replay`() {
        service.authorize("order-1", 5_000)
        val first = service.cancelAuthorization("order-1")
        val replay = service.cancelAuthorization("order-1")

        assertEquals(false, first.replayed)
        assertTrue(replay.replayed, "the second cancel must be a no-op replay")
        assertEquals(AuthorizationStatus.VOIDED, replay.authorization.status)
        assertEquals(1, repository.count())
    }

    // Verifies the no-op branch: cancelling a DECLINED authorization changes nothing (nothing was ever held)
    @Test
    fun `cancelAuthorization of a DECLINED authorization is a no-op`() {
        service.authorize("order-big", 100_000)

        val outcome = service.cancelAuthorization("order-big")

        assertTrue(outcome.replayed)
        assertEquals(AuthorizationStatus.DECLINED, outcome.authorization.status, "a declined authorization stays DECLINED")
    }

    // --- cancel tombstone + authorize guard (the cross-topic race prerequisite) --------------------------------

    // Verifies the tombstone: cancelling an order with no prior authorization inserts a VOIDED tombstone (amount 0)
    // so a later stale authorize-payment (separate topic, no ordering) will find it and refuse to charge.
    @Test
    fun `cancelAuthorization of an unknown order inserts a VOIDED tombstone`() {
        val outcome = service.cancelAuthorization("order-ghost")

        assertEquals(false, outcome.replayed)
        assertEquals(AuthorizationStatus.VOIDED, outcome.authorization.status)
        assertEquals(0, outcome.authorization.amountCents, "a tombstone charges nothing")
        assertNotNull(outcome.authorization.voidedAt)
        assertEquals(1, repository.count(), "the tombstone is the single row for this order")
    }

    // Verifies the guard (cancel-before-authorize): an authorize arriving AFTER a tombstone refuses to charge and
    // replies DECLINED — no new row, no PSP charge, the tombstone wins.
    @Test
    fun `authorize after a cancel tombstone refuses and replies DECLINED`() {
        service.cancelAuthorization("order-ghost")   // tombstone first

        val outcome = service.authorize("order-ghost", 5_000)

        assertTrue(outcome.replayed, "authorize must not charge over a tombstone")
        assertEquals(AuthorizationStatus.VOIDED, outcome.authorization.status)
        assertNull(outcome.authorization.pspReference, "nothing was authorized -> no PSP reference")
        assertEquals(1, repository.count(), "no second row is created")
        assertEquals(listOf("DECLINED"), paymentResultStatuses(), "the saga is told the payment will not happen")
    }

    // Verifies the tombstone itself is idempotent: a redelivered cancel over a tombstone is a no-op replay
    @Test
    fun `cancel over an existing tombstone is an idempotent replay`() {
        service.cancelAuthorization("order-ghost")
        val replay = service.cancelAuthorization("order-ghost")

        assertTrue(replay.replayed)
        assertEquals(AuthorizationStatus.VOIDED, replay.authorization.status)
        assertEquals(1, repository.count())
    }

    // Verifies convergence under the cross-topic race: authorize and cancel fired concurrently, then each re-driven
    // once (Kafka at-least-once), always settle on a single VOIDED row — never a dangling AUTHORIZED hold.
    @Test
    fun `concurrent authorize and cancel converge to a single VOIDED row`() {
        val start = CountDownLatch(1)
        val a = pool.submit(Callable { start.await(); runCatching { service.authorize("order-race", 5_000) } })
        val c = pool.submit(Callable { start.await(); runCatching { service.cancelAuthorization("order-race") } })
        start.countDown()
        a.get(); c.get()

        // redelivery of the loser (whichever rolled back on the UNIQUE race) drives convergence
        runCatching { service.cancelAuthorization("order-race") }
        runCatching { service.authorize("order-race", 5_000) }

        assertEquals(1, repository.count(), "exactly one row per order")
        assertEquals(AuthorizationStatus.VOIDED, service.find("order-race").status, "no dangling AUTHORIZED hold")
    }

    // --- outbox replies committed in the same transaction -------------------------------------------------------

    // Verifies authorize appends exactly one payment-result reply to the outbox, AND re-emits on idempotent replay
    @Test
    fun `authorize appends one payment-result reply, including on replay`() {
        service.authorize("order-1", 5_000)
        assertEquals(1, outboxCount(Topics.PAYMENT_RESULT), "first authorize appends exactly one payment-result")

        service.authorize("order-1", 5_000)
        assertEquals(2, outboxCount(Topics.PAYMENT_RESULT), "the replay re-emits the reply (at-least-once recovery)")
    }

    // Verifies a DECLINED authorize still appends a payment-result reply (the saga branches on DECLINED)
    @Test
    fun `a DECLINED authorize appends a payment-result reply`() {
        service.authorize("order-big", 100_000)

        assertEquals(1, outboxCount(Topics.PAYMENT_RESULT))
    }

    // Verifies capture appends exactly one payment-captured reply, AND re-emits on idempotent replay
    @Test
    fun `capture appends one payment-captured reply, including on replay`() {
        service.authorize("order-1", 5_000)

        service.capture("order-1")
        assertEquals(1, outboxCount(Topics.PAYMENT_CAPTURED), "first capture appends exactly one payment-captured")

        service.capture("order-1")
        assertEquals(2, outboxCount(Topics.PAYMENT_CAPTURED), "the replay re-emits the reply (at-least-once recovery)")
    }

    // Verifies cancelAuthorization emits NO reply of its own (Orders is already rejecting the order)
    @Test
    fun `cancelAuthorization appends no reply`() {
        service.authorize("order-1", 5_000)   // the only reply in play is this authorize's payment-result

        service.cancelAuthorization("order-1")

        assertEquals(1, outboxCount(Topics.PAYMENT_RESULT), "cancel adds no payment-result of its own")
        assertEquals(0, outboxCount(Topics.PAYMENT_CAPTURED), "cancel adds no payment-captured")
    }
}
