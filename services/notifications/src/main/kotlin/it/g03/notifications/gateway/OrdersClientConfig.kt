package it.g03.notifications.gateway

import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * RestClient for the Orders ownership check (SSE stream authorization). Bounded connect/read timeouts so a
 * slow/broken Orders can never hang the stream open (Pillar C); traced via the app ObservationRegistry
 * (Pillar D). Base URL is env-driven (ORDERS_BASE_URL) with a local-dev default. Built from the static
 * RestClient.builder() — Boot 4 modularizes its auto-config and does not expose a RestClient.Builder bean.
 */
@Configuration(proxyBeanMethods = false)
class OrdersClientConfig {

    @Bean
    fun ordersRestClient(
        @Value("\${orders.base-url}") baseUrl: String,
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
        val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
