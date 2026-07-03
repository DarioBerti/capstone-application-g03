package it.g03.notifications.service

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-memory registry of open SSE connections, keyed by orderId. Notifications is single-replica by design, so
 * ONE instance holds every browser connection AND consumes every order-status-changed event — there is no
 * cross-pod fan-out to solve (no Redis). This is ephemeral CONNECTION state only (no DB), consistent with the
 * service's stateless contract: losing it on a restart just drops the streams, which the SPA reconnects to (or
 * degrades to polling from) — checkout is never affected.
 */
@Component
class OrderStreamHub {
    private val log = LoggerFactory.getLogger(javaClass)
    private val streams = ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>>()

    /**
     * Opens a stream for one order. The timeout comfortably outlasts the hold window (the browser reconnects if
     * it lapses); completion/timeout/error all deregister so the map never leaks dead emitters.
     */
    fun open(orderId: String): SseEmitter {
        val emitter = SseEmitter(STREAM_TIMEOUT_MS)
        streams.computeIfAbsent(orderId) { CopyOnWriteArraySet() }.add(emitter)
        emitter.onCompletion { remove(orderId, emitter) }
        emitter.onTimeout { remove(orderId, emitter) }
        emitter.onError { remove(orderId, emitter) }
        return emitter
    }

    // Relays a booking snapshot (raw JSON, verbatim from Kafka) to every open stream for that order.
    fun push(orderId: String, json: String) {
        val emitters = streams[orderId] ?: return
        emitters.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON))
            } catch (ex: Exception) {
                remove(orderId, emitter) // a dead/slow client is not ours to retry — drop it
            }
        }
    }

    // Terminal state: the caller has already pushed the final snapshot; complete the streams so the browser stops.
    fun complete(orderId: String) {
        streams.remove(orderId)?.forEach {
            try {
                it.complete()
            } catch (ex: Exception) {
                log.debug("emitter already closed for order {}: {}", orderId, ex.message)
            }
        }
    }

    /**
     * Keep-alive: an SSE comment to every open stream so a proxy (Traefik) idle timeout can't kill a long HELD
     * wait; a failed send prunes the dead emitter. Requires @EnableScheduling (NotificationsApplication).
     */
    @Scheduled(fixedRate = HEARTBEAT_MS)
    fun heartbeat() {
        streams.forEach { (orderId, emitters) ->
            emitters.forEach { emitter ->
                try {
                    emitter.send(SseEmitter.event().comment("ping"))
                } catch (ex: Exception) {
                    remove(orderId, emitter)
                }
            }
        }
    }

    private fun remove(orderId: String, emitter: SseEmitter) {
        streams[orderId]?.let { set ->
            set.remove(emitter)
            if (set.isEmpty()) streams.remove(orderId, set) // drop the bucket only if it is still this empty set
        }
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 20 * 60 * 1000L // > the 15-min hold window, with slack
        const val HEARTBEAT_MS = 15_000L
    }
}
