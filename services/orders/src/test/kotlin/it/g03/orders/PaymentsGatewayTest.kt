package it.g03.orders

import it.g03.orders.gateway.PaymentLineItemDto
import it.g03.orders.gateway.PaymentsGateway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

/**
 * The Orders -> Payments session gateway, driven by MockRestServiceServer (no live payments, no DB). Verifies it
 * RELAYS the buyer's bearer token, posts the booking amount/items to the internal session endpoint, and maps the
 * response the browser needs — no card or secret crosses this seam.
 */
class PaymentsGatewayTest {

    private val base = "http://payments:8080"
    private lateinit var server: MockRestServiceServer
    private lateinit var gateway: PaymentsGateway

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl(base)
        server = MockRestServiceServer.bindTo(builder).build()
        gateway = PaymentsGateway(builder.build())
    }

    @Test
    fun `createPayPalSession relays the token, posts the booking, and maps the session response`() {
        server.expect(requestTo("$base/internal/payments/order-1/paypal-session"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer buyer-token-123"))
            .andExpect(jsonPath("$.amountCents").value(2000))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.items[0].name").value("OUTBOUND FR9592"))
            .andRespond(
                withSuccess(
                    """{"clientId":"PUB-CLIENT-ID","clientToken":"ct-9","payPalOrderId":"PP-ORDER-1"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val items = listOf(PaymentLineItemDto("OUTBOUND FR9592", 1, 1_000), PaymentLineItemDto("RETURN FR9593", 1, 1_000))
        val session = gateway.createPayPalSession("order-1", 2_000, "EUR", items, "buyer-token-123")

        assertEquals("PUB-CLIENT-ID", session.clientId)
        assertEquals("ct-9", session.clientToken)
        assertEquals("PP-ORDER-1", session.payPalOrderId)
        server.verify()
    }
}
