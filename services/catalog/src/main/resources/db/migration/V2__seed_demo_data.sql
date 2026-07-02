-- Demo seed: a few real-looking departures with finite seats.
-- Trip `code`s are the SHARED cross-service vocabulary: Inventory seeds seat counts for the SAME codes
-- (database-per-service — the two stores share only this code vocabulary, never each other's tables).
-- Service date 2026-06-29 keeps the demo departures in the near future.

INSERT INTO stations (code, name, city, country) VALUES
    ('TRN', 'Torino Porta Nuova', 'Torino', 'IT'),
    ('MIL', 'Milano Centrale',    'Milano', 'IT'),
    ('ROM', 'Roma Termini',       'Roma',   'IT'),
    ('NAP', 'Napoli Centrale',    'Napoli', 'IT');

INSERT INTO trips (id, code, carrier_code, service_number, service_date,
                   origin_code, destination_code, departure_time, arrival_time, price_cents, currency) VALUES
    ('00000000-0000-0000-0000-000000000001', 'FR9592-20260629', 'FR', '9592', DATE '2026-06-29',
        'TRN', 'MIL', TIMESTAMPTZ '2026-06-29 08:00:00+02', TIMESTAMPTZ '2026-06-29 09:00:00+02', 1500, 'EUR'),
    ('00000000-0000-0000-0000-000000000002', 'FR9612-20260629', 'FR', '9612', DATE '2026-06-29',
        'TRN', 'ROM', TIMESTAMPTZ '2026-06-29 09:30:00+02', TIMESTAMPTZ '2026-06-29 13:30:00+02', 4900, 'EUR'),
    ('00000000-0000-0000-0000-000000000003', 'IC1015-20260629', 'IC', '1015', DATE '2026-06-29',
        'MIL', 'NAP', TIMESTAMPTZ '2026-06-29 10:15:00+02', TIMESTAMPTZ '2026-06-29 15:15:00+02', 6900, 'EUR'),
    -- last-seat contention demo: Inventory seeds this code with a single seat
    ('00000000-0000-0000-0000-000000000004', 'FR9999-20260629', 'FR', '9999', DATE '2026-06-29',
        'ROM', 'NAP', TIMESTAMPTZ '2026-06-29 18:00:00+02', TIMESTAMPTZ '2026-06-29 19:10:00+02', 1000, 'EUR');
