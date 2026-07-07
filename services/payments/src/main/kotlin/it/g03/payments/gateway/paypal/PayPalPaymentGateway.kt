package it.g03.payments.gateway.paypal

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import it.g03.payments.gateway.AuthorizationResult
import it.g03.payments.gateway.CaptureResult
import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.PaymentGateway
import it.g03.payments.gateway.PspException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Real PSP: PayPal Orders v2 (sandbox). Two authorize modes:
 *  - Card-Fields (default in-cluster): the browser already created + approved a PayPal order (card attached in
 *    PayPal's iframe), so authorize just POSTs `/orders/{payPalOrderId}/authorize` on that approved order.
 *  - Server-to-server card flow (fallback, payPalOrderId=null): create an order with intent=AUTHORIZE and the
 *    configured test card as the payment source.
 * capture/void act on the returned authorization id.
 *
 * Idempotency is layered: PayPal dedupes on the PayPal-Request-Id header (orderId / cap-orderId / void-orderId),
 * on top of the service's UNIQUE(order_id) guard. Resilience (Pillar C): @Retry + @CircuitBreaker retry only
 * [TransientPspException] (5xx/timeout/network); a DECLINE (422) is a returned business outcome, never a failure.
 */
@Component
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "paypal")
class PayPalPaymentGateway(
    private val payPalRestClient: RestClient,
    private val tokenProvider: PayPalTokenProvider,
    @Value("\${paypal.test-card.number:}") private val cardNumber: String,
    @Value("\${paypal.test-card.expiry:}") private val cardExpiry: String,
    @Value("\${paypal.test-card.security-code:}") private val cardSecurityCode: String,
) : PaymentGateway {

    @CircuitBreaker(name = PAYPAL_RESILIENCE)
    @Retry(name = PAYPAL_RESILIENCE)
    override fun authorize(
        orderId: String,
        amountCents: Long,
        currency: String,
        items: List<LineItem>,
        payPalOrderId: String?,
    ): AuthorizationResult =
        if (payPalOrderId != null) authorizeApprovedOrder(orderId, payPalOrderId)
        else createAndAuthorizeWithCard(orderId, amountCents, currency, items)

    // Card-Fields path: authorize the order the browser already created + approved (card attached client-side).
    private fun authorizeApprovedOrder(orderId: String, payPalOrderId: String): AuthorizationResult =
        PayPalHttp.translate {
            payPalRestClient.post()
                .uri("/v2/checkout/orders/{id}/authorize", payPalOrderId)
                .header(PayPalHttp.REQUEST_ID, orderId)
                .header(PayPalHttp.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange { _, response -> mapAuthorizeResponse(response) }
        }

    // Server-to-server card path: create the order with the configured test card as the payment source.
    private fun createAndAuthorizeWithCard(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): AuthorizationResult {
        val request = CreateOrderRequest(
            intent = "AUTHORIZE",
            purchaseUnits = listOf(purchaseUnitFor(amountCents, currency, items)),
            paymentSource = PaymentSource(Card(cardNumber, cardExpiry, cardSecurityCode)),
        )
        return PayPalHttp.translate {
            payPalRestClient.post()
                .uri("/v2/checkout/orders")
                .header(PayPalHttp.REQUEST_ID, orderId)
                .header(PayPalHttp.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange { _, response -> mapAuthorizeResponse(response) }
        }
    }

    private fun mapAuthorizeResponse(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse): AuthorizationResult {
        val status = response.statusCode
        return when {
            status.is2xxSuccessful ->
                AuthorizationResult.Authorized(authorizationId(response.bodyTo(OrderResponse::class.java)))
            status.value() == PayPalHttp.DECLINE_STATUS ->
                AuthorizationResult.Declined(issue(response.bodyTo(PayPalError::class.java)))
            else -> throw PayPalHttp.classify(status, response.bodyTo(String::class.java))
        }
    }

    @CircuitBreaker(name = PAYPAL_RESILIENCE)
    @Retry(name = PAYPAL_RESILIENCE)
    override fun capture(orderId: String, pspReference: String): CaptureResult = PayPalHttp.translate {
        payPalRestClient.post()
            .uri("/v2/payments/authorizations/{id}/capture", pspReference)
            .header(PayPalHttp.REQUEST_ID, "cap-$orderId")
            .header(PayPalHttp.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange { _, response ->
                val status = response.statusCode
                if (status.is2xxSuccessful) CaptureResult(captureId(response.bodyTo(CaptureResponse::class.java)))
                else throw PayPalHttp.classify(status, response.bodyTo(String::class.java))
            }
    }

    @CircuitBreaker(name = PAYPAL_RESILIENCE)
    @Retry(name = PAYPAL_RESILIENCE)
    override fun void(orderId: String, pspReference: String) {
        PayPalHttp.translate {
            payPalRestClient.post()
                .uri("/v2/payments/authorizations/{id}/void", pspReference)
                .header(PayPalHttp.REQUEST_ID, "void-$orderId")
                .header(PayPalHttp.AUTHORIZATION, bearer())
                .exchange { _, response ->
                    val status = response.statusCode
                    if (!status.is2xxSuccessful) throw PayPalHttp.classify(status, response.bodyTo(String::class.java))
                }
        }
    }

    private fun bearer() = "Bearer ${tokenProvider.token()}"

    private fun authorizationId(order: OrderResponse?): String =
        order?.purchaseUnits?.firstOrNull()?.payments?.authorizations?.firstOrNull()?.id
            ?: throw PspException("PayPal order approved but response carried no authorization id")

    private fun issue(error: PayPalError?): String =
        error?.details?.firstOrNull()?.issue ?: error?.name ?: "PAYMENT_DECLINED"

    private fun captureId(capture: CaptureResponse?): String =
        capture?.id ?: throw PspException("PayPal capture returned no capture id")
}
