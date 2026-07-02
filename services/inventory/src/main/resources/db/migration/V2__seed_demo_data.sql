-- Demo seed: finite seats for the SAME trip codes Catalog seeds (database-per-service: the two stores
-- share only the trip-code vocabulary). Seat counts mirror the canonical demo set; FR9999-20260629 is the
-- single-seat "last-seat contention" demo used in the chaos runs.
INSERT INTO trip_seats (trip_code, total_seats, available) VALUES
    ('FR9592-20260629', 120, 120),
    ('FR9612-20260629',  80,  80),
    ('IC1015-20260629', 100, 100),
    ('FR9999-20260629',   1,   1);
