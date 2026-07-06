package it.g03.orders.config

import com.nimbusds.jose.crypto.ECDSAVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Loads the EC PUBLIC key Orders uses to verify Catalog's price quotes (ES256). Supplied as a public JWK JSON
 * string via `PRICE_QUOTE_PUBLIC_KEY` (a non-secret ConfigMap value in-cluster); blank falls back to the fixed
 * DEV-ONLY public key that matches Catalog's dev signing key, so local dev + tests work without the cluster.
 * Orders holds only the public half — it can verify a quote but never mint one (least privilege).
 */
@Configuration
class PricingConfig {

    @Bean
    fun priceQuoteVerifier(@Value("\${app.pricing.verify-key:}") verifyKeyJson: String): ECDSAVerifier {
        val json = verifyKeyJson.ifBlank { DEV_PUBLIC_JWK }
        return ECDSAVerifier(com.nimbusds.jose.jwk.ECKey.parse(json))
    }

    private companion object {
        // DEV-ONLY public key; the real verify key is injected via PRICE_QUOTE_PUBLIC_KEY in-cluster. Matches
        // catalog's dev signing key (same x/y), so a quote signed by a locally-run Catalog verifies here.
        const val DEV_PUBLIC_JWK =
            """{"kty":"EC","crv":"P-256","kid":"price-quote-dev","x":"88QMkhVI-Xygzue616voDrIHFU9DZrxo91Np99RGL8I","y":"yE9kgtizYUeBDJCYNttyrHhAi5zWukU85bIHKr4CIc0","alg":"ES256"}"""
    }
}
