package it.g03.orders

import it.g03.orders.gateway.HttpPaymentsGateway
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fase 2 #5.3 — proves the slide-22 link: "retry is safe ONLY because the operation is idempotent".
 *
 * The dangerous case neither existing test models: the charge COMMITS on the server, then the RESPONSE is LOST, so the
 * orders retry RETRANSMITS onto the (idempotent) Payments endpoint. PaymentsRetryIntegrationTest faults with bodyless
 * 503s BEFORE any side effect (nothing to dedup); PaymentsServiceTest dedups with two DIRECT calls (no retry). Here the
 * REAL HttpPaymentsGateway drives a real retry onto a stub that charges once (putIfAbsent = UNIQUE(order_id)) and we
 * assert exactly ONE charge survives N retransmissions.
 *
 * Falsifiable: replace the stub's putIfAbsent dedup with a plain increment and `charges["order-1"]` becomes 2 -> RED.
 */
@Tag("chaos-02") // anticipates chaos #2 (inventory-podkill / at-least-once redelivery): a retransmitted money op charges once
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.recovery.enabled=false",
    ],
)
class PaymentsRetryIdempotencyIntegrationTest {

    companion object {
        private lateinit var server: HttpServer
        private val requests = AtomicInteger(0)

        // orderId -> number of times the charge actually COMMITTED on the server. The idempotency store (UNIQUE(order_id)).
        private val charges = ConcurrentHashMap<String, Int>()

        // when set, the FIRST (committing) attempt for this orderId returns a bodyless 503: the charge is committed but the
        // response is lost, so the client retransmits onto the now-idempotent endpoint
        @Volatile private var dropResponseAfterCommitFor: String? = null

        private val ORDER_ID = Regex("\"orderId\"\\s*:\\s*\"([^\"]+)\"")

        @JvmStatic
        @BeforeAll
        fun startStub() {
            server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            server.createContext("/api/payments/authorizations") { exchange ->
                requests.incrementAndGet()
                val body = exchange.requestBody.readBytes().decodeToString()
                val orderId = ORDER_ID.find(body)?.groupValues?.get(1) ?: "unknown"

                // commit-once: putIfAbsent returns null only for the FIRST delivery of this orderId (the real charge)
                val firstCommit = charges.putIfAbsent(orderId, 1) == null
                if (firstCommit && orderId == dropResponseAfterCommitFor) {
                    // charge COMMITTED, but the response is LOST -> 503 (HttpServerErrorException) makes the gateway retry
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                } else {
                    // a retransmission finds the existing charge -> idempotent replay, no second charge
                    val resp = """{"authorizationId":"00000000-0000-0000-0000-000000000001","orderId":"$orderId","amountCents":1000,"status":"AUTHORIZED","replayed":${!firstCommit}}"""
                        .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, resp.size.toLong())
                    exchange.responseBody.use { it.write(resp) }
                }
            }
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun stopStub() {
            server.stop(0)
        }

        @JvmStatic
        @DynamicPropertySource
        fun paymentsUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.payments-base-url") { "http://localhost:${server.address.port}" }
        }
    }

    @Autowired private lateinit var gateway: HttpPaymentsGateway
    @Autowired private lateinit var breakers: CircuitBreakerRegistry

    @BeforeEach
    fun reset() {
        breakers.circuitBreaker("payments").reset()
        requests.set(0)
        charges.clear()
        dropResponseAfterCommitFor = null
    }

    @Test
    fun `a retransmitted authorize charges the order exactly once - retry is safe ONLY because idempotent`() {
        dropResponseAfterCommitFor = "order-1" // attempt 1 commits the charge then loses the response

        val result = gateway.authorize("order-1", 1_000)

        assertEquals("AUTHORIZED", result.status, "the retransmission must still surface the successful authorization")
        assertTrue(result.replayed, "the surviving attempt is the idempotent REPLAY, not a fresh charge")
        assertEquals(1, charges["order-1"], "N retransmissions collapse to exactly ONE charge — the dedup is what makes the retry safe")
        assertEquals(2, requests.get(), "attempt 1 (committed, response dropped) + attempt 2 (idempotent replay)")
    }
}
