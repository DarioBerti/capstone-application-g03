package it.g03.payments

import it.g03.payments.gateway.AuthorizationResult
import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.PaymentGateway
import it.g03.payments.gateway.TransientPspException
import it.g03.payments.gateway.paypal.PayPalPaymentGateway
import it.g03.payments.gateway.paypal.PayPalTokenProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Unit tests for the PayPal Orders v2 gateway mapping, driven by Spring's MockRestServiceServer (no live
 * PayPal, no broker, no DB). Verifies: approved -> Authorized(id) with a correctly-shaped request, 422 ->
 * Declined(issue), the capture id, void on 2xx, transient 5xx -> TransientPspException (retryable, NOT a
 * decline), and that the OAuth2 token is fetched once and cached across calls.
 */
class PayPalPaymentGatewayTest {

    private val base = "https://api-m.sandbox.paypal.com"

    private lateinit var server: MockRestServiceServer
    // Typed as the interface so calls use the default (empty) items where a test doesn't care about line items.
    private lateinit var gateway: PaymentGateway

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl(base)
        server = MockRestServiceServer.bindTo(builder).build()
        val restClient = builder.build()
        val tokenProvider = PayPalTokenProvider(restClient, "client-id", "client-secret")
        gateway = PayPalPaymentGateway(restClient, tokenProvider, "4032039999999999", "2030-01", "123")
    }

    private fun expectTokenOnce() {
        server.expect(requestTo("$base/v1/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"access_token":"tok-123","expires_in":32400}""", MediaType.APPLICATION_JSON))
    }

    @Test
    fun `authorize approved maps to Authorized with the PayPal authorization id`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("PayPal-Request-Id", "order-1"))
            .andExpect(header("Authorization", "Bearer tok-123"))
            .andExpect(jsonPath("$.intent").value("AUTHORIZE"))
            .andExpect(jsonPath("$.purchase_units[0].amount.value").value("50.00"))
            .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("EUR"))
            .andExpect(jsonPath("$.payment_source.card.number").value("4032039999999999"))
            .andRespond(
                withSuccess(
                    """{"id":"5O1","status":"COMPLETED","purchase_units":[{"payments":{"authorizations":[{"id":"AUTH-9","status":"CREATED"}]}}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = gateway.authorize("order-1", 5_000, "EUR")

        val authorized = assertIs<AuthorizationResult.Authorized>(result)
        assertEquals("AUTH-9", authorized.pspReference)
        server.verify()
    }

    @Test
    fun `authorize with line items sends purchase_units items and a matching item_total breakdown`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.purchase_units[0].amount.value").value("20.00"))
            .andExpect(jsonPath("$.purchase_units[0].amount.breakdown.item_total.value").value("20.00"))
            .andExpect(jsonPath("$.purchase_units[0].items[0].name").value("OUTBOUND FR9592"))
            .andExpect(jsonPath("$.purchase_units[0].items[0].quantity").value("1"))
            .andExpect(jsonPath("$.purchase_units[0].items[0].unit_amount.value").value("10.00"))
            .andExpect(jsonPath("$.purchase_units[0].items[1].name").value("RETURN FR9593"))
            .andRespond(
                withSuccess(
                    """{"id":"5O1","purchase_units":[{"payments":{"authorizations":[{"id":"AUTH-9"}]}}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val items = listOf(LineItem("OUTBOUND FR9592", 1, 1_000), LineItem("RETURN FR9593", 1, 1_000))
        val result = gateway.authorize("order-1", 2_000, "EUR", items)

        assertIs<AuthorizationResult.Authorized>(result)
        server.verify()
    }

    @Test
    fun `authorize declined maps to Declined with the PayPal issue`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body("""{"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"INSTRUMENT_DECLINED","description":"declined"}]}""")
                    .contentType(MediaType.APPLICATION_JSON),
            )

        val result = gateway.authorize("order-2", 5_000, "EUR")

        val declined = assertIs<AuthorizationResult.Declined>(result)
        assertEquals("INSTRUMENT_DECLINED", declined.reason)
        server.verify()
    }

    @Test
    fun `capture returns the PayPal capture id`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/payments/authorizations/AUTH-9/capture"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("PayPal-Request-Id", "cap-order-1"))
            .andRespond(withSuccess("""{"id":"CAP-7","status":"COMPLETED"}""", MediaType.APPLICATION_JSON))

        val result = gateway.capture("order-1", "AUTH-9")

        assertEquals("CAP-7", result.captureReference)
        server.verify()
    }

    @Test
    fun `void succeeds on a 2xx no-content response`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/payments/authorizations/AUTH-9/void"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("PayPal-Request-Id", "void-order-1"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        gateway.void("order-1", "AUTH-9")

        server.verify()
    }

    @Test
    fun `a 5xx from PayPal maps to TransientPspException (retryable, not a decline)`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertFailsWith<TransientPspException> { gateway.authorize("order-3", 5_000, "EUR") }
        server.verify()
    }

    @Test
    fun `the OAuth2 token is fetched once and cached across calls`() {
        expectTokenOnce()
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andRespond(
                withSuccess(
                    """{"id":"5O1","purchase_units":[{"payments":{"authorizations":[{"id":"AUTH-9"}]}}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("$base/v2/payments/authorizations/AUTH-9/capture"))
            .andRespond(withSuccess("""{"id":"CAP-7"}""", MediaType.APPLICATION_JSON))

        gateway.authorize("order-1", 5_000, "EUR")
        gateway.capture("order-1", "AUTH-9")

        server.verify()
    }
}
