package it.g03.catalog.controller

import it.g03.catalog.dto.ProductFilter
import it.g03.catalog.dto.ProductResponse
import it.g03.catalog.dto.toResponse
import it.g03.catalog.service.CatalogService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public read API (exposed through the ingress at /api/catalog). Read-mostly and tolerant
 * of staleness by contract — it sits OUTSIDE the money path — and is a candidate for
 * caching/replicas later. HTTP-only: it delegates to CatalogService and maps entities to DTOs;
 * it never touches the repository (Controller -> Service -> Repository).
 */
@RestController
@RequestMapping("/api/catalog")
class CatalogController(private val service: CatalogService) {

    // GET /api/catalog/products -> paginated, filterable list of products (never an unbounded List).
    // Filters (?name=, ?maxPriceCents=) bind into ProductFilter; ?page/?size/?sort into Pageable.
    @GetMapping("/products")
    fun list(
        filter: ProductFilter,
        @PageableDefault(size = 20) pageable: Pageable,
    ): Page<ProductResponse> =
        service.search(filter, pageable).map { it.toResponse() }

    // GET /api/catalog/products/{productId} -> looks up a single product by its id.
    @GetMapping("/products/{productId}")
    fun byId(@PathVariable productId: String): ProductResponse =
        service.byId(productId).toResponse()
}
