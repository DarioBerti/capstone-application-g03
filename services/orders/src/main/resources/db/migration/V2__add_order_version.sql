-- Phase 2 (Pillar C): optimistic-lock column for the Order aggregate.
-- The recovery sweep (OrderRecoverySweeper) adds a SECOND saga driver alongside Kafka redelivery, so two
-- concurrent handle() runs can race on the same order. The JPA @Version on Order makes the loser of a
-- concurrent TERMINAL transition fail at commit (rollback) instead of appending a duplicate
-- order-confirmed / notification-requested event to the outbox.
-- Flyway owns this on the postgres profile; on H2 dev (Flyway disabled) Hibernate ddl-auto:update adds it.
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
