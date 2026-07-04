-- Phase 1 (human-gated checkout): a cancel-authorization for an order with NO prior authorization inserts a
-- VOIDED tombstone (amount_cents = 0) so the compensation WINS the race against a later, stale authorize-payment
-- (authorize and cancel are separate Kafka topics with no cross-topic ordering). The baseline
-- CHECK (amount_cents > 0) would reject that zero-amount tombstone, so relax it to >= 0. Genuine authorizations
-- still carry a positive amount (enforced in the service), so this only widens the door for the tombstone.
ALTER TABLE payment_authorizations DROP CONSTRAINT IF EXISTS payment_authorizations_amount_cents_check;
ALTER TABLE payment_authorizations ADD CONSTRAINT payment_authorizations_amount_cents_check CHECK (amount_cents >= 0);
