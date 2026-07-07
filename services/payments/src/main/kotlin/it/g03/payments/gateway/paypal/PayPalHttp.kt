package it.g03.payments.gateway.paypal

import it.g03.payments.gateway.PspException
import it.g03.payments.gateway.TransientPspException
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.ResourceAccessException

// Resilience4j instance name shared by every outbound PayPal call (@Retry/@CircuitBreaker).
const val PAYPAL_RESILIENCE = "paypal"

/**
 * Shared PayPal HTTP concerns for the gateway AND the Card-Fields session provider: the request headers and the
 * error mapping. The mapping is load-bearing — a transient transport failure (5xx/timeout/network/429) must throw
 * [TransientPspException] so the caller rolls back and the Kafka retry/DLT + sweeper re-drive; a terminal 4xx is a
 * non-retryable [PspException] parked to the DLT. A 422 decline is handled by the caller, not here.
 */
internal object PayPalHttp {
    const val REQUEST_ID = "PayPal-Request-Id"
    const val AUTHORIZATION = "Authorization"
    const val DECLINE_STATUS = 422
    private const val TOO_MANY_REQUESTS = 429

    fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (ex: ResourceAccessException) {
            throw TransientPspException("PayPal endpoint unreachable (timeout/io)", ex)
        }

    fun classify(status: HttpStatusCode, body: String?): RuntimeException {
        val detail = body.orEmpty()
        return if (status.is5xxServerError || status.value() == TOO_MANY_REQUESTS) {
            TransientPspException("PayPal transient failure ${status.value()}: $detail")
        } else {
            PspException("PayPal request failed ${status.value()}: $detail")
        }
    }
}
