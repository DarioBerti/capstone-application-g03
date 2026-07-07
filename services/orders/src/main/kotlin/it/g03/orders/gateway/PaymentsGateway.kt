package it.g03.orders.gateway

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import it.g03.orders.dto.PayPalSessionResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// A booking leg the PSP records (name/qty/unit); sums to the order amount.
data class PaymentLineItemDto(val name: String, val quantity: Int, val unitAmountCents: Long)

// Request body Orders POSTs to the payments internal session endpoint.
data class PayPalSessionGatewayRequest(
    val amountCents: Long,
    val currency: String,
    val items: List<PaymentLineItemDto>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PayPalSessionGatewayResponse(
    val clientId: String,
    val clientToken: String,
    val payPalOrderId: String,
)

/**
 * The one synchronous outbound call on the money path: ask Payments (in-cluster) to open a PayPal Card-Fields
 * session for a booking about to be paid. Payments secures the endpoint as an OAuth2 resource server, so we RELAY
 * the buyer's Keycloak token (same realm) — Orders holds no service credential. Bounded (RestClient timeouts) and
 * resilient (bounded @Retry + @CircuitBreaker on the `payments` instances); the session create is idempotent on
 * PayPal's side (PayPal-Request-Id), so a retry is safe.
 */
@Component
class PaymentsGateway(private val paymentsRestClient: RestClient) {

    @CircuitBreaker(name = PAYMENTS)
    @Retry(name = PAYMENTS)
    fun createPayPalSession(
        orderId: String,
        amountCents: Long,
        currency: String,
        items: List<PaymentLineItemDto>,
        bearerToken: String,
    ): PayPalSessionResponse {
        val response = paymentsRestClient.post()
            .uri("/internal/payments/{orderId}/paypal-session", orderId)
            .header("Authorization", "Bearer $bearerToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PayPalSessionGatewayRequest(amountCents, currency, items))
            .retrieve()
            .body(PayPalSessionGatewayResponse::class.java)
            ?: throw IllegalStateException("payments returned an empty PayPal session response for order $orderId")
        return PayPalSessionResponse(response.clientId, response.clientToken, response.payPalOrderId)
    }

    private companion object {
        const val PAYMENTS = "payments"
    }
}
