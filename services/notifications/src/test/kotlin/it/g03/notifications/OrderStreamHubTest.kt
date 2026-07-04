package it.g03.notifications

import it.g03.notifications.service.OrderStreamHub
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/**
 * Lifecycle safety of the in-memory emitter registry. The send/complete plumbing is exercised end-to-end by the
 * SSE handshake in the running app; here we pin the registry bookkeeping that must never NPE or leak: relaying
 * to an order nobody is streaming is a no-op, each open() is a distinct stream, and the heartbeat tolerates an
 * empty/at-rest registry. Pure logic — no Spring context.
 */
class OrderStreamHubTest {

    @Test
    fun `push and complete for an order with no open stream are safe no-ops`() {
        val hub = OrderStreamHub()

        hub.push("nobody-is-watching", """{"orderId":"nobody-is-watching","status":"AUTHORIZED"}""")
        hub.complete("nobody-is-watching")
        hub.heartbeat() // empty registry must not throw
    }

    @Test
    fun `open returns a distinct emitter per call`() {
        val hub = OrderStreamHub()

        val a = hub.open("order-1")
        val b = hub.open("order-2")

        assertNotNull(a)
        assertNotSame(a, b, "each open() is its own stream")
    }
}
