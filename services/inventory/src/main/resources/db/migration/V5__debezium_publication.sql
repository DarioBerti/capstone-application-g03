-- Debezium CDC replaces the in-app outbox relay: the connector reads outbox_events via Postgres logical
-- replication (pgoutput) instead of a polling relay. This migration provisions the source-side objects it needs.
--
--  1) A dedicated PUBLICATION scopes logical decoding to the outbox table (+ the heartbeat table below).
--     CREATE PUBLICATION is valid at any wal_level, so this is safe on Testcontainers/local (wal_level=replica);
--     it only takes effect once the cluster runs with wal_level=logical (set by CloudNativePG in the config repo).
--  2) A single-row heartbeat table: Debezium's `heartbeat.action.query` bumps it on a timer so the replication
--     slot's confirmed LSN keeps advancing even when the outbox is idle but the (uncaptured) busy business tables
--     are not — without this the slot pins WAL and the disk fills. It is IN the publication (so the change reaches
--     the decoding stream) but NOT in the connector's table.include.list (so it is filtered, never emitted).
--  3) Grants for the `debezium` login/replication role (created by CloudNativePG managed.roles). Guarded on the
--     role existing so this migration is a no-op for those grants on Testcontainers/local, where the role is absent.

CREATE TABLE inventory.debezium_heartbeat (
    id BIGINT      PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL
);
INSERT INTO inventory.debezium_heartbeat (id, ts) VALUES (1, now());

CREATE PUBLICATION dbz_outbox FOR TABLE inventory.outbox_events, inventory.debezium_heartbeat;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'debezium') THEN
        GRANT USAGE ON SCHEMA inventory TO debezium;
        GRANT SELECT ON inventory.outbox_events TO debezium;
        GRANT SELECT, UPDATE ON inventory.debezium_heartbeat TO debezium;
    END IF;
END $$;
