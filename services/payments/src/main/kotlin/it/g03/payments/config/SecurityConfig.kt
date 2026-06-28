package it.g03.payments.config

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
 * Locks the API behind Keycloak-issued JWTs while keeping operational endpoints open.
 *
 * - Actuator health/readiness/liveness, Prometheus and info stay public so Kubernetes probes and the
 *   Prometheus scraper never need a token.
 * - Every business endpoint requires a valid bearer token (Orders forwards a service token; see orders
 *   Gateways).
 * - Stateless + CSRF off: a token-authenticated API with no server-side session or browser form.
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
     * Lazy, issuer-checked JWT decoder. `withJwkSetUri` fetches + caches the JWKS on the first token
     * (not at startup), so the service boots even when Keycloak is briefly unavailable (Pillar C). We add
     * issuer validation on top of the default timestamp checks because jwk-set-uri alone does not verify
     * the `iss` claim.
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
