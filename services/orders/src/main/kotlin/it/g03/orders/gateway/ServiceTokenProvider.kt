package it.g03.orders.gateway

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.stereotype.Component

/**
 * Supplies the bearer token Orders uses to call its siblings. The saga runs on a Kafka listener thread
 * (no inbound user SecurityContext), so we authenticate as a service via the client-credentials grant.
 * The underlying AuthorizedClientManager caches the access token and refreshes it near expiry, so this is
 * effectively a cheap getter — no token request per call.
 *
 * Returns null if a token cannot currently be obtained (e.g. Keycloak unreachable); the gateway then
 * calls without a token and the downstream's 401 surfaces as a normal saga failure, recovered by Kafka
 * redelivery — we never block the pipeline waiting for auth.
 */
@Component
class ServiceTokenProvider(
    private val manager: OAuth2AuthorizedClientManager,
    @Value("\${app.service-client-registration:keycloak-svc}") private val registrationId: String,
) {
    fun token(): String? {
        val request = OAuth2AuthorizeRequest
            .withClientRegistrationId(registrationId)
            .principal("orders-service")
            .build()
        return runCatching { manager.authorize(request)?.accessToken?.tokenValue }.getOrNull()
    }
}
