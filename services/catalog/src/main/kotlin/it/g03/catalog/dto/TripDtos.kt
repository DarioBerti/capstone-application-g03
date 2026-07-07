package it.g03.catalog.dto

import it.g03.catalog.entities.Station
import it.g03.catalog.entities.Trip
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Boundary DTOs for the catalog API. JPA entities are never exposed directly (root convention);
 * the controller returns these immutable value types and maps from the entity explicitly.
 */

// A station as exposed to clients.
data class StationResponse(
    val code: String,
    val name: String,
    val city: String,
    val country: String,
)

// A sellable trip as exposed to clients: a structured journey (origin/destination/times), not prose.
data class TripResponse(
    val id: UUID,
    val code: String,
    val carrierCode: String,
    val serviceNumber: String,
    val serviceDate: LocalDate,
    val origin: StationResponse,
    val destination: StationResponse,
    val departureTime: OffsetDateTime,
    val arrivalTime: OffsetDateTime,
    val priceCents: Long,
    val currency: String,
    // Signed, short-lived price quote (JWS). The SPA carries it into add-leg so Orders can snapshot the price
    // it verifies locally — no synchronous Catalog call on the money path.
    val priceToken: String,
    // Advisory seat count from the availability read-model (projected off Inventory events). NULL = unknown
    // (not yet learned); the storefront renders low/plenty badges off it. Sold-out (0) rows are already
    // filtered out of search server-side.
    val availableSeats: Int?,
)

// Optional query filters for the paginated trip list. Null fields contribute no predicate, so an empty
// filter lists everything (one page at a time). departAfter/departBefore bound the departure window.
data class TripFilter(
    val origin: String? = null,
    val destination: String? = null,
    val carrierCode: String? = null,
    val serviceDate: LocalDate? = null,
    val departAfter: OffsetDateTime? = null,
    val departBefore: OffsetDateTime? = null,
    val maxPriceCents: Long? = null,
)

// Entity -> response mappings, kept at the boundary.
fun Station.toResponse() = StationResponse(code, name, city, country)

// A Trip holds only origin/destination CODES; the service resolves the Station details and signs the price
// quote, passing both in.
fun Trip.toResponse(origin: StationResponse, destination: StationResponse, priceToken: String) = TripResponse(
    id = id,
    code = code,
    carrierCode = carrierCode,
    serviceNumber = serviceNumber,
    serviceDate = serviceDate,
    origin = origin,
    destination = destination,
    departureTime = departureTime,
    arrivalTime = arrivalTime,
    priceCents = priceCents,
    currency = currency,
    priceToken = priceToken,
    availableSeats = availableSeats,
)
