-- Round-trip demo seed: return-direction departures with near-future dates, so the storefront can exercise
-- BOTH a one-way and a two-way (outbound + return) booking. Reuses the stations already seeded in V2
-- (TRN/MIL/ROM/NAP). Trip `code`s stay the shared cross-service vocabulary — Inventory seeds seat counts for
-- the SAME codes (database-per-service). created_at/updated_at are DB-owned (DEFAULT now()).

INSERT INTO trips (id, code, carrier_code, service_number, service_date,
                   origin_code, destination_code, departure_time, arrival_time, price_cents, currency) VALUES
    -- Milano <-> Roma (round-trip capable)
    ('00000000-0000-0000-0000-000000000005', 'IT8001-20260706', 'IT', '8001', DATE '2026-07-06',
        'MIL', 'ROM', TIMESTAMPTZ '2026-07-06 07:00:00+02', TIMESTAMPTZ '2026-07-06 10:00:00+02', 5900, 'EUR'),
    ('00000000-0000-0000-0000-000000000006', 'IT8002-20260706', 'IT', '8002', DATE '2026-07-06',
        'ROM', 'MIL', TIMESTAMPTZ '2026-07-06 18:00:00+02', TIMESTAMPTZ '2026-07-06 21:00:00+02', 5900, 'EUR'),
    -- Torino <-> Milano (round-trip capable)
    ('00000000-0000-0000-0000-000000000007', 'FR9700-20260706', 'FR', '9700', DATE '2026-07-06',
        'TRN', 'MIL', TIMESTAMPTZ '2026-07-06 08:00:00+02', TIMESTAMPTZ '2026-07-06 09:00:00+02', 1600, 'EUR'),
    ('00000000-0000-0000-0000-000000000008', 'FR9701-20260706', 'FR', '9701', DATE '2026-07-06',
        'MIL', 'TRN', TIMESTAMPTZ '2026-07-06 19:00:00+02', TIMESTAMPTZ '2026-07-06 20:00:00+02', 1600, 'EUR'),
    -- Roma <-> Napoli (round-trip capable)
    ('00000000-0000-0000-0000-000000000009', 'FR9800-20260706', 'FR', '9800', DATE '2026-07-06',
        'ROM', 'NAP', TIMESTAMPTZ '2026-07-06 09:00:00+02', TIMESTAMPTZ '2026-07-06 10:10:00+02', 2500, 'EUR'),
    ('00000000-0000-0000-0000-00000000000a', 'FR9801-20260706', 'FR', '9801', DATE '2026-07-06',
        'NAP', 'ROM', TIMESTAMPTZ '2026-07-06 17:00:00+02', TIMESTAMPTZ '2026-07-06 18:10:00+02', 2500, 'EUR');
