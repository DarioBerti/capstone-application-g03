package it.g03.orders

import it.g03.orders.entities.Order
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
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

    // Persist a booking directly (bypassing the saga) so a read-side test can pin its status and createdAt —
    // createdAt is a plain settable column, so distinct timestamps make the default sort deterministic.
    private fun persist(status: OrderStatus, createdAt: Instant = Instant.now(), buyer: String = sub): Order =
        orders.save(Order(buyerSub = buyer, buyerEmail = "buyer@example.org", status = status, createdAt = createdAt))

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
                .content("""{"direction":"OUTBOUND","tripCode":"FR9592-20260629","qty":0,"priceToken":"t"}"""),
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
                .content("""{"direction":"OUTBOUND","tripCode":"  ","qty":1,"priceToken":"t"}"""),
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
                .content("""{"tripCode":"FR9592-20260629","qty":1,"priceToken":"t"}"""),
        ).andExpect(status().isBadRequest)
    }

    // Verifies Bean Validation at the edge: a missing price quote is rejected with 400 (the leg can't be priced).
    @Test
    fun `adding a leg without a price quote is rejected with 400`() {
        val id = draftId()
        mockMvc.perform(
            post("/api/orders/$id/legs")
                .with(jwt().jwt { it.subject(sub) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"direction":"OUTBOUND","tripCode":"FR9592-20260629","qty":1}"""),
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

    // Buyer-scoping (IDOR guard): a buyer cannot read another buyer's order — it reads as 404, not leaking existence.
    @Test
    fun `GET another buyer's order is rejected with 404`() {
        val foreignId = orderService.createDraft("other-buyer", "other@example.org", null).order.id
        mockMvc.perform(get("/api/orders/$foreignId").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isNotFound)
    }

    // The owner can read their own booking.
    @Test
    fun `GET own order returns it`() {
        val id = draftId()
        mockMvc.perform(get("/api/orders/$id").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId").value(id.toString()))
    }

    // My-bookings is buyer-scoped: it returns only the caller's own bookings, never another buyer's.
    @Test
    fun `GET my orders returns only the caller's bookings`() {
        draftId()
        draftId()
        orderService.createDraft("other-buyer", "other@example.org", null)
        mockMvc.perform(get("/api/orders").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    // My-bookings requires a valid token.
    @Test
    fun `GET my orders without a token is rejected with 401`() {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized)
    }

    // The optional status filter narrows my-bookings to the requested status only.
    @Test
    fun `GET my orders filters by status`() {
        persist(OrderStatus.CONFIRMED)
        persist(OrderStatus.HELD)
        persist(OrderStatus.REJECTED)

        mockMvc.perform(get("/api/orders?status=CONFIRMED").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
    }

    // A CSV of statuses is an OR over the set (?status=HELD,PAYING returns bookings in either state).
    @Test
    fun `GET my orders accepts a CSV of statuses`() {
        persist(OrderStatus.HELD)
        persist(OrderStatus.PAYING)
        persist(OrderStatus.CONFIRMED)

        mockMvc.perform(get("/api/orders?status=HELD,PAYING").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    // An unknown status value can't bind to OrderStatus → 400 with the service's typed ErrorResponse body
    // (a populated `error`), not Spring's default empty error response.
    @Test
    fun `GET my orders with an invalid status is rejected with a 400 typed error`() {
        mockMvc.perform(get("/api/orders?status=NOT_A_STATUS").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }

    // With no explicit sort the default is createdAt DESC (newest booking first).
    @Test
    fun `GET my orders returns newest first by default`() {
        val older = persist(OrderStatus.CONFIRMED, Instant.parse("2026-06-01T10:00:00Z"))
        val newer = persist(OrderStatus.CONFIRMED, Instant.parse("2026-06-02T10:00:00Z"))

        mockMvc.perform(get("/api/orders").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].orderId").value(newer.id.toString()))
            .andExpect(jsonPath("$.content[1].orderId").value(older.id.toString()))
    }

    // createdFrom/createdTo resolve to inclusive UTC day bounds: a same-day range keeps only that day's bookings.
    @Test
    fun `GET my orders filters by created date range`() {
        persist(OrderStatus.CONFIRMED, Instant.parse("2026-06-01T12:00:00Z"))
        val inRange = persist(OrderStatus.CONFIRMED, Instant.parse("2026-06-02T12:00:00Z"))
        persist(OrderStatus.CONFIRMED, Instant.parse("2026-06-03T12:00:00Z"))

        mockMvc.perform(
            get("/api/orders?createdFrom=2026-06-02&createdTo=2026-06-02").with(jwt().jwt { it.subject(sub) }),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].orderId").value(inRange.id.toString()))
    }

    // The response exposes the booking's createdAt.
    @Test
    fun `GET my orders exposes createdAt in the response`() {
        persist(OrderStatus.CONFIRMED, Instant.parse("2026-06-02T12:00:00Z"))

        mockMvc.perform(get("/api/orders").with(jwt().jwt { it.subject(sub) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].createdAt").exists())
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
