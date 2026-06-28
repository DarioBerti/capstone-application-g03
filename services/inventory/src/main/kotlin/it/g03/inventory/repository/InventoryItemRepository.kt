package it.g03.inventory.repository

import it.g03.inventory.entities.InventoryItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the InventoryItem entity in this case); the IdType is String because the productId is a string. Spring Data will generate the implementation at runtime
interface InventoryItemRepository : JpaRepository<InventoryItem, String> {

    /**
     * Atomic conditional reservation (Pillar B): a single-row conditional UPDATE is
     * atomic under the database's transaction isolation: two concurrent transactions
     * can't both pass the "available >= qty" guard for the last seat (the row lock
     * serialises them and the loser sees the already-decremented value (0 rows updated
     * = sold out)). This is our CP choice made executable!
     *
     * @return 1 if the seats were reserved, 0 if not enough seats (sold out)
     *
     * flushAutomatically: pending entity changes (e.g. a status transition set just before
     * this call) are flushed BEFORE the bulk update —> without it, clearAutomatically would
     * silently drop them from the persistence context (bug caught by InventoryServiceTest)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update InventoryItem i
           set i.available = i.available - :qty
         where i.productId = :productId
           and i.available >= :qty
        """,
    )
    fun tryDecrement(@Param("productId") productId: String, @Param("qty") qty: Int): Int

    /** Restock (release/expiry): give the seats back */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update InventoryItem i
           set i.available = i.available + :qty
         where i.productId = :productId
        """,
    )
    fun restock(@Param("productId") productId: String, @Param("qty") qty: Int): Int
}
