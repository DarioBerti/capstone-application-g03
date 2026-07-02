package it.g03.payments.gateway.paypal

import it.g03.payments.gateway.PspException
import it.g03.payments.gateway.TransientPspException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * PayPal OAuth2 client-credentials token, cached in-memory. A single access token (valid ~9h in sandbox) is
 * reused across calls and refreshed a safety margin before expiry. Thread-safe: the cached token is swapped
 * atomically and a benign concurrent double-fetch is acceptable (both tokens are valid).
 */
@Component
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "paypal")
class PayPalTokenProvider(
    private val payPalRestClient: RestClient,
    @Value("\${paypal.client-id}") private val clientId: String,
    @Value("\${paypal.client-secret}") private val clientSecret: String,
) {
    private data class CachedToken(val value: String, val expiresAt: Instant)

    private val cached = AtomicReference<CachedToken?>()

    fun token(): String {
        cached.get()?.let { if (Instant.now().isBefore(it.expiresAt)) return it.value }
        return fetch().also { cached.set(it) }.value
    }

    private fun fetch(): CachedToken {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        try {
            val response = payPalRestClient.post()
                .uri("/v1/oauth2/token")
                .header("Authorization", "Basic $basic")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(TokenResponse::class.java)
                ?: throw PspException("PayPal token response carried no body")
            val expiresAt = Instant.now().plusSeconds(response.expiresIn).minus(REFRESH_MARGIN)
            return CachedToken(response.accessToken, expiresAt)
        } catch (ex: ResourceAccessException) {
            throw TransientPspException("PayPal token endpoint unreachable (timeout/io)", ex)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.is5xxServerError) throw TransientPspException("PayPal token endpoint 5xx", ex)
            throw PspException("PayPal token request failed: ${ex.statusCode}", ex)
        }
    }

    private companion object {
        val REFRESH_MARGIN: Duration = Duration.ofSeconds(60)
    }
}
