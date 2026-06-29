-- Orders schema: the saga aggregate + the TRANSACTIONAL OUTBOX.
-- Flyway owns this schema; Hibernate only validates.

CREATE TABLE orders (
    id               UUID         PRIMARY KEY,
    trip_code        VARCHAR(64)  NOT NULL,                         -- shared trip-code vocabulary (e.g. 'FR9592-20260629')
    qty              INTEGER      NOT NULL CHECK (qty > 0),
    amount_cents     BIGINT       NOT NULL CHECK (amount_cents > 0),
    currency         VARCHAR(3)   NOT NULL DEFAULT 'EUR',           -- ISO 4217 (it IS EuroTransit)
    buyer_sub        VARCHAR(255) NOT NULL,                         -- Keycloak `sub` of the authenticated buyer
    buyer_email      VARCHAR(320),                                  -- buyer email snapshot from the JWT (may be absent)
    -- Client-supplied Idempotency-Key: a retried POST /api/orders with the same key replays the existing
    -- order instead of creating a duplicate. NULLable (key is optional); Postgres allows many NULLs under UNIQUE.
    idempotency_key  VARCHAR(64),
    status           VARCHAR(16)  NOT NULL,
    rejection_reason VARCHAR(32),                                   -- why a REJECTED order was rejected (null otherwise)
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key)
);

-- "My orders" lookups scoped to the authenticated buyer
CREATE INDEX idx_orders_buyer_sub ON orders (buyer_sub);

-- The outbox: events are INSERTed here in the SAME transaction as the business change (no dual-write); the
-- relay publishes unpublished rows to Kafka and stamps published_at.
CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    topic        VARCHAR(64)  NOT NULL,
    aggregate_id VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64),                                       -- payload class name (per-type metrics/queries)
    payload      TEXT         NOT NULL,                             -- pre-serialised JSON (TEXT: no truncation)
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

-- Partial index: the relay only ever scans unpublished rows (kept tiny by definition)
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
