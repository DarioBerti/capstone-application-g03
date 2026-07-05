package it.g03.notifications

import it.g03.notifications.gateway.OrdersClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The SSE stream must be per-user: a caller may open a stream only for an order they own. Ownership is
 * delegated to Orders via [OrdersClient]; a fake stands in here so the gate is tested in isolation, with no
 * broker and no live Orders.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "app.kafka-listeners-enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(OrderStreamControllerTest.FakeOrdersClientConfig::class)
class OrderStreamControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var ordersClient: FakeOrdersClient

    @TestConfiguration
    class FakeOrdersClientConfig {
        @Bean
        @Primary
        fun fakeOrdersClient() = FakeOrdersClient()
    }

    // Hand-written fake (project convention: fakes over mocks); `owner` flips the ownership answer per test.
    class FakeOrdersClient(var owner: Boolean = true) : OrdersClient {
        override fun isOwner(orderId: String, bearerToken: String) = owner
    }

    private val orderId = "11111111-1111-1111-1111-111111111111"

    @Test
    fun `stream without a token is rejected with 401`() {
        mockMvc.perform(get("/api/notifications/orders/$orderId/events"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `stream for another buyer's order is rejected with 404`() {
        ordersClient.owner = false
        mockMvc.perform(get("/api/notifications/orders/$orderId/events").with(jwt().jwt { it.subject("kc-sub-1") }))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `stream for an owned order opens the SSE stream`() {
        ordersClient.owner = true
        mockMvc.perform(get("/api/notifications/orders/$orderId/events").with(jwt().jwt { it.subject("kc-sub-1") }))
            .andExpect(request().asyncStarted())
    }
}
