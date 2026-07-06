package it.g03.catalog.service

/**
 * Signs a short-lived, tamper-proof price quote for a trip (a compact JWS). The client carries the quote from
 * browse time into checkout; Orders verifies it locally and snapshots the price — so Catalog never sits on the
 * money path. The quote binds the price to the trip (`sub = tripCode`) and expires.
 */
interface PriceQuoteSigner {
    fun sign(tripCode: String, priceCents: Long, currency: String): String
}
