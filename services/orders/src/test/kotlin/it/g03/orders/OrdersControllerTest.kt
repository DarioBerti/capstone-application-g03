package it.g03.orders

import it.g03.orders.repository.OrderRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * Edge (HTTP) tests for the synchronous order entry: authentication, buyer-identity extraction from the JWT,
 * and client idempotency. Full context + Testcontainers Postgres; the saga/relay are off (no broker), so a
 * placed order simply persists as PENDING — exactly what we assert against.
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
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OrdersControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var orders: OrderRepository

    private val body = """{"tripCode":"FR9592-20260629","qty":1}"""

    @BeforeEach
    fun cleanSlate() {
        orders.deleteAll()
    }

    // Verifies the entry is locked: an unauthenticated POST is rejected with 401, never creating an order.
    @Test
    fun `POST without a token is rejected with 401`() {
        mockMvc.perform(
            post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isUnauthorized)

        assertEquals(0, orders.count().toInt())
    }

    // Verifies the buyer identity is taken from the validated token (not the body) and the order is accepted.
    @Test
    fun `POST records the buyer identity from the token and returns 202`() {
        mockMvc.perform(
            post("/api/orders")
                .with(jwt().jwt { it.subject("kc-sub-1").claim("email", "buyer@example.org") })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isAccepted)

        val order = orders.findAll().single()
        assertEquals("kc-sub-1", order.buyerSub)
        assertEquals("buyer@example.org", order.buyerEmail)
    }

    // Verifies Bean Validation at the edge: a non-positive qty is rejected with 400 and never creates an order.
    @Test
    fun `POST with a non-positive qty is rejected with 400`() {
        mockMvc.perform(
            post("/api/orders")
                .with(jwt().jwt { it.subject("kc-sub-1") })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tripCode":"FR9592-20260629","qty":0}"""),
        ).andExpect(status().isBadRequest)

        assertEquals(0, orders.count().toInt())
    }

    // Verifies Bean Validation at the edge: a blank tripCode is rejected with 400 and never creates an order.
    @Test
    fun `POST with a blank tripCode is rejected with 400`() {
        mockMvc.perform(
            post("/api/orders")
                .with(jwt().jwt { it.subject("kc-sub-1") })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tripCode":"  ","qty":1}"""),
        ).andExpect(status().isBadRequest)

        assertEquals(0, orders.count().toInt())
    }

    // Verifies idempotency at the HTTP edge: a retried POST with the same Idempotency-Key creates ONE order.
    @Test
    fun `a retried POST with the same Idempotency-Key does not create a duplicate order`() {
        repeat(2) {
            mockMvc.perform(
                post("/api/orders")
                    .with(jwt().jwt { it.subject("kc-sub-1").claim("email", "buyer@example.org") })
                    .header("Idempotency-Key", "key-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isAccepted)
        }

        assertEquals(1, orders.count().toInt(), "a retried POST with the same key must not duplicate the order")
        assertEquals("key-1", orders.findAll().single().idempotencyKey)
    }
}
