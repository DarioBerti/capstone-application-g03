package it.g03.catalog.controller

import it.g03.catalog.entities.Product

/**
 * Boundary DTOs for the catalog API. JPA entities are never exposed directly (root convention);
 * the controller returns these immutable value types and maps from the entity explicitly.
 */

// What a client receives for a product.
data class ProductResponse(
    val productId: String,
    val name: String,
    val priceCents: Long,
)

// Optional query filters for the paginated product list. Null fields contribute no predicate,
// so an empty filter lists everything (one page at a time).
data class ProductFilter(
    val name: String? = null,
    val maxPriceCents: Long? = null,
)

// Entity -> response mapping, kept at the boundary.
fun Product.toResponse() = ProductResponse(productId, name, priceCents)
