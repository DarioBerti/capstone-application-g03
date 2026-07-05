package it.g03.notifications.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Notifications is an async edge sink with an authenticated SSE surface: the per-user stream
 * (`GET /api/notifications/orders/{id}/events`) that relays saga updates to the browser. It validates the
 * caller's Keycloak token like the other resource servers; actuator stays open for probes/scraping. The stream
 * additionally verifies order ownership by calling Orders with the caller's own token (see OrdersClient) — a
 * read-only authorization check, off the money path; there is still no outbound oauth2-client (service-token)
 * wiring.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { authz ->
            authz
                .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { oauth2 -> oauth2.jwt {} }
        .build()

    /**
     * Lazy, issuer-checked JWT decoder for INBOUND tokens. `withJwkSetUri` fetches + caches the JWKS on the
     * first token (not at startup), so the service boots — and readiness stays green — even when Keycloak is
     * briefly unavailable. That is essential here: Notifications must never fail readiness on a downstream
     * (its off-critical-path / graceful-degradation contract). Issuer validation is added because jwk-set-uri
     * alone does not verify the `iss` claim.
     */
    @Bean
    fun jwtDecoder(
        @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") jwkSetUri: String,
        @Value("\${app.oidc.issuer}") issuer: String,
    ): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(JwtValidators.createDefault(), JwtIssuerValidator(issuer)),
        )
        return decoder
    }
}
