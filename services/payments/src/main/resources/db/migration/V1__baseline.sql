-- Payments schema (Pillar B: strict idempotency — must not double-charge).
-- Flyway owns this schema; Hibernate only validates.

CREATE TABLE payment_authorizations (
    id            UUID        PRIMARY KEY,

    -- The idempotency key of the money path: AT MOST ONE authorization outcome per order. A duplicated/retried
    -- authorize either replays the stored outcome or loses this constraint race and rolls back its whole
    -- transaction (charge included).
    order_id      VARCHAR(64) NOT NULL,

    amount_cents  BIGINT      NOT NULL CHECK (amount_cents > 0),
    currency      VARCHAR(3)  NOT NULL DEFAULT 'EUR',                -- ISO 4217 (it IS EuroTransit)

    status        VARCHAR(16) NOT NULL,                              -- AUTHORIZED | DECLINED | VOIDED

    psp_reference  VARCHAR(64),                                      -- (mock) PSP authorization code, set on AUTHORIZED
    decline_reason VARCHAR(64),                                      -- why a DECLINED authorization was declined

    created_at    TIMESTAMPTZ NOT NULL,
    captured_at   TIMESTAMPTZ,                                       -- when the charge was captured (auth+capture in this model)
    voided_at     TIMESTAMPTZ,                                       -- when the charge was voided/refunded (saga compensation)

    -- This UNIQUE(order_id) IS the idempotency record: it is what enforces "must not double-charge"
    CONSTRAINT uq_payment_auth_order UNIQUE (order_id)
);
