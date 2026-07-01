-- Inventory transactional outbox: saga replies (reservation-result, seats-confirmed) are INSERTed here in the SAME
-- transaction as the seat change (no dual-write); the relay (OutboxPublisher) publishes unpublished rows to Kafka and
-- stamps published_at. Flyway owns this schema; Hibernate only validates. Mirrors the Orders outbox (services/orders V1).

CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    topic        VARCHAR(64)  NOT NULL,
    aggregate_id VARCHAR(64)  NOT NULL,                              -- the orderId (Kafka key: per-order ordering)
    event_type   VARCHAR(64),                                       -- payload class name (per-type metrics/queries)
    payload      TEXT         NOT NULL,                              -- pre-serialised JSON (TEXT: no truncation)
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

-- Partial index: the relay only ever scans unpublished rows (kept tiny by definition)
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
