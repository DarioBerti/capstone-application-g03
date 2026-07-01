package it.g03.orders

import it.g03.orders.entities.OutboxEvent
import it.g03.orders.events.EventPublisher
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OutboxPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Outbox relay semantics: at-least-once with ordering preserved. The publisher under test is constructed
 * manually (ticker disabled) with a recording/failing fake, against the real repository (Testcontainers Postgres).
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.timeout-sweeper.enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
@Tag("chaos-04") // anticipates chaos #4 (kafka-partition): the outbox re-drains in order on heal, nothing lost/duplicated
class OutboxPublisherTest {

    class RecordingPublisher : EventPublisher {
        val published = mutableListOf<Pair<String, String>>() // topic to key
        var failOnKey: String? = null
        override fun publish(topic: String, key: String, jsonPayload: String) {
            if (key == failOnKey) throw IllegalStateException("simulated broker failure")
            published += topic to key
        }
    }

    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var txManager: PlatformTransactionManager

    private val recorder = RecordingPublisher()
    private lateinit var publisher: OutboxPublisher

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        recorder.published.clear()
        recorder.failOnKey = null
        publisher = OutboxPublisher(outbox, recorder, txManager, Duration.ofHours(1), enabled = false)
    }

    @Test
    fun `drainOnce publishes unpublished events in creation order and marks them`() {
        outbox.save(OutboxEvent(topic = "order-placed", aggregateId = "o1", payload = "{}"))
        outbox.save(OutboxEvent(topic = "order-confirmed", aggregateId = "o1", payload = "{}"))

        val published = publisher.drainOnce()

        assertEquals(2, published)
        assertEquals(listOf("order-placed" to "o1", "order-confirmed" to "o1"), recorder.published)
        outbox.findAll().forEach { assertNotNull(it.publishedAt, "published events must be stamped") }
    }

    @Test
    fun `already-published events are never re-sent`() {
        outbox.save(OutboxEvent(topic = "order-placed", aggregateId = "o1", payload = "{}"))
        publisher.drainOnce()
        recorder.published.clear()

        assertEquals(0, publisher.drainOnce())
        assertEquals(0, recorder.published.size)
    }

    @Test
    fun `a failed send stops the batch (ordering preserved) and is retried next drain`() {
        outbox.save(OutboxEvent(topic = "order-placed", aggregateId = "fail-me", payload = "{}"))
        outbox.save(OutboxEvent(topic = "order-confirmed", aggregateId = "fail-me", payload = "{}"))
        recorder.failOnKey = "fail-me"

        assertEquals(0, publisher.drainOnce(), "nothing published while the broker fails")
        outbox.findAll().forEach { assertNull(it.publishedAt, "nothing may be marked on failure") }

        // "broker recovers": the next drain delivers everything, still in order
        recorder.failOnKey = null
        assertEquals(2, publisher.drainOnce())
        assertEquals(listOf("order-placed" to "fail-me", "order-confirmed" to "fail-me"), recorder.published)
    }

    /**
     * The multi-replica guard: two relays draining the SAME outbox concurrently must never publish a row twice
     * (this is the `2x order-placed` bug). Relay A locks the whole batch (FOR UPDATE SKIP LOCKED) and blocks mid-
     * publish with its transaction still open; relay B then drains and MUST skip every locked row — publishing
     * nothing, and crucially NOT blocking. If the lock/skip were absent, B would re-publish the batch and the
     * duplicate assertions below would fail.
     */
    @Test
    fun `concurrent drains never publish an event twice (FOR UPDATE SKIP LOCKED)`() {
        // four unpublished events for one order, distinct payloads so any duplicate publish is detectable
        repeat(4) { i -> outbox.save(OutboxEvent(topic = "order-placed", aggregateId = "o1", payload = """{"n":$i}""")) }

        val aHoldsLocks = CountDownLatch(1)
        val bMayFinish = CountDownLatch(1)
        val aPayloads = CopyOnWriteArrayList<String>()
        val bPayloads = CopyOnWriteArrayList<String>()

        // Relay A locks the whole batch in its SELECT, then blocks mid-publish (transaction open, rows still
        // locked) until B has drained — forcing B to observe every row as locked.
        val relayA = object : EventPublisher {
            override fun publish(topic: String, key: String, jsonPayload: String) {
                aPayloads += jsonPayload
                if (aPayloads.size == 1) {
                    aHoldsLocks.countDown()
                    check(bMayFinish.await(5, TimeUnit.SECONDS)) { "B did not finish while A held the locks" }
                }
            }
        }
        val relayB = object : EventPublisher {
            override fun publish(topic: String, key: String, jsonPayload: String) { bPayloads += jsonPayload }
        }
        val publisherA = OutboxPublisher(outbox, relayA, txManager, Duration.ofHours(1), enabled = false)
        val publisherB = OutboxPublisher(outbox, relayB, txManager, Duration.ofHours(1), enabled = false)

        val pool = Executors.newSingleThreadExecutor()
        val aResult = pool.submit(Callable { publisherA.drainOnce() })
        check(aHoldsLocks.await(5, TimeUnit.SECONDS)) { "A never reached the publish step" }

        // A holds row locks on all four rows; B must SKIP them: publish nothing and not block.
        val bCount = publisherB.drainOnce()
        bMayFinish.countDown()
        val aCount = aResult.get(5, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(0, bCount, "B saw the batch locked -> it publishes nothing (skip-locked, never double-publishes)")
        assertEquals(4, aCount, "A owns and publishes the whole batch")
        val all = aPayloads + bPayloads
        assertEquals(4, all.size, "exactly four publishes across both relays — no event sent twice")
        assertEquals(4, all.toSet().size, "every publish is a distinct event")
        outbox.findAll().forEach { assertNotNull(it.publishedAt, "every row ends up marked published") }
    }
}
