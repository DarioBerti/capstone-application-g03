package it.g03.orders

import it.g03.orders.gateway.HttpPaymentsGateway
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import kotlin.test.assertEquals

/**
 * Proves the gateway maps a PERMANENT 4xx to a TERMINAL outcome (DECLINED), not the transient UNAVAILABLE fallback —
 * otherwise a deterministic client error would park the order forever in PAYMENT_PENDING. Also pins the breaker's
 * ignore-exceptions binding: a 4xx must NOT open the breaker. A tiny stub server returns 400 on the authorize endpoint
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
class PaymentsGateway4xxTest {

    companion object {
        private lateinit var server: HttpServer

        @JvmStatic
        @BeforeAll
        fun startStub() {
            server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            server.createContext("/api/payments/authorizations") { exchange ->
                exchange.sendResponseHeaders(400, -1) // 400 Bad Request, no body
                exchange.close()
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

    @Autowired
    private lateinit var gateway: HttpPaymentsGateway

    @Autowired
    private lateinit var registry: CircuitBreakerRegistry

    @BeforeEach
    fun reset() {
        registry.circuitBreaker("payments").reset()
    }

    @Test
    fun `a 4xx from Payments maps to terminal DECLINED and does NOT trip the breaker`() {
        // six client errors — well past minimum-number-of-calls — must all be terminal and leave the breaker CLOSED
        repeat(6) {
            val result = gateway.authorize("order-$it", 1_000)
            assertEquals("DECLINED", result.status, "a 4xx is a permanent client error -> terminal DECLINED, not UNAVAILABLE")
        }
        assertEquals(
            CircuitBreaker.State.CLOSED,
            registry.circuitBreaker("payments").state,
            "an ignored 4xx must NOT open the breaker (ignore-exceptions binding)",
        )
    }
}
