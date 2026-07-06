package it.g03.catalog

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import it.g03.catalog.service.PriceQuoteSignerImpl
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signed price quote in isolation: it must bind the price to the trip (`sub`), carry the amount/currency,
 * expire, and verify under the matching public key. Uses the DEV keypair (the same one Orders is configured
 * with by default), so a token minted here is exactly what Orders will accept.
 */
class PriceQuoteSignerTest {

    private val key = ECKey.parse(DEV_SIGNING_JWK)
    private val signer = PriceQuoteSignerImpl(key, Duration.ofMinutes(30))

    @Test
    fun `signs a verifiable quote binding price to the trip`() {
        val token = signer.sign("FR9592-20260629", 4_250, "EUR")

        val jwt = SignedJWT.parse(token)
        assertTrue(jwt.verify(ECDSAVerifier(key.toPublicJWK())), "quote verifies under the public key")

        val claims = jwt.jwtClaimsSet
        assertEquals("FR9592-20260629", claims.subject, "sub binds the quote to the trip")
        assertEquals(4_250L, claims.getLongClaim("price_cents"), "carries the quoted price")
        assertEquals("EUR", claims.getStringClaim("cur"))
        assertTrue(claims.expirationTime.toInstant().isAfter(Instant.now()), "quote is not already expired")
    }

    @Test
    fun `a tampered payload fails verification`() {
        val token = signer.sign("FR9592-20260629", 1_000, "EUR")
        // Swap the payload segment for a re-encoded, higher price; the signature no longer matches.
        val parts = token.split(".")
        val forgedPayload = SignedJWT.parse(token).jwtClaimsSet.toString().replace("\"price_cents\":1000", "\"price_cents\":1")
        val forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(forgedPayload.toByteArray()) + "." + parts[2]

        assertTrue(!SignedJWT.parse(forged).verify(ECDSAVerifier(key.toPublicJWK())), "a tampered quote must not verify")
    }

    private companion object {
        const val DEV_SIGNING_JWK =
            """{"kty":"EC","d":"rRtoahvH5qqlroH0kU8YDvN05zDvvbP8rREjzUejhkc","crv":"P-256","kid":"price-quote-dev","x":"88QMkhVI-Xygzue616voDrIHFU9DZrxo91Np99RGL8I","y":"yE9kgtizYUeBDJCYNttyrHhAi5zWukU85bIHKr4CIc0","alg":"ES256"}"""
    }
}
