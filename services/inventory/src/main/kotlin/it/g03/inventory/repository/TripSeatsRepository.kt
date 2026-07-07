package it.g03.inventory.repository

import it.g03.inventory.entities.TripSeats
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

// JpaRepository<TripSeats, String> (the id is the trip_code String). Spring Data generates the implementation.
interface TripSeatsRepository : JpaRepository<TripSeats, String> {

    /**
     * Atomic conditional reservation (Pillar B): a single-row conditional UPDATE is atomic under the
     * database's transaction isolation — two concurrent transactions can't both pass the "available >= qty"
     * guard for the last seat (the row lock serialises them and the loser sees the already-decremented value:
     * 0 rows updated = sold out). This is our CP choice made executable!
     *
     * @return 1 if the seats were reserved, 0 if not enough seats (sold out)
     *
     * flushAutomatically: pending entity changes (e.g. a status transition set just before this call) are
     * flushed BEFORE the bulk update — without it, clearAutomatically would silently drop them from the
     * persistence context (bug caught by InventoryServiceTest). updated_at is bumped in the same statement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update TripSeats t
           set t.available = t.available - :qty,
               t.updatedAt = current_timestamp
         where t.tripCode = :tripCode
           and t.available >= :qty
        """,
    )
    fun tryDecrement(@Param("tripCode") tripCode: String, @Param("qty") qty: Int): Int

    /** Restock (release/expiry): give the seats back (and bump updated_at). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update TripSeats t
           set t.available = t.available + :qty,
               t.updatedAt = current_timestamp
         where t.tripCode = :tripCode
        """,
    )
    fun restock(@Param("tripCode") tripCode: String, @Param("qty") qty: Int): Int

    /** Every known trip code — drives the one-shot availability snapshot republish (no seat mutation). */
    @Query("select t.tripCode from TripSeats t")
    fun findAllTripCodes(): List<String>
}
