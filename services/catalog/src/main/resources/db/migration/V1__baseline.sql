-- Catalog schema (read store, tolerant of staleness). Flyway owns this schema; Hibernate only validates.
--
-- A "trip" is a scheduled, sellable train departure (the catalog "product"). Its business key `code`
-- follows the transit-industry format [CARRIER][SERVICE_NUMBER]-[YYYYMMDD] (e.g. FR9592-20260629) and is
-- the cross-service vocabulary: Inventory/Orders reference trips by this code (database-per-service — the
-- stores share only this code vocabulary, never each other's tables). The PARTS of the code are ALSO stored
-- as their own columns (carrier_code, service_number, service_date) so the catalog is queryable (filter by
-- carrier/date/route) instead of parsing strings.

CREATE TABLE stations (
    code    VARCHAR(8)   PRIMARY KEY,   -- short station code, e.g. 'TRN'
    name    VARCHAR(128) NOT NULL,      -- 'Torino Porta Nuova'
    city    VARCHAR(128) NOT NULL,      -- 'Torino'
    country VARCHAR(2)   NOT NULL       -- ISO 3166-1 alpha-2, e.g. 'IT'
);

CREATE TABLE trips (
    id               UUID         PRIMARY KEY,                          -- surrogate identity (app-assigned UUID)
    code             VARCHAR(32)  NOT NULL,                             -- business key, e.g. 'FR9592-20260629'
    carrier_code     VARCHAR(8)   NOT NULL,                             -- 'FR'
    service_number   VARCHAR(16)  NOT NULL,                             -- '9592'
    service_date     DATE         NOT NULL,                             -- 2026-06-29
    origin_code      VARCHAR(8)   NOT NULL REFERENCES stations (code),
    destination_code VARCHAR(8)   NOT NULL REFERENCES stations (code),
    departure_time   TIMESTAMPTZ  NOT NULL,
    arrival_time     TIMESTAMPTZ  NOT NULL,
    price_cents      BIGINT       NOT NULL CHECK (price_cents >= 0),
    currency         VARCHAR(3)   NOT NULL DEFAULT 'EUR',               -- ISO 4217 (it IS EuroTransit)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- the business code is the public/cross-service identifier: exactly one trip per code
    CONSTRAINT uq_trips_code           UNIQUE (code),
    -- a trip cannot start and end at the same station
    CONSTRAINT chk_trips_distinct_ends CHECK (origin_code <> destination_code),
    -- arrival must be after departure
    CONSTRAINT chk_trips_time_order    CHECK (arrival_time > departure_time)
);

-- Route + time search: the natural "trains from A to B departing around T" query
CREATE INDEX idx_trips_route_departure ON trips (origin_code, destination_code, departure_time);
-- Carrier + date filter (e.g. all Frecciarossa departures on a day)
CREATE INDEX idx_trips_carrier_date ON trips (carrier_code, service_date);
