package it.g03.catalog.Entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * A sellable offer (e.g. a train departure)
 * Catalog OWNS product/pricing data; Inventory owns the seat counts for the same productIds. The two stores are deliberately separate (database-per-service): they are synchronised only by sharing the productId vocabulary in their seed data:
 * in this way, Catalog can be stale or down without affecting the money path!
 */

@Entity
@Table(name = "products")
data class Product(
    @Id
    @Column(name = "product_id", length = 64)
    val productId: String,

    @Column(nullable = false, length = 128)
    val name: String,

    @Column(name = "price_cents", nullable = false)
    val priceCents: Long,
) {
    // No-arg constructor for JPA
    constructor() : this("", "", 0)
}