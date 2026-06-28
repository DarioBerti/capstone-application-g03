package it.g03.inventory.entities

import jakarta.persistence.*

/**
 * The contended resource (Pillar B): finite seats for a product (a train departure).
 *
 * `available` is NEVER updated through the entity state — that would be a lost-update
 * waiting to happen. All decrements/restocks go through the atomic conditional UPDATEs
 * in [InventoryItemRepository], which is what makes "never oversell" hold under
 * concurrency. The PostgreSQL schema adds a CHECK (available >= 0) as a last line of
 * defence (see db/migration/V1__baseline.sql).
 */

@Entity  // maps this class to a database table (JPA managed entity)
@Table(name = "inventory_items")
data class InventoryItem(
    
    @Id  // primary key
    @Column(name = "product_id", length = 64)  // shared productId vocabulary with Catalog
    val productId: String,

    @Column(name = "total_seats", nullable = false)  // fixed capacity for this departure
    val totalSeats: Int,

    // contended counter: mutated ONLY by the atomic conditional UPDATEs, never by entity state
    @Column(nullable = false)
    var available: Int,
) {
    // No-arg constructor for JPA
    constructor() : this("", 0, 0)
}
