package it.g03.payments.gateway.paypal

import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The first outbound HTTP client in the codebase: a RestClient pointed at PayPal (sandbox) with explicit
 * connect/read timeouts (never an unbounded hang, Pillar C). Built from the static RestClient.builder() — Boot 4
 * modularizes its auto-config and does NOT expose a RestClient.Builder bean here — with the default Jackson 3
 * message converters (present on the classpath) and the app's ObservationRegistry so the call is traced
 * (Pillar D). Only wired when payments.provider=paypal.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "paypal")
class PayPalConfig {

    @Bean
    fun payPalRestClient(
        @Value("\${paypal.base-url}") baseUrl: String,
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
