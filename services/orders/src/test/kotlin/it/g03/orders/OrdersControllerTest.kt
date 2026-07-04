package it.g03.orders

import it.g03.orders.entities.OrderStatus
import it.g03.orders.repository.OrderLegRepository
import it.g03.orders.repository.OrderRepository
import it.g03.orders.service.OrderService
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
 * Edge (HTTP) tests for the booking API: authentication, buyer-identity extraction from the JWT, request
 * validation, and the 409 state-conflict mappings. Full context + Testcontainers Postgres; the saga/relay are
 * off (no broker). Drafts used as fixtures are created through the service so the ownership check (jwt.sub ==
 * order.buyerSub) lines up with the test token.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.timeout-sweeper.enabled=false",
        "app.checkout.expiry-sweeper.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OrdersControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var legs: OrderLegRepository

    private val sub = "kc-sub-1"

    @BeforeEach
    fun cleanSlate() {
        legs.deleteAll()
        orders.deleteAll()
    }

    private fun draftId() = orderService.createDraft(sub, "buyer@example.org", null).order.id

    // Verifies the entry is locked: an unauthenticated POST is rejected with 401, never creating a booking.
    @Test
    fun `POST without a token is rejected with 401`() {
        mockMvc.perform(post("/api/orders")).andExpect(status().isUnauthorized)

        assertEquals(0, orders.count().toInt())
    }

    // Verifies the buyer identity is taken from the validated token and a DRAFT is created (201).
    @Test
    fun `POST creates a DRAFT and records the buyer identity from the token`() {
        mockMvc.perform(
            post("/api/orders").with(jwt().jwt { it.subject(sub).claim("email", "buyer@example.org") }),
        ).andExpect(status().isCreated)

        val order = orders.findAll().single()
        assertEquals(sub, order.buyerSub)
        assertEquals("buyer@example.org", order.buyerEmail)
        assertEquals(OrderStatus.DRAFT, order.status)
    }

    // Verifies Bean Validation at the edge: a non-positive qty is rejected with 400.
    @Test
    fun `adding a leg with a non-positive qty is rejected with 400`() {
        val id = draftId()
        mockMvc.perform(
            post("/api/orders/$id/legs")
                .with(jwt().jwt { it.subject(sub) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"direction":"OUTBOUND","tripCode":"FR9592-20260629","qty":0}"""),
        ).andExpect(status().isBadRequest)
    }

    // Verifies Bean Validation at the edge: a blank tripCode is rejected with 400.
    @Test
    fun `adding a leg with a blank tripCode is rejected with 400`() {
        val id = draftId()
        mockMvc.perform(
            post("/api/orders/$id/legs")
                .with(jwt().jwt { it.subject(sub) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"direction":"OUTBOUND","tripCode":"  ","qty":1}"""),
        ).andExpect(status().isBadRequest)
    }

    // Verifies Bean Validation at the edge: a missing direction is rejected with 400.
    @Test
    fun `adding a leg with a missing direction is rejected with 400`() {
        val id = draftId()
        mockMvc.perform(
            post("/api/orders/$id/legs")
                .with(jwt().jwt { it.subject(sub) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tripCode":"FR9592-20260629","qty":1}"""),
        ).andExpect(status().isBadRequest)
    }

    // Verifies paying an empty DRAFT (nothing reserved) is a 409 state conflict, not a 5xx.
    @Test
    fun `paying an empty draft is rejected with 409`() {
        val id = draftId()
        mockMvc.perform(
            post("/api/orders/$id/pay").with(jwt().jwt { it.subject(sub) }),
        ).andExpect(status().isConflict)
    }

    // Verifies idempotency at the HTTP edge: a retried create with the same Idempotency-Key yields ONE booking.
    @Test
    fun `a retried POST with the same Idempotency-Key does not create a duplicate order`() {
        repeat(2) {
            mockMvc.perform(
                post("/api/orders")
                    .with(jwt().jwt { it.subject(sub).claim("email", "buyer@example.org") })
                    .header("Idempotency-Key", "key-1"),
            ).andExpect(status().isCreated)
        }

        assertEquals(1, orders.count().toInt(), "a retried create with the same key must not duplicate the order")
        assertEquals("key-1", orders.findAll().single().idempotencyKey)
    }
}
