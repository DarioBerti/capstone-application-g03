package it.g03.orders.service

/**
 * Verifies a signed price quote the client carries from browse time into add-leg and returns the quoted unit
 * price (cents). Verification is pure local crypto — no call to Catalog — so checkout never depends on Catalog
 * being up. Rejects (via [BadPriceQuoteException]) a quote that is malformed, tampered, expired, or issued for a
 * different trip than the one being booked.
 */
interface PriceQuoteVerifier {
    fun verify(tripCode: String, priceToken: String): Long
}
