package it.g03.catalog.service

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.Date

@Service
class PriceQuoteSignerImpl(
    signingKey: ECKey,
    // How long a quote stays valid: comfortably longer than a browse->book session (the quote is consumed at
    // add-leg, not at pay), short enough that a stale price self-heals.
    @Value("\${app.pricing.quote-ttl:PT30M}") private val ttl: Duration,
) : PriceQuoteSigner {

    private val signer = ECDSASigner(signingKey)
    private val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.keyID).build()

    override fun sign(tripCode: String, priceCents: Long, currency: String): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(tripCode)
            .claim(CLAIM_PRICE_CENTS, priceCents)
            .claim(CLAIM_CURRENCY, currency)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .build()
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }

    private companion object {
        const val CLAIM_PRICE_CENTS = "price_cents"
        const val CLAIM_CURRENCY = "cur"
    }
}
