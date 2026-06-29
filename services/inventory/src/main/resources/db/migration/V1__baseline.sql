-- Inventory schema (Pillar B: the contended resource). Flyway owns this schema; Hibernate only validates.
-- Keyed by the trip `code` — the shared cross-service vocabulary (database-per-service; Inventory owns seat
-- counts for the same trip codes Catalog publishes, never reads Catalog's tables).

CREATE TABLE trip_seats (
    trip_code   VARCHAR(64) PRIMARY KEY,                          -- shared trip-code vocabulary, e.g. 'FR9592-20260629'
    total_seats INTEGER     NOT NULL CHECK (total_seats >= 0),
    -- Last line of defence for "never oversell": even a code bug cannot push the seat count below zero
    -- (the database refuses the write). The first line is the atomic conditional UPDATE in TripSeatsRepository.
    available   INTEGER     NOT NULL CHECK (available >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()                -- bumped by the atomic reserve/restock UPDATEs
);

CREATE TABLE reservations (
    id          UUID         PRIMARY KEY,
    order_id    VARCHAR(64)  NOT NULL,
    trip_code   VARCHAR(64)  NOT NULL REFERENCES trip_seats (trip_code),
    qty         INTEGER      NOT NULL CHECK (qty > 0),
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    confirmed_at TIMESTAMPTZ,                                     -- set when RESERVED -> CONFIRMED
    released_at  TIMESTAMPTZ,                                     -- set when RESERVED -> RELEASED/EXPIRED (restocked)
    -- The idempotency record of the money path: one reservation outcome per (order, trip). Duplicated
    -- deliveries either replay the stored outcome or lose this constraint race and roll back their whole
    -- transaction (decrement included).
    CONSTRAINT uq_reservations_order_trip UNIQUE (order_id, trip_code)
);

-- Reconciler sweep: find RESERVED holds whose TTL elapsed
CREATE INDEX idx_reservations_status_expires ON reservations (status, expires_at);
