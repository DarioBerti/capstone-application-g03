package it.g03.payments.gateway.paypal

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import it.g03.payments.gateway.AuthorizationResult
import it.g03.payments.gateway.CaptureResult
import it.g03.payments.gateway.LineItem
import it.g03.payments.gateway.PaymentGateway
import it.g03.payments.gateway.PspException
import it.g03.payments.gateway.TransientPspException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

/**
 * Real PSP: PayPal Orders v2 over a server-to-server card flow (sandbox). authorize -> create order with
 * intent=AUTHORIZE and a test card as the payment source; capture/void act on the returned authorization id.
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
    @Value("\${paypal.test-card.number}") private val cardNumber: String,
    @Value("\${paypal.test-card.expiry}") private val cardExpiry: String,
    @Value("\${paypal.test-card.security-code}") private val cardSecurityCode: String,
) : PaymentGateway {

    @CircuitBreaker(name = PAYPAL)
    @Retry(name = PAYPAL)
    override fun authorize(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): AuthorizationResult {
        val request = CreateOrderRequest(
            intent = "AUTHORIZE",
            purchaseUnits = listOf(purchaseUnit(amountCents, currency, items)),
            paymentSource = PaymentSource(Card(cardNumber, cardExpiry, cardSecurityCode)),
        )
        return translate {
            payPalRestClient.post()
                .uri("/v2/checkout/orders")
                .header(REQUEST_ID, orderId)
                .header(AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange { _, response ->
                    val status = response.statusCode
                    when {
                        status.is2xxSuccessful ->
                            AuthorizationResult.Authorized(authorizationId(response.bodyTo(OrderResponse::class.java)))
                        status.value() == DECLINE_STATUS ->
                            AuthorizationResult.Declined(issue(response.bodyTo(PayPalError::class.java)))
                        else -> throw classify(status, response.bodyTo(String::class.java))
                    }
                }
        }
    }

    @CircuitBreaker(name = PAYPAL)
    @Retry(name = PAYPAL)
    override fun capture(orderId: String, pspReference: String): CaptureResult = translate {
        payPalRestClient.post()
            .uri("/v2/payments/authorizations/{id}/capture", pspReference)
            .header(REQUEST_ID, "cap-$orderId")
            .header(AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange { _, response ->
                val status = response.statusCode
                if (status.is2xxSuccessful) CaptureResult(captureId(response.bodyTo(CaptureResponse::class.java)))
                else throw classify(status, response.bodyTo(String::class.java))
            }
    }

    @CircuitBreaker(name = PAYPAL)
    @Retry(name = PAYPAL)
    override fun void(orderId: String, pspReference: String) {
        translate {
            payPalRestClient.post()
                .uri("/v2/payments/authorizations/{id}/void", pspReference)
                .header(REQUEST_ID, "void-$orderId")
                .header(AUTHORIZATION, bearer())
                .exchange { _, response ->
                    val status = response.statusCode
                    if (!status.is2xxSuccessful) throw classify(status, response.bodyTo(String::class.java))
                }
        }
    }

    private fun bearer() = "Bearer ${tokenProvider.token()}"

    private fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (ex: ResourceAccessException) {
            throw TransientPspException("PayPal endpoint unreachable (timeout/io)", ex)
        }

    private fun classify(status: HttpStatusCode, body: String?): RuntimeException {
        val detail = body.orEmpty()
        return if (status.is5xxServerError || status.value() == TOO_MANY_REQUESTS) {
            TransientPspException("PayPal transient failure ${status.value()}: $detail")
        } else {
            PspException("PayPal request failed ${status.value()}: $detail")
        }
    }

    private fun authorizationId(order: OrderResponse?): String =
        order?.purchaseUnits?.firstOrNull()?.payments?.authorizations?.firstOrNull()?.id
            ?: throw PspException("PayPal order approved but response carried no authorization id")

    private fun issue(error: PayPalError?): String =
        error?.details?.firstOrNull()?.issue ?: error?.name ?: "PAYMENT_DECLINED"

    private fun captureId(capture: CaptureResponse?): String =
        capture?.id ?: throw PspException("PayPal capture returned no capture id")

    // Builds the purchase unit. With items, PayPal requires amount.value == breakdown.item_total == Σ(items), so
    // we compute item_total from the items and use it for both — the invariant always holds (and equals
    // amountCents, which Orders derives from the same legs). With no items, authorize the total alone (unchanged).
    private fun purchaseUnit(amountCents: Long, currency: String, items: List<LineItem>): PurchaseUnit {
        if (items.isEmpty()) return PurchaseUnit(Amount(currency, formatAmount(amountCents)))
        val itemTotal = formatAmount(items.sumOf { it.unitAmountCents * it.quantity })
        val payPalItems = items.map { Item(it.name, it.quantity.toString(), Amount(currency, formatAmount(it.unitAmountCents))) }
        return PurchaseUnit(
            amount = Amount(currency, itemTotal, Breakdown(Amount(currency, itemTotal))),
            items = payPalItems,
        )
    }

    private fun formatAmount(amountCents: Long): String = "%d.%02d".format(amountCents / 100, amountCents % 100)

    private companion object {
        const val PAYPAL = "paypal"
        const val REQUEST_ID = "PayPal-Request-Id"
        const val AUTHORIZATION = "Authorization"
        const val DECLINE_STATUS = 422
        const val TOO_MANY_REQUESTS = 429
    }
}
