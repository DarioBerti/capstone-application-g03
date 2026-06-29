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
import java.time.Duration
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
        "app.recovery.enabled=false",
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

    private val recorder = RecordingPublisher()
    private lateinit var publisher: OutboxPublisher

    @BeforeEach
    fun cleanSlate() {
        outbox.deleteAll()
        recorder.published.clear()
        recorder.failOnKey = null
        publisher = OutboxPublisher(outbox, recorder, Duration.ofHours(1), enabled = false)
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
}
