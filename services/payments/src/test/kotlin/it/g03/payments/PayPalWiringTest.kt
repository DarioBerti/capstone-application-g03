package it.g03.payments

import it.g03.payments.gateway.PaymentGateway
import it.g03.payments.gateway.paypal.PayPalPaymentGateway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertIs

/**
 * Boots the context with payments.provider=paypal to PROVE the PayPal wiring resolves — in particular that
 * Spring Boot auto-configures the `RestClient.Builder` PayPalConfig depends on (Boot 4 modularizes its
 * auto-config, so this is not a given). The default-provider tests never load these beans. No network is
 * touched: the token is fetched lazily, so dummy credentials are enough to build the context.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "payments.provider=paypal",
        "paypal.base-url=https://api-m.sandbox.paypal.com",
        "paypal.client-id=test-client",
        "paypal.client-secret=test-secret",
        "paypal.test-card.number=4032039999999999",
        "paypal.test-card.expiry=2030-01",
        "paypal.test-card.security-code=123",
    ],
)
@Import(TestcontainersConfiguration::class)
class PayPalWiringTest {

    @Autowired lateinit var gateway: PaymentGateway

    @Test
    fun `paypal provider selects the PayPal gateway and its RestClient bean resolves`() {
        assertIs<PayPalPaymentGateway>(gateway)
    }
}
