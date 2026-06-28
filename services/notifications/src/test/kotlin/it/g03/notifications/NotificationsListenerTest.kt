package it.g03.notifications

import it.g03.notifications.events.NotificationsListener
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@SpringBootTest(  // boot the full Spring app context to test the Kafka listener + controller wiring
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "app.kafka-listeners-enabled=false",
    ],
)

class NotificationsListenerTest {

    // Injects the listener under test
    @Autowired lateinit var listener: NotificationsListener

    // Verifies the listener parses a notification-requested payload and records it (pins the notification-requested wire contract produced by Orders' outbox)
    @Test
    fun `parses a notification-requested payload and records it`() {
        // Hand-written JSON: pins the wire contract produced by Orders' outbox
        val json = """
            {"eventId":"7e57d004-2b97-0e7a-b45f-5387367791cd",
             "orderId":"order-1",
             "recipient":"customer@example.org",
             "message":"Your order order-1 is confirmed. Enjoy your trip!",
             "occurredAt":"2026-06-10T12:00:00Z"}
        """.trimIndent()

        listener.onNotificationRequested(json)

        assertEquals(1, listener.recent.size)
        assertEquals("order-1", listener.recent.first().orderId)
    }
}
