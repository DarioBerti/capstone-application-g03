package it.g03.orders

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import it.g03.orders.service.BadPriceQuoteException
import it.g03.orders.service.PriceQuoteVerifierImpl
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The price-quote verifier in isolation: accepts a valid quote and returns its price; rejects a quote that is
 * malformed, signed by the wrong key, expired, or issued for a different trip. Verifier is built from the DEV
 * public key that matches [TestPriceQuotes]'s signing key.
 */
class PriceQuoteVerifierTest {

    private val verifier = PriceQuoteVerifierImpl(ECDSAVerifier(ECKey.parse(DEV_PUBLIC_JWK)))
    private val trip = "FR9592-20260629"

    @Test
    fun `accepts a valid quote and returns its price`() {
        val price = verifier.verify(trip, TestPriceQuotes.sign(trip, priceCents = 4_250))
        assertEquals(4_250, price)
    }

    @Test
    fun `rejects a quote issued for a different trip`() {
        val quote = TestPriceQuotes.sign("FR0001-20260629", priceCents = 1)
        assertFailsWith<BadPriceQuoteException> { verifier.verify(trip, quote) }
    }

    @Test
    fun `rejects an expired quote`() {
        val past = Instant.now().minusSeconds(3_600)
        val expired = TestPriceQuotes.sign(trip, issuedAt = past, expiresAt = past.plusSeconds(60))
        assertFailsWith<BadPriceQuoteException> { verifier.verify(trip, expired) }
    }

    @Test
    fun `rejects a malformed token`() {
        assertFailsWith<BadPriceQuoteException> { verifier.verify(trip, "not-a-jwt") }
    }

    @Test
    fun `rejects a quote signed by an unknown key`() {
        // Verifier is built for the DEV key; a token signed by a DIFFERENT key must not verify.
        val foreignVerifier = PriceQuoteVerifierImpl(ECDSAVerifier(ECKey.parse(OTHER_PUBLIC_JWK)))
        assertFailsWith<BadPriceQuoteException> {
            foreignVerifier.verify(trip, TestPriceQuotes.sign(trip, priceCents = 4_250))
        }
    }

    private companion object {
        const val DEV_PUBLIC_JWK =
            """{"kty":"EC","crv":"P-256","kid":"price-quote-dev","x":"88QMkhVI-Xygzue616voDrIHFU9DZrxo91Np99RGL8I","y":"yE9kgtizYUeBDJCYNttyrHhAi5zWukU85bIHKr4CIc0","alg":"ES256"}"""
        // A DISTINCT P-256 public key (different x/y) — used to prove a quote from an unknown signer is rejected.
        const val OTHER_PUBLIC_JWK =
            """{"kty":"EC","crv":"P-256","kid":"other","x":"gvI3mgQyGgw1GoOvSBwRnyUVWSlRwCBLNlSCZWrKudo","y":"vijCL-ZRmKAAOAW4TBPTuL2KP5OorcQwK0TdXLZKQ6w","alg":"ES256"}"""
    }
}
