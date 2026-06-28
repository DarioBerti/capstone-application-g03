package it.g03.catalog.service

import it.g03.catalog.controller.ProductFilter
import it.g03.catalog.entities.Product
import it.g03.catalog.repository.ProductRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service

/**
 * Business layer for the catalog (Controller -> Service -> Repository, mandatory for every service).
 * Read-mostly: there is little domain logic, but the layer still owns query composition (turning a
 * ProductFilter into a Specification) and the not-found contract, so the controller stays HTTP-only.
 */
@Service
class CatalogService(private val products: ProductRepository) {

    // Paginated, filterable list. A null filter field adds no predicate (lists everything).
    fun search(filter: ProductFilter, pageable: Pageable): Page<Product> =
        products.findAll(filter.toSpecification(), pageable)

    // Single product lookup; a missing id is a domain not-found (mapped to 404 at the boundary).
    fun byId(productId: String): Product =
        products.findById(productId)
            .orElseThrow { NoSuchElementException("unknown product: $productId") }
}

// Builds a composable Specification from the filter. Each helper returns null when its field is
// absent; nulls are dropped so only the supplied filters constrain the query.
private fun ProductFilter.toSpecification(): Specification<Product> =
    Specification.allOf(
        listOfNotNull(nameContains(name), priceAtMost(maxPriceCents)),
    )

private fun nameContains(name: String?): Specification<Product>? =
    name?.takeIf { it.isNotBlank() }?.let { value ->
        Specification { root, _, cb ->
            cb.like(cb.lower(root.get<String>("name")), "%${value.lowercase()}%")
        }
    }

private fun priceAtMost(maxPriceCents: Long?): Specification<Product>? =
    maxPriceCents?.let { max ->
        Specification { root, _, cb ->
            cb.lessThanOrEqualTo(root.get<Long>("priceCents"), max)
        }
    }
