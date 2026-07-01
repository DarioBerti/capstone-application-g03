package it.g03.inventory.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The TRANSACTIONAL OUTBOX -> the fix for the dual-write problem.
 *
 * Writing to the database AND publishing to Kafka are two different systems: a crash in between either
 * loses the event or creates a ghost. So the saga reply is written HERE, in the SAME transaction as the
 * business change (the reservation row); Debezium CDC then reads the committed rows from the Postgres WAL
 * (logical replication) and publishes them to Kafka. Guarantee: at-least-once -> consumers must be idempotent.
 *
 * Plain `class`, not `data class` — a JPA-managed entity has identity, not value, semantics (root CLAUDE.md).
 */
@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Id
    val id: UUID = UUID.randomUUID(),

    // Kafka topic to publish to
    @Column(nullable = false, length = 64)
    val topic: String = "",

    // Kafka message key (the orderId): keeps per-order ordering within a partition
    @Column(name = "aggregate_id", nullable = false, length = 64)
    val aggregateId: String = "",

    // payload class name — handy for per-type metrics/queries (nullable: legacy rows may not have it)
    @Column(name = "event_type", length = 64)
    val eventType: String? = null,

    // pre-serialised JSON payload (serialised at append time, inside the business tx); TEXT = no truncation
    @Column(nullable = false, columnDefinition = "text")
    val payload: String = "",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    // null = not yet published; the relay sets it after a successful acked send
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)
