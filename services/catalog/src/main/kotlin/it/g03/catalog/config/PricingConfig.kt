package it.g03.catalog.config

import com.nimbusds.jose.jwk.ECKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Loads the EC private key Catalog uses to sign price quotes (ES256). The key is supplied as a JWK JSON string
 * via `PRICE_QUOTE_SIGNING_KEY` (a SealedSecret in-cluster); when absent it falls back to a fixed DEV-ONLY
 * throwaway key so local dev + tests work without the cluster (mirrors the `${DB_PASS:orders}` dev-default
 * convention). Orders holds ONLY the matching public half and verifies locally — Catalog stays off the money path.
 */
@Configuration
class PricingConfig {

    @Bean
    fun priceQuoteSigningKey(@Value("\${app.pricing.signing-key:}") signingKeyJson: String): ECKey {
        val key = ECKey.parse(signingKeyJson.ifBlank { DEV_SIGNING_JWK })
        require(key.isPrivate) { "app.pricing.signing-key must be a PRIVATE EC JWK (needs the 'd' parameter)" }
        return key
    }

    private companion object {
        // DEV-ONLY throwaway P-256 keypair; the real signing key is injected via PRICE_QUOTE_SIGNING_KEY in-cluster.
        const val DEV_SIGNING_JWK =
            """{"kty":"EC","d":"rRtoahvH5qqlroH0kU8YDvN05zDvvbP8rREjzUejhkc","crv":"P-256","kid":"price-quote-dev","x":"88QMkhVI-Xygzue616voDrIHFU9DZrxo91Np99RGL8I","y":"yE9kgtizYUeBDJCYNttyrHhAi5zWukU85bIHKr4CIc0","alg":"ES256"}"""
    }
}
