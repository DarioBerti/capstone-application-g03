-- Phase 2 (Pillar B): optimistic-lock column for the Reservation aggregate.
-- The terminal transitions RESERVED -> {CONFIRMED, RELEASED, EXPIRED} each do a read-then-(status flip + ADDITIVE
-- restock). Two drivers can race the SAME reservation: a late confirm vs the reconciler, or two reconcilers (inventory
-- runs more than one replica with no leader election). Without a guard both pass the status check and BOTH restock
-- (additive, non-convergent) -> phantom availability -> oversell, breaking the headline "never oversell" invariant.
-- The JPA @Version on Reservation makes the loser fail at flush (ObjectOptimisticLockingFailureException -> rollback)
-- BEFORE its restock runs, so the seats are given back exactly once.
-- Flyway owns this on the postgres profile; on H2 dev (Flyway disabled) Hibernate ddl-auto:update adds it.
ALTER TABLE reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
