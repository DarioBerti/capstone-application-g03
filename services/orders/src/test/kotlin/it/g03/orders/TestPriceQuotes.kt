package it.g03.orders

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date

/**
 * Mints price quotes signed with the DEV private key that matches the DEV public key Orders verifies with by
 * default — so a token produced here is accepted by [it.g03.orders.service.PriceQuoteVerifier] in tests without
 * a running Catalog. Parameters let a test forge an expired quote or one for the wrong trip.
 */
object TestPriceQuotes {

    private val key = ECKey.parse(DEV_SIGNING_JWK)
    private val signer = ECDSASigner(key)
    private val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).build()

    fun sign(
        tripCode: String,
        priceCents: Long = 1_000,
        currency: String = "EUR",
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = issuedAt.plusSeconds(1_800),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject(tripCode)
            .claim("price_cents", priceCents)
            .claim("cur", currency)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .build()
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }

    private const val DEV_SIGNING_JWK =
        """{"kty":"EC","d":"rRtoahvH5qqlroH0kU8YDvN05zDvvbP8rREjzUejhkc","crv":"P-256","kid":"price-quote-dev","x":"88QMkhVI-Xygzue616voDrIHFU9DZrxo91Np99RGL8I","y":"yE9kgtizYUeBDJCYNttyrHhAi5zWukU85bIHKr4CIc0","alg":"ES256"}"""
}
