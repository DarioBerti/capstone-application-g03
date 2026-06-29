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
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * END-TO-END proof of the Payments retry through the REAL gateway (Fase 2 #2): a stub Payments that returns 503 twice
 * then 200 -> the retry absorbs the transient faults and the gateway returns AUTHORIZED (the saga never sees the blip).
 * A stub that is permanently 503 -> after the retries are exhausted the gateway maps it to the UNAVAILABLE fallback.
 * Reuses the JDK HttpServer stub pattern of PaymentsGateway4xxTest; the request counter proves the retry fired.
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
@Tag("chaos-01") // anticipates chaos #1 (payments-latency): transient 5xx absorbed end-to-end; permanent 5xx -> UNAVAILABLE
@Import(TestcontainersConfiguration::class)
class PaymentsRetryIntegrationTest {

    companion object {
        private lateinit var server: HttpServer
        private val requests = AtomicInteger(0)

        // how many of the next requests must fault with 503: a positive N = "the first N", -1 = "always".
        @Volatile private var failCount = 0

        @JvmStatic
        @BeforeAll
        fun startStub() {
            server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            server.createContext("/api/payments/authorizations") { exchange ->
                val n = requests.incrementAndGet()
                val fail = failCount < 0 || n <= failCount
                if (fail) {
                    exchange.sendResponseHeaders(503, -1) // Service Unavailable, no body -> HttpServerErrorException
                    exchange.close()
                } else {
                    val body = """{"authorizationId":"00000000-0000-0000-0000-000000000001","orderId":"o","amountCents":1000,"status":"AUTHORIZED","replayed":false}"""
                        .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
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
    }

    @Test
    fun `two transient 503s are absorbed by the retry and the call succeeds`() {
        failCount = 2 // first two requests 503, the third 200

        val result = gateway.authorize("order-1", 1_000)

        assertEquals("AUTHORIZED", result.status, "the retry must absorb the two transient 503s and return the success")
        assertEquals(3, requests.get(), "exactly 3 attempts: 503, 503, 200 (max-attempts=3)")
    }

    @Test
    fun `a permanent 503 is retried then maps to UNAVAILABLE after exhaustion`() {
        failCount = -1 // always 503

        val result = gateway.authorize("order-2", 1_000)

        assertEquals("UNAVAILABLE", result.status, "after the retries are exhausted a 5xx maps to the fallback")
        assertEquals(3, requests.get(), "the retry attempted max-attempts=3 times before giving up")
    }
}
