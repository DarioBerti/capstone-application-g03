package it.g03.orders.config

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
 * Orders is an INBOUND resource server only: the public POST /api/orders + GET /api/orders/{id} require a
 * valid Keycloak user token (forwarded by the frontend). Actuator stays open for probes/scraping.
 *
 * There is no longer any OUTBOUND oauth2-client wiring: the saga now drives Inventory/Payments over Kafka
 * (commands + replies via the outbox), not authenticated HTTP, so the service token / AuthorizedClientManager
 * are gone with the gateways.
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
     * Lazy, issuer-checked JWT decoder for INBOUND tokens. `withJwkSetUri` fetches + caches the JWKS on
     * the first token (not at startup), so the service boots even when Keycloak is briefly unavailable
     * (Pillar C). Issuer validation is added because jwk-set-uri alone does not verify the `iss` claim.
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
