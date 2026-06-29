package it.g03.notifications.events

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/** Local copy of the notification-requested event shape (each service owns its copies) */

data class NotificationRequested(
    val eventId: UUID,
    val orderId: String,
    val recipient: String,
    val message: String,
    val occurredAt: Instant,
)

/**
 * FULLY ASYNCHRONOUS consumer -> graceful-degradation showcase: this service is not on the critical path BY CONSTRUCTION. If it is down, events simply accumulate in Kafka (consumer offsets don't move) and are delivered when it comes back. Nothing here is allowed to call back into the money path.
 *
 * The "email" sink is a log line + a small in-memory ring buffer exposed over HTTP so the demo can SHOW notifications flowing (in-memory on purpose: this service owns no state worth keeping; losing the buffer on restart is part of its contract).
 *
 * At-least-once strategy (see application.yml): a duplicated delivery means a duplicated
 * e-mail -> acceptable (a LOST confirmation instead is not acceptable)!
 */

@Component   // registers this class as a Spring-managed bean (so the @KafkaListener is wired up)

class NotificationsListener(private val objectMapper: ObjectMapper) {

    // Logger used to make the mock e-mail "send" observable in the service logs
    private val log = LoggerFactory.getLogger(javaClass)

    // most recent notifications, newest first, capped (in-memory ring buffer)
    val recent = ConcurrentLinkedDeque<NotificationRequested>()

    // consumes notification-requested off Kafka: this is the FULLY-ASYNC, graceful-degradation path (NOT the money path) -> if it lags or is down, events simply wait in the topic and checkout is unaffected!
    @KafkaListener(topics = ["notification-requested"], autoStartup = "\${app.kafka-listeners-enabled:true}") // subscribes to the notification-requested topic; autoStartup can be disabled in tests

    fun onNotificationRequested(payload: String) {
        val n = try {
            objectMapper.readValue(payload, NotificationRequested::class.java)
        } catch (e: Exception) {
            // A malformed/poison payload must NOT throw out of the listener: that would fail the commit, stall
            // the partition on redelivery, and contradict "failure stays local / graceful degradation". Log and
            // skip — losing an unparseable notification is acceptable by this service's contract.
            log.warn("skipping unparseable notification-requested payload: {}", e.message)
            return
        }
        // the mock e-mail "send": observable in logs and via GET /api/notifications
        log.info("[MAIL to {}] order {}: {}", n.recipient, n.orderId, n.message)
        recent.addFirst(n)
        while (recent.size > 100) recent.pollLast()
    }
}
