package it.g03.catalog.repository

// Read-only query projection for the "popular destinations" aggregation (see TripRepository.topDestinations):
// a destination station enriched with its cheapest-from price and its count of future departures. Spring Data
// maps the JPQL select aliases onto these getters by name.
interface DestinationAggregate {
    val code: String
    val name: String
    val city: String
    val country: String
    val fromPriceCents: Long
    val currency: String
    val tripCount: Long
}
