package it.g03.payments.gateway.paypal

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.PspException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// Everything the browser needs to render PayPal Card Fields and, later, tell us which order it approved.
data class PayPalSession(val clientId: String, val clientToken: String, val payPalOrderId: String)

/**
 * Opens a PayPal Card-Fields session (only wired when payments.provider=paypal). Two PayPal calls, both bounded +
 * resilient like the payment gateway:
 *  1. generate a per-session **client token** (`/v1/identity/generate-token`) — the SDK auth the browser uses;
 *  2. create a PayPal order (intent=AUTHORIZE) for the booking, **without any card** — the browser attaches the
 *     card to this order id via the SDK. The returned order id is later authorized by the async saga.
 * The order is created with `PayPal-Request-Id = session-<orderId>` so a checkout remount is idempotent.
 */
@Component
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "paypal")
class PayPalSessionProvider(
    private val payPalRestClient: RestClient,
    private val tokenProvider: PayPalTokenProvider,
    @Value("\${paypal.client-id}") private val clientId: String,
) {
    @CircuitBreaker(name = PAYPAL_RESILIENCE)
    @Retry(name = PAYPAL_RESILIENCE)
    fun createSession(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): PayPalSession =
        PayPalHttp.translate {
            val clientToken = generateClientToken()
            val payPalOrderId = createOrder(orderId, amountCents, currency, items)
            PayPalSession(clientId = clientId, clientToken = clientToken, payPalOrderId = payPalOrderId)
        }

    private fun generateClientToken(): String =
        payPalRestClient.post()
            .uri("/v1/identity/generate-token")
            .header(PayPalHttp.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange { _, response ->
                val status = response.statusCode
                if (status.is2xxSuccessful) {
                    response.bodyTo(GenerateClientTokenResponse::class.java)?.clientToken
                        ?: throw PspException("PayPal generate-token returned no client_token")
                } else {
                    throw PayPalHttp.classify(status, response.bodyTo(String::class.java))
                }
            }

    private fun createOrder(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): String {
        val request = CreateOrderRequest(
            intent = "AUTHORIZE",
            purchaseUnits = listOf(purchaseUnitFor(amountCents, currency, items)),
            // No payment_source: the browser attaches the card to this order via Card Fields.
        )
        return payPalRestClient.post()
            .uri("/v2/checkout/orders")
            .header(PayPalHttp.REQUEST_ID, "session-$orderId")
            .header(PayPalHttp.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange { _, response ->
                val status = response.statusCode
                if (status.is2xxSuccessful) {
                    response.bodyTo(OrderResponse::class.java)?.id
                        ?: throw PspException("PayPal create-order returned no order id")
                } else {
                    throw PayPalHttp.classify(status, response.bodyTo(String::class.java))
                }
            }
    }

    private fun bearer() = "Bearer ${tokenProvider.token()}"
}
