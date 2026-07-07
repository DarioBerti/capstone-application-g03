package it.g03.orders.gateway

import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * RestClient for the ONE synchronous Orders -> Payments call the money path still needs: opening a PayPal
 * Card-Fields session (the browser must create + approve a PayPal order before pay). The async saga stays on
 * Kafka. Bounded connect/read timeouts (never an unbounded hang, Pillar C); traced via the app ObservationRegistry.
 */
@Configuration(proxyBeanMethods = false)
class PaymentsGatewayConfig {

    @Bean
    fun paymentsRestClient(
        @Value("\${app.payments.base-url}") baseUrl: String,
        observationRegistry: ObservationRegistry,
    ): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(CONNECT_TIMEOUT)
            setReadTimeout(READ_TIMEOUT)
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .observationRegistry(observationRegistry)
            .build()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
