package it.g03.payments

import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.paypal.PayPalSessionProvider
import it.g03.payments.gateway.paypal.PayPalTokenProvider
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
 * Button (wallet) session provider, driven by MockRestServiceServer (no live PayPal). Verifies it fetches a
 * client token and creates a **card-less** AUTHORIZE order (idempotent PayPal-Request-Id) whose wallet
 * payment_source carries the return/cancel URLs back to the buyer's checkout page — with no card/secret in the
 * request — and returns the public client-id + client token + order id the browser needs.
 */
class PayPalSessionProviderTest {

    private val base = "https://api-m.sandbox.paypal.com"
    private val frontend = "https://app.example.test"
    private lateinit var server: MockRestServiceServer
    private lateinit var provider: PayPalSessionProvider

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl(base)
        server = MockRestServiceServer.bindTo(builder).build()
        val restClient = builder.build()
        val tokenProvider = PayPalTokenProvider(restClient, "client-id", "client-secret")
        provider = PayPalSessionProvider(restClient, tokenProvider, "PUB-CLIENT-ID", frontend)
    }

    @Test
    fun `createSession returns the client token and a card-less wallet order id with return URLs`() {
        server.expect(requestTo("$base/v1/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"access_token":"tok-123","expires_in":32400}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$base/v1/identity/generate-token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer tok-123"))
            .andRespond(withSuccess("""{"client_token":"ct-9"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("PayPal-Request-Id", "session-order-1"))
            .andExpect(jsonPath("$.intent").value("AUTHORIZE"))
            .andExpect(jsonPath("$.purchase_units[0].amount.value").value("50.00"))
            .andExpect(jsonPath("$.payment_source.card").doesNotExist())
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url").value("$frontend/orders/order-1"))
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.cancel_url").value("$frontend/orders/order-1"))
            .andRespond(withSuccess("""{"id":"PP-ORDER-1","status":"CREATED"}""", MediaType.APPLICATION_JSON))

        val session = provider.createSession("order-1", 5_000, "EUR", emptyList())

        assertEquals("PUB-CLIENT-ID", session.clientId)
        assertEquals("ct-9", session.clientToken)
        assertEquals("PP-ORDER-1", session.payPalOrderId)
        server.verify()
    }

    @Test
    fun `createSession forwards the booking legs as purchase_units items`() {
        server.expect(requestTo("$base/v1/oauth2/token"))
            .andRespond(withSuccess("""{"access_token":"tok-123","expires_in":32400}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$base/v1/identity/generate-token"))
            .andRespond(withSuccess("""{"client_token":"ct-9"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andExpect(jsonPath("$.purchase_units[0].items[0].name").value("OUTBOUND FR9592"))
            .andExpect(jsonPath("$.purchase_units[0].amount.breakdown.item_total.value").value("20.00"))
            .andRespond(withSuccess("""{"id":"PP-ORDER-2"}""", MediaType.APPLICATION_JSON))

        val items = listOf(LineItem("OUTBOUND FR9592", 1, 1_000), LineItem("RETURN FR9593", 1, 1_000))
        val session = provider.createSession("order-1", 2_000, "EUR", items)

        assertEquals("PP-ORDER-2", session.payPalOrderId)
        server.verify()
    }
}
