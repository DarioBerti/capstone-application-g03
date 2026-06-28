package it.g03.orders.entities

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * The TRANSACTIONAL OUTBOX (design decision #4) -> the fix for the dual-write problem!
 *
 * Writing to the database AND publishing to Kafka are two different systems: a crash in between either loses the event or creates a ghost. So the event is written HERE, in the SAME transaction as the business change (order row); a separate relay ([OutboxPublisher]) reads unpublished rows and publishes them, marking publishedAt.
 * Guarantee: at-least-once -> consumers must be idempotent (they are, by design)
 */

@Entity // maps this class to a database table (JPA entity)
@Table(name = "outbox_events")
data class OutboxEvent(
    @Id // primary key of the outbox_events table (UUID as always we want to avoid a shared sequence that would contend on high load)
    val id: UUID = UUID.randomUUID(),

    // Kafka topic to publish to
    @Column(nullable = false, length = 64)
    val topic: String = "",

    // Kafka message key (the orderId): keeps per-order ordering within a partition
    @Column(name = "aggregate_id", nullable = false, length = 64)
    val aggregateId: String = "",

    // pre-serialised JSON payload (serialised at append time, inside the business tx)
    @Column(nullable = false, length = 4000)
    val payload: String = "",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    // null = not yet published; the relay sets it after a successful acked send
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)
