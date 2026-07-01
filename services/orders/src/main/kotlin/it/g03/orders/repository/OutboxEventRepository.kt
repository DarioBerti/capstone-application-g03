package it.g03.orders.repository

import it.g03.orders.entities.OutboxEvent
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import java.util.UUID

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the OutboxEvent entity in this case); the IdType is UUID because the outbox event id is a UUID. Spring Data will generate the implementation at runtime
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * Relay batch: oldest unpublished first (preserves per-order ordering) AND row-locked so a second relay
     * replica cannot grab the same rows. PESSIMISTIC_WRITE -> `FOR UPDATE`; the `jakarta.persistence.lock.timeout
     * = -2` hint is Hibernate's SKIP_LOCKED sentinel -> `FOR UPDATE SKIP LOCKED`, so the query SKIPS (never blocks
     * on) rows another replica is already draining. MUST run inside a transaction — see OutboxPublisher.drainOnce().
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    fun lockUnpublishedBatch(pageable: Pageable): List<OutboxEvent>
}
