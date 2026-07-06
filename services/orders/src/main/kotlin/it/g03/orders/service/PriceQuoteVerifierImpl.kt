package it.g03.orders.service

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PriceQuoteVerifierImpl(
    private val verifier: ECDSAVerifier,
) : PriceQuoteVerifier {

    override fun verify(tripCode: String, priceToken: String): Long {
        val jwt = runCatching { SignedJWT.parse(priceToken) }
            .getOrElse { throw BadPriceQuoteException("malformed price quote") }

        val signatureOk = runCatching { jwt.verify(verifier) }
            .getOrElse { throw BadPriceQuoteException("price quote could not be verified") }
        if (!signatureOk) throw BadPriceQuoteException("price quote signature is invalid")

        val claims = jwt.jwtClaimsSet
        val expiresAt = claims.expirationTime?.toInstant()
            ?: throw BadPriceQuoteException("price quote has no expiry")
        if (expiresAt.isBefore(Instant.now())) throw BadPriceQuoteException("price quote has expired")

        // Bind the quote to THIS trip: a quote for a cheap trip must not price a leg on a different (dearer) one.
        if (claims.subject != tripCode) throw BadPriceQuoteException("price quote is for a different trip")

        val priceCents = runCatching { claims.getLongClaim(CLAIM_PRICE_CENTS) }.getOrNull()
            ?: throw BadPriceQuoteException("price quote has no price")
        if (priceCents < 0) throw BadPriceQuoteException("price quote has a negative price")
        return priceCents
    }

    private companion object {
        const val CLAIM_PRICE_CENTS = "price_cents"
    }
}
