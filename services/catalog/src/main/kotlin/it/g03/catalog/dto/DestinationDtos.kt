package it.g03.catalog.dto

import it.g03.catalog.repository.DestinationAggregate

/**
 * Boundary DTO for the "popular destinations" strip. A destination station enriched with its cheapest-from
 * price and its count of future departures. This is an aggregate over trips (not a single Trip), so unlike
 * [TripResponse] it carries no signed price quote — it's a browse hint, not a bookable offer.
 */
data class DestinationSummaryResponse(
    val code: String,
    val name: String,
    val city: String,
    val country: String,
    val fromPriceCents: Long,
    val currency: String,
    val tripCount: Long,
)

// Projection -> response mapping, kept at the boundary.
fun DestinationAggregate.toResponse() = DestinationSummaryResponse(
    code = code,
    name = name,
    city = city,
    country = country,
    fromPriceCents = fromPriceCents,
    currency = currency,
    tripCount = tripCount,
)
