package it.g03.orders.repository

import it.g03.orders.entities.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the OutboxEvent entity in this case); the IdType is UUID because the outbox event id is a UUID. Spring Data will generate the implementation at runtime
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    // Relay batch: oldest unpublished first -> preserves per-order event ordering!
    // Spring Data derives the query from the method name: selects the first 50 events whose publishedAt is null, ordered by createdAt ascending (oldest first)
    fun findTop50ByPublishedAtIsNullOrderByCreatedAtAsc(): List<OutboxEvent>
}
