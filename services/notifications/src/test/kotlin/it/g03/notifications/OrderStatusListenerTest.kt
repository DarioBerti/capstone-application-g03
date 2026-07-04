package it.g03.notifications

import it.g03.notifications.events.OrderStatusListener
import it.g03.notifications.service.OrderStreamHub
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Routing logic of the SSE relay: the listener must push the FULL snapshot verbatim to the matching order,
 * close the stream only on a terminal status, and swallow a malformed payload (never throw out of the
 * listener — same graceful-degradation contract as the mail sink). Uses the real Spring ObjectMapper (Kotlin +
 * time modules, as in production) with a recording fake hub — no Kafka/HTTP needed.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "app.kafka-listeners-enabled=false",
    ],
)
class OrderStatusListenerTest {

    @Autowired lateinit var objectMapper: ObjectMapper

    // plugin.spring opens @Component classes + their methods, so we can record what the listener would push.
    private class RecordingHub : OrderStreamHub() {
        val pushed = mutableListOf<Pair<String, String>>()
        val completed = mutableListOf<String>()
        override fun push(orderId: String, json: String) { pushed += orderId to json }
        override fun complete(orderId: String) { completed += orderId }
    }

    private fun snapshot(orderId: String, status: String) =
        """{"orderId":"$orderId","status":"$status","currency":"EUR","amountCents":1000,"legs":[]}"""

    @Test
    fun `relays the full snapshot to the matching order without closing a non-terminal stream`() {
        val hub = RecordingHub()
        val listener = OrderStatusListener(objectMapper, hub)
        val json = snapshot("order-1", "AUTHORIZED")

        listener.onOrderStatusChanged(json)

        assertEquals(listOf("order-1" to json), hub.pushed, "the snapshot is relayed verbatim, keyed by orderId")
        assertTrue(hub.completed.isEmpty(), "a non-terminal status must not close the stream")
    }

    @Test
    fun `closes the stream on a terminal snapshot (CONFIRMED or REJECTED)`() {
        val hub = RecordingHub()
        val listener = OrderStatusListener(objectMapper, hub)

        listener.onOrderStatusChanged(snapshot("order-2", "CONFIRMED"))
        listener.onOrderStatusChanged(snapshot("order-3", "REJECTED"))

        assertEquals(listOf("order-2", "order-3"), hub.completed, "both terminal states complete their stream")
    }

    @Test
    fun `skips a malformed payload without throwing`() {
        val hub = RecordingHub()
        val listener = OrderStatusListener(objectMapper, hub)

        listener.onOrderStatusChanged("{ this is not valid json")

        assertTrue(hub.pushed.isEmpty() && hub.completed.isEmpty(), "an unparseable payload relays nothing")
    }
}
