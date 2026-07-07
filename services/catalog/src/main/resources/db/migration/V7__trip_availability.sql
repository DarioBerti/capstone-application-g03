-- Availability read-model: a local, advisory projection of Inventory's seat counts, kept fresh by consuming
-- Inventory's `seat-availability` events (database-per-service; Catalog owns only this derived hint, never
-- Inventory's tables). Search hides sold-out trips server-side with `available_seats = 0`; NULL means
-- "not yet known" and stays visible (a trip only reaches 0 via a reservation, which emits the event).
-- Advisory only: the authoritative never-oversell guard remains Inventory's atomic decrement at reserve time.

ALTER TABLE trips
    ADD COLUMN available_seats INTEGER,       -- NULL = unknown (show); 0 = sold out (hidden from search)
    ADD COLUMN seats_updated_at TIMESTAMPTZ;  -- event time of the last applied projection (out-of-order guard)

-- Supports the sold-out filter on the paginated search
CREATE INDEX idx_trips_available_seats ON trips (available_seats);
