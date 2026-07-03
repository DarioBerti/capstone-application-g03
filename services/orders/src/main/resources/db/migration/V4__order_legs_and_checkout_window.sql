-- Phase 1 (human-gated round-trip checkout). A booking becomes DRAFT -> add legs (reserve-on-add) -> HELD ->
-- pay -> AUTHORIZED -> CONFIRMED. Trip/qty move from the order onto per-leg rows; the order keeps a derived
-- amount cache and the backend-owned hold window (expires_at + payment_initiated_at).

-- Per-leg rows: OUTBOUND (required) + optional RETURN, always distinct trip codes. UNIQUE(order_id, trip_code)
-- mirrors inventory's reservation key so a redelivered reservation-result / seats-confirmed reply maps to
-- exactly one leg. "At most one ACTIVE leg per direction" is enforced in the service (a SOLD_OUT/RELEASED leg
-- must not block re-adding that direction, so it cannot be a DB uniqueness on direction).
CREATE TABLE order_legs (
    id               UUID         PRIMARY KEY,
    order_id         UUID         NOT NULL REFERENCES orders (id),
    direction        VARCHAR(16)  NOT NULL,                                 -- OUTBOUND | RETURN
    trip_code        VARCHAR(64)  NOT NULL,
    qty              INTEGER      NOT NULL CHECK (qty > 0),
    unit_price_cents BIGINT       NOT NULL CHECK (unit_price_cents >= 0),   -- price snapshot at add-time
    status           VARCHAR(16)  NOT NULL,                                 -- PENDING|RESERVED|SOLD_OUT|CONFIRMED|EXPIRED|RELEASED
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_legs_order_trip UNIQUE (order_id, trip_code)
);

-- The fan-out/fan-in saga loads every leg of an order
CREATE INDEX idx_order_legs_order ON order_legs (order_id);

-- Backend-owned hold window on the order: stamped when the first leg is added; the dual-cause flag at expiry.
ALTER TABLE orders ADD COLUMN expires_at           TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN payment_initiated_at TIMESTAMPTZ;

-- An empty DRAFT has no trip, no qty, and amount 0 — so relax the single-item baseline constraints. Trip/qty now
-- live per-leg (the order no longer maps those columns); amount_cents is a derived cache that is 0 until a leg is
-- held. The baseline CHECK (amount_cents > 0) would reject a DRAFT, so relax it to >= 0.
ALTER TABLE orders ALTER COLUMN trip_code    DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN qty          DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN amount_cents SET DEFAULT 0;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_qty_check;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_amount_cents_check;
ALTER TABLE orders ADD CONSTRAINT orders_amount_cents_check CHECK (amount_cents >= 0);
