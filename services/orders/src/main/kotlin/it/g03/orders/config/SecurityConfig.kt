package it.g03.orders.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Orders sits on both sides of auth:
 *  - INBOUND (resource server): the public POST /api/orders + GET /api/orders/{id} require a valid
 *    Keycloak user token (forwarded by the frontend). Actuator stays open for probes/scraping.
 *  - OUTBOUND (oauth2 client): the saga's calls to Inventory/Payments run on a Kafka listener thread
 *    where there is no user SecurityContext, so they carry a *service* token minted via client
 *    credentials (see ServiceTokenProvider + Gateways). The AuthorizedClientManager below issues it.
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

    /**
     * Manager for OUTBOUND service tokens. The "service" variant (not the request-bound default) works off
     * the HTTP request thread — essential because the saga runs on a Kafka consumer thread. It caches the
     * token via the authorized-client service and refreshes it near expiry.
     */
    @Bean
    fun authorizedClientManager(
        clients: ClientRegistrationRepository,
        authorizedClients: OAuth2AuthorizedClientService,
    ): OAuth2AuthorizedClientManager {
        val manager = AuthorizedClientServiceOAuth2AuthorizedClientManager(clients, authorizedClients)
        manager.setAuthorizedClientProvider(
            OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build(),
        )
        return manager
    }
}
